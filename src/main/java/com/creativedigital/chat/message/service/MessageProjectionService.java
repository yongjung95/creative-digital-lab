package com.creativedigital.chat.message.service;

import com.creativedigital.chat.common.config.AsyncConfig;
import com.creativedigital.chat.common.time.DbClock;
import com.creativedigital.chat.event.dto.EventAppended;
import com.creativedigital.chat.event.dto.EventResult;
import com.creativedigital.chat.event.service.EventService;
import com.creativedigital.chat.message.config.MessageProjectionProperties;
import com.creativedigital.chat.message.entity.MessageProjection;
import com.creativedigital.chat.message.entity.MessageProjectionCheckpoint;
import com.creativedigital.chat.message.repository.MessageProjectionCheckpointRepository;
import com.creativedigital.chat.message.repository.MessageProjectionRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * message_projection 비동기 반영.
 * 반영은 항상 "checkpoint 이후 이벤트를 sequence 순서대로 전부"(catchUp) 방식이다. 이벤트 하나만 반영하는 경로는 없다.
 * - 실행 순서가 뒤바뀌어도: 먼저 실행된 작업이 앞 이벤트까지 같이 반영하고, 늦은 작업은 할 일이 없어 끝난다
 * - 동시에 실행돼도: checkpoint 행 Lock으로 세션당 한 번에 하나만 반영해 중복 반영이 없다
 * - 작업이 버려져도: 같은 세션의 다음 작업이나 안전망 스케줄러가 밀린 것을 한꺼번에 반영한다
 */
@Slf4j
@Service
public class MessageProjectionService {

    private static final int BATCH_SIZE = 500;
    private static final int MAX_ATTEMPTS = 3;
    private static final long INITIAL_BACKOFF_MILLIS = 100;
    private static final String METRIC_NAME = "message.projection";

    private final MessageProjectionRepository messageProjectionRepository;
    private final MessageProjectionCheckpointRepository checkpointRepository;
    private final EventService eventService;
    private final MessageProjectionProperties properties;
    private final DbClock dbClock;
    private final TransactionTemplate transactionTemplate;
    private final TaskExecutor messageProjectionExecutor;
    private final MeterRegistry meterRegistry;

    public MessageProjectionService(
        MessageProjectionRepository messageProjectionRepository,
        MessageProjectionCheckpointRepository checkpointRepository,
        EventService eventService,
        MessageProjectionProperties properties,
        DbClock dbClock,
        TransactionTemplate transactionTemplate,
        @Qualifier(AsyncConfig.MESSAGE_PROJECTION_EXECUTOR) TaskExecutor messageProjectionExecutor,
        MeterRegistry meterRegistry
    ) {
        this.messageProjectionRepository = messageProjectionRepository;
        this.checkpointRepository = checkpointRepository;
        this.eventService = eventService;
        this.properties = properties;
        this.dbClock = dbClock;
        this.transactionTemplate = transactionTemplate;
        this.messageProjectionExecutor = messageProjectionExecutor;
        this.meterRegistry = meterRegistry;
    }

    /**
     * 메시지 이벤트가 아니어도 작업을 넘긴다. 그래야 JOIN 같은 이벤트 뒤에도 반영 위치(projectedSequence)가 뒤처지지 않는다.
     * 큐가 차서 거절되면 버리고 기록만 남긴다. 다음 작업이나 안전망 스케줄러가 따라잡는다.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onEventAppended(EventAppended event) {
        try {
            messageProjectionExecutor.execute(() -> catchUpWithRetry(event.sessionId()));
        } catch (TaskRejectedException e) {
            log.warn("message_projection_task_rejected sessionId={} sequence={}", event.sessionId(), event.sequence());
            meterRegistry.counter(METRIC_NAME, "result", "rejected").increment();
        }
    }

    /**
     * checkpoint 이후 이벤트를 전부 반영한다. 트랜잭션이 길어지지 않게 BATCH_SIZE씩 나눠 커밋한다.
     *
     * @return 반영 위치를 전진시킨 이벤트 수 (메시지가 아닌 이벤트 포함)
     */
    public int catchUp(Long sessionId) {
        createCheckpointIfAbsent(sessionId);

        int totalCount = 0;
        while (true) {
            int count = transactionTemplate.execute(status -> applyNextBatch(sessionId));
            totalCount += count;
            if (count < BATCH_SIZE) return totalCount;
        }
    }

    /**
     * 안전망: 최근 갱신된 세션 중 반영이 밀린 세션을 찾아 따라잡는다.
     * 한 세션이 실패해도 다른 세션은 계속 처리하고, 실패한 세션은 다음 주기에 다시 시도한다.
     *
     * @return 따라잡은 세션 수
     */
    public int catchUpLaggingSessions() {
        LocalDateTime since = dbClock.now().minus(properties.scheduler().lookback());
        return catchUpSessions(checkpointRepository.findLaggingSessionIds(since));
    }

    /**
     * checkpoint 행은 Lock 트랜잭션 밖에서 먼저 만든다.
     * 없는 행을 FOR UPDATE로 조회하면 MariaDB가 갭 락을 걸어서, 두 작업이 동시에 첫 생성을 시도할 때 서로의 INSERT를 막아 데드락이 난다.
     * 이미 있으면 Lock 없는 조회로 끝나서 매번 INSERT를 시도하지 않는다.
     */
    private void createCheckpointIfAbsent(Long sessionId) {
        if (checkpointRepository.existsById(sessionId)) return;
        transactionTemplate.executeWithoutResult(status -> checkpointRepository.insertIfAbsent(sessionId));
    }

    private int applyNextBatch(Long sessionId) {
        MessageProjectionCheckpoint checkpoint = checkpointRepository.findForUpdate(sessionId)
            .orElseThrow(() -> new IllegalStateException("checkpoint가 없습니다. sessionId=" + sessionId));
        List<EventResult> events = eventService.findEventsForReplay(
            sessionId,
            checkpoint.getLastSequence(),
            Long.MAX_VALUE,
            BATCH_SIZE
        );
        if (events.isEmpty()) return 0;

        events.forEach(this::apply);
        checkpoint.advanceTo(events.getLast().sequence(), dbClock.now());
        return events.size();
    }

    // 메시지 시각은 이벤트 저장 시각(created_at)을 쓴다. 반영 시각을 쓰면 언제 반영했는지에 따라 결과가 달라진다
    private void apply(EventResult event) {
        switch (event.eventType()) {
            case MESSAGE -> messageProjectionRepository.save(
                MessageProjection.sent(
                    event.eventId(),
                    event.sessionId(),
                    event.sequence(),
                    event.participantId(),
                    content(event),
                    event.createdAt()
                ));
            case MESSAGE_EDITED -> findTarget(event).ifPresent(message -> message.edit(content(event), event.createdAt()));
            case MESSAGE_DELETED -> findTarget(event).ifPresent(message -> message.delete(event.createdAt()));
            default -> {
                // 메시지와 무관한 이벤트는 반영 위치만 전진한다
            }
        }
    }

    // 대상은 Command 검증에서 확인했으므로 정상이면 항상 있다. 없으면 기록만 남기고 건너뛴다
    private Optional<MessageProjection> findTarget(EventResult event) {
        Optional<MessageProjection> target = messageProjectionRepository.findByMessageId(event.targetEventId());
        if (target.isEmpty()) {
            log.warn(
                "message_projection_target_missing sessionId={} sequence={} targetEventId={}",
                event.sessionId(),
                event.sequence(),
                event.targetEventId()
            );
        }
        return target;
    }

    /**
     * 실패하면 간격을 2배씩 늘려 재시도한다 (100ms → 200ms).
     * 최종 실패는 기록만 남기고, 다음 작업이나 안전망 스케줄러가 따라잡는다.
     */
    private void catchUpWithRetry(Long sessionId) {
        long backoffMillis = INITIAL_BACKOFF_MILLIS;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                catchUp(sessionId);
                return;
            } catch (RuntimeException e) {
                if (attempt == MAX_ATTEMPTS) {
                    log.error("message_projection_failed sessionId={} attempts={}", sessionId, attempt, e);
                    meterRegistry.counter(METRIC_NAME, "result", "failed").increment();
                    return;
                }
                log.warn("message_projection_retry sessionId={} attempt={}", sessionId, attempt);
                if (!sleep(backoffMillis)) return;
                backoffMillis *= 2;
            }
        }
    }

    // 종료 중 인터럽트되면 재시도를 멈춘다
    private boolean sleep(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private String content(EventResult event) {
        return event.payload().path("content").asString();
    }

    /**
     * 앱 시작 시 한 번: 기간 제한 없이 반영이 밀린 세션을 전부 따라잡는다.
     * 작업 유실은 서버가 죽었을 때 생기는데, 서버가 lookback(1시간)보다 오래 꺼져 있었다면 주기 안전망이 그 세션을 찾지 못한다.
     * 전체를 훑는 쿼리라 1분마다가 아니라 시작할 때만 실행한다.
     *
     * @return 따라잡은 세션 수
     */
    public int catchUpAllLaggingSessions() {
        return catchUpSessions(checkpointRepository.findAllLaggingSessionIds());
    }

    // 한 세션이 실패해도 다른 세션은 계속 처리하고, 실패한 세션은 다음 주기에 다시 시도한다
    private int catchUpSessions(List<Long> sessionIds) {
        int caughtUpCount = 0;
        for (Long sessionId : sessionIds) {
            try {
                int eventCount = catchUp(sessionId);
                log.info("message_projection_caught_up sessionId={} eventCount={}", sessionId, eventCount);
                caughtUpCount++;
            } catch (RuntimeException e) {
                log.error("message_projection_catch_up_failed sessionId={}", sessionId, e);
                meterRegistry.counter(METRIC_NAME, "result", "failed").increment();
            }
        }
        return caughtUpCount;
    }

}

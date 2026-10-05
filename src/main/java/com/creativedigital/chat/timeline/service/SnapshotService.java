package com.creativedigital.chat.timeline.service;

import com.creativedigital.chat.common.config.AsyncConfig;
import com.creativedigital.chat.common.error.BusinessException;
import com.creativedigital.chat.common.error.ErrorCode;
import com.creativedigital.chat.common.time.DbClock;
import com.creativedigital.chat.event.dto.EventAppended;
import com.creativedigital.chat.session.entity.Session;
import com.creativedigital.chat.session.repository.SessionRepository;
import com.creativedigital.chat.timeline.config.SnapshotProperties;
import com.creativedigital.chat.timeline.dto.SnapshotResult;
import com.creativedigital.chat.timeline.entity.Snapshot;
import com.creativedigital.chat.timeline.repository.SnapshotRepository;
import com.creativedigital.chat.timeline.state.SessionState;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Snapshot 생성.
 * 주 경로: 이벤트 커밋 후 sequence가 간격의 배수면 전용 스레드 풀에서 비동기로 생성한다.
 * 안전망: 스케줄러가 주기적으로 빠진 Snapshot을 찾아 채운다 (fillMissingSnapshots).
 * 같은 Snapshot을 주 경로·스케줄러·여러 인스턴스가 동시에 만들어도 UNIQUE(session_id, sequence) 위반을 "이미 있음"으로 처리해 결과가 같다 (멱등).
 */
@Slf4j
@Service
public class SnapshotService {

    private static final int MAX_ATTEMPTS = 3;
    private static final long INITIAL_BACKOFF_MILLIS = 100;
    private static final String METRIC_NAME = "snapshot.create";

    private final SnapshotRepository snapshotRepository;
    private final SessionRepository sessionRepository;
    private final TimelineService timelineService;
    private final SnapshotProperties snapshotProperties;
    private final JsonMapper jsonMapper;
    private final DbClock dbClock;
    private final TransactionTemplate transactionTemplate;
    private final TaskExecutor snapshotExecutor;
    private final MeterRegistry meterRegistry;

    public SnapshotService(
        SnapshotRepository snapshotRepository,
        SessionRepository sessionRepository,
        TimelineService timelineService,
        SnapshotProperties snapshotProperties,
        JsonMapper jsonMapper,
        DbClock dbClock,
        TransactionTemplate transactionTemplate,
        @Qualifier(AsyncConfig.SNAPSHOT_EXECUTOR) TaskExecutor snapshotExecutor,
        MeterRegistry meterRegistry
    ) {
        this.snapshotRepository = snapshotRepository;
        this.sessionRepository = sessionRepository;
        this.timelineService = timelineService;
        this.snapshotProperties = snapshotProperties;
        this.jsonMapper = jsonMapper;
        this.dbClock = dbClock;
        this.transactionTemplate = transactionTemplate;
        this.snapshotExecutor = snapshotExecutor;
        this.meterRegistry = meterRegistry;
    }

    /**
     * 커밋된 이벤트가 Snapshot을 만들 차례인 sequence(간격의 배수. 간격 1000이면 1000, 2000, ...)일 때만 스레드 풀에 넘긴다.
     * 모든 이벤트를 넘기면 만들 차례가 아닌 작업이 큐를 채우므로, 차례인지 확인은 커밋한 스레드에서 먼저 한다.
     * 큐가 차서 거절되면 버리고 기록만 남긴다. 안전망 스케줄러가 나중에 채운다.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onEventAppended(EventAppended event) {
        if (event.sequence() % snapshotProperties.interval() != 0) return;

        try {
            snapshotExecutor.execute(() -> createWithRetry(event.sessionId(), event.sequence()));
        } catch (TaskRejectedException e) {
            log.warn("snapshot_task_rejected sessionId={} sequence={}", event.sessionId(), event.sequence());
            meterRegistry.counter(METRIC_NAME, "result", "rejected").increment();
        }
    }

    // POST /sessions/{id}/snapshots: 현재 last_sequence 기준 수동 생성
    public SnapshotResult createLatest(Long sessionId) {
        long lastSequence = sessionRepository.findById(sessionId)
            .map(Session::getLastSequence)
            .orElseThrow(() -> new BusinessException(ErrorCode.SESSION_NOT_FOUND));
        return create(sessionId, lastSequence);
    }

    /**
     * sequence 시점 Snapshot을 만든다. 이미 있으면 기존 것을 반환한다.
     * 동시에 같은 Snapshot을 만들다 UNIQUE 위반이 나면 롤백 후 새 트랜잭션에서 먼저 저장된 것을 반환한다.
     */
    public SnapshotResult create(Long sessionId, long sequence) {
        try {
            return transactionTemplate.execute(status -> createInTransaction(sessionId, sequence));
        } catch (DataIntegrityViolationException e) {
            return transactionTemplate.execute(status -> snapshotRepository.findBySessionIdAndSequence(sessionId, sequence)
                .map(SnapshotResult::existing)
                .orElseThrow(() -> e));
        }
    }

    /**
     * 안전망: 최근 갱신된 세션 중 간격의 배수 Snapshot이 빠진 세션을 찾아, 빠진 것을 전부 오름차순으로 만든다 (중간 구멍 포함).
     * 한 세션이 실패해도 다른 세션은 계속 처리하고, 실패한 세션은 다음 주기에 다시 시도한다.
     *
     * @return 새로 만든 Snapshot 수
     */
    public int fillMissingSnapshots() {
        long interval = snapshotProperties.interval();
        LocalDateTime since = dbClock.now().minus(snapshotProperties.scheduler().lookback());
        List<Long> sessionIds = snapshotRepository.findSessionIdsMissingSnapshots(since, interval);

        int createdCount = 0;
        for (Long sessionId : sessionIds) {
            try {
                createdCount += fillMissing(sessionId, interval);
            } catch (RuntimeException e) {
                log.error("snapshot_fill_failed sessionId={}", sessionId, e);
                meterRegistry.counter(METRIC_NAME, "result", "failed").increment();
            }
        }
        if (createdCount > 0) {
            log.info("snapshot_filled sessionCount={} createdCount={}", sessionIds.size(), createdCount);
        }
        return createdCount;
    }

    private SnapshotResult createInTransaction(Long sessionId, long sequence) {
        Optional<Snapshot> existing = snapshotRepository.findBySessionIdAndSequence(sessionId, sequence);
        if (existing.isPresent()) return SnapshotResult.existing(existing.get());

        SessionState state = timelineService.restoreState(sessionId, sequence).state();
        Snapshot saved = snapshotRepository.saveAndFlush(
            Snapshot.create(
                sessionId,
                sequence,
                jsonMapper.writeValueAsString(state),
                dbClock.now()
            ));
        log.info("snapshot_created sessionId={} sequence={}", sessionId, sequence);
        meterRegistry.counter(METRIC_NAME, "result", "created").increment();
        return SnapshotResult.created(saved);
    }

    /**
     * 실패하면 간격을 2배씩 늘려 재시도한다 (100ms → 200ms).
     * 최종 실패는 기록만 남기고, 안전망 스케줄러가 다음 주기에 다시 만든다.
     */
    private void createWithRetry(Long sessionId, long sequence) {
        long backoffMillis = INITIAL_BACKOFF_MILLIS;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                create(sessionId, sequence);
                return;
            } catch (RuntimeException e) {
                if (attempt == MAX_ATTEMPTS) {
                    log.error("snapshot_create_failed sessionId={} sequence={} attempts={}", sessionId, sequence, attempt, e);
                    meterRegistry.counter(METRIC_NAME, "result", "failed").increment();
                    return;
                }
                log.warn("snapshot_create_retry sessionId={} sequence={} attempt={}", sessionId, sequence, attempt);
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

    private int fillMissing(Long sessionId, long interval) {
        long lastSequence = sessionRepository.findById(sessionId)
            .map(Session::getLastSequence)
            .orElse(0L);
        Set<Long> existing = new HashSet<>(snapshotRepository.findBoundarySequences(sessionId, interval));

        int createdCount = 0;
        for (long sequence = interval; sequence <= lastSequence; sequence += interval) {
            if (existing.contains(sequence)) continue;
            if (create(sessionId, sequence).created()) createdCount++;
        }
        return createdCount;
    }

}

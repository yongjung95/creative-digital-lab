package com.creativedigital.chat.session.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.creativedigital.chat.TestcontainersConfiguration;
import com.creativedigital.chat.common.error.BusinessException;
import com.creativedigital.chat.common.error.ErrorCode;
import com.creativedigital.chat.event.dto.AppendResult;
import com.creativedigital.chat.event.entity.EventType;
import com.creativedigital.chat.session.dto.ClientEventCommand;
import com.creativedigital.chat.session.dto.JoinCommand;
import com.creativedigital.chat.session.entity.Participant;
import com.creativedigital.chat.session.entity.ParticipantStatus;
import com.creativedigital.chat.session.entity.SessionStatus;
import com.creativedigital.chat.session.repository.ParticipantRepository;
import com.creativedigital.chat.session.repository.SessionRepository;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class SessionServiceCommandTest {

    @Autowired
    private SessionService sessionService;

    @Autowired
    private SessionRepository sessionRepository;

    @Autowired
    private ParticipantRepository participantRepository;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private DataSource dataSource;

    // ===== 세션 생성 =====

    @Test
    void 세션을_생성하면_sequence_1의_SESSION_CREATED가_저장된다() {
        AppendResult result = sessionService.createSession(newEventId());

        assertThat(result.duplicate()).isFalse();
        assertThat(result.event().eventType()).isEqualTo(EventType.SESSION_CREATED);
        assertThat(result.event().sequence()).isEqualTo(1);
        assertThat(sessionRepository.findById(result.event().sessionId())).isPresent();
    }

    @Test
    void 같은_eventId로_세션_생성을_재시도하면_같은_세션을_반환한다() {
        String eventId = newEventId();

        AppendResult first = sessionService.createSession(eventId);
        AppendResult retry = sessionService.createSession(eventId);

        assertThat(retry.duplicate()).isTrue();
        assertThat(retry.event().sessionId()).isEqualTo(first.event().sessionId());
    }

    @Test
    void 같은_세션_생성_요청이_동시에_와도_세션은_하나만_생기고_둘_다_정상_응답한다() throws Exception {
        String eventId = newEventId();
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<AppendResult>> futures = new ArrayList<>();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 2; i++) {
                futures.add(executor.submit(() -> {
                    startLatch.await();
                    return sessionService.createSession(eventId);
                }));
            }
            startLatch.countDown();
        }

        AppendResult first = futures.get(0).get();
        AppendResult second = futures.get(1).get();
        assertThat(first.event().sessionId()).isEqualTo(second.event().sessionId());
        assertThat(List.of(first.duplicate(), second.duplicate())).containsExactlyInAnyOrder(true, false);
    }

    // ===== JOIN =====

    @Test
    void 같은_eventId로_JOIN을_재시도하면_같은_참여자를_반환한다() {
        Long sessionId = createSession();
        JoinCommand command = new JoinCommand(newEventId(), "철수", null);

        AppendResult first = sessionService.join(sessionId, command);
        // 재시도 요청에는 서버가 발급한 참여자 번호가 없지만 정상 재시도로 판단해야 한다
        AppendResult retry = sessionService.join(sessionId, command);

        assertThat(retry.duplicate()).isTrue();
        assertThat(retry.event().participantId()).isEqualTo(first.event().participantId());
        assertThat(participantRepository.findBySessionId(sessionId)).hasSize(1);
    }

    @Test
    void 동시에_3명이_JOIN하면_정확히_2명만_성공한다() throws Exception {
        Long sessionId = createSession();
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<AppendResult>> futures = new ArrayList<>();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 3; i++) {
                String displayName = "참여자" + i;
                futures.add(executor.submit(() -> {
                    startLatch.await();
                    return sessionService.join(sessionId, new JoinCommand(newEventId(), displayName, null));
                }));
            }
            startLatch.countDown();
        }

        int succeeded = 0;
        int full = 0;
        for (Future<AppendResult> future : futures) {
            try {
                future.get();
                succeeded++;
            } catch (ExecutionException e) {
                assertThat(e.getCause()).isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.SESSION_FULL);
                full++;
            }
        }
        assertThat(succeeded).isEqualTo(2);
        assertThat(full).isEqualTo(1);
    }

    @Test
    void 종료된_세션에는_JOIN할_수_없다() {
        Long sessionId = createSession();
        sessionService.endSession(sessionId, newEventId());

        assertThatThrownBy(() -> sessionService.join(sessionId, new JoinCommand(newEventId(), "철수", null)))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.SESSION_COMPLETED);
    }

    // ===== 메시지 수정/삭제 =====

    @Test
    void 본인_메시지는_수정할_수_있다() {
        Long sessionId = createSession();
        Long participantId = join(sessionId, "철수");
        String messageId = sendMessage(sessionId, participantId, "안녕");

        AppendResult result = sessionService.appendClientEvent(
            sessionId,
            command(participantId, EventType.MESSAGE_EDITED, messageId, content("안녕하세요"))
        );

        assertThat(result.event().eventType()).isEqualTo(EventType.MESSAGE_EDITED);
        assertThat(result.event().targetEventId()).isEqualTo(messageId);
    }

    @Test
    void 남의_메시지를_수정하면_NOT_MESSAGE_OWNER() {
        Long sessionId = createSession();
        Long chulsoo = join(sessionId, "철수");
        Long younghee = join(sessionId, "영희");
        String messageId = sendMessage(sessionId, chulsoo, "안녕");

        assertThatThrownBy(() -> sessionService.appendClientEvent(
            sessionId,
            command(younghee, EventType.MESSAGE_EDITED, messageId, content("바꿈"))
        ))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.NOT_MESSAGE_OWNER);
    }

    @Test
    void 삭제된_메시지를_수정하면_MESSAGE_ALREADY_DELETED() {
        Long sessionId = createSession();
        Long participantId = join(sessionId, "철수");
        String messageId = sendMessage(sessionId, participantId, "안녕");
        sessionService.appendClientEvent(
            sessionId,
            command(participantId, EventType.MESSAGE_DELETED, messageId, null)
        );

        assertThatThrownBy(() -> sessionService.appendClientEvent(
            sessionId,
            command(participantId, EventType.MESSAGE_EDITED, messageId, content("수정"))
        ))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.MESSAGE_ALREADY_DELETED);
    }

    @Test
    void 없는_메시지를_삭제하면_MESSAGE_NOT_FOUND() {
        Long sessionId = createSession();
        Long participantId = join(sessionId, "철수");

        assertThatThrownBy(() -> sessionService.appendClientEvent(
            sessionId,
            command(participantId, EventType.MESSAGE_DELETED, newEventId(), null)
        ))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.MESSAGE_NOT_FOUND);
    }

    // ===== 요청 검증 =====

    @Test
    void 서버만_만들_수_있는_이벤트_타입을_보내면_INVALID_REQUEST() {
        Long sessionId = createSession();
        Long participantId = join(sessionId, "철수");

        assertThatThrownBy(() -> sessionService.appendClientEvent(
            sessionId,
            command(participantId, EventType.DISCONNECT, null, null)
        ))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.INVALID_REQUEST);
    }

    @Test
    void 빈_메시지를_보내면_INVALID_REQUEST() {
        Long sessionId = createSession();
        Long participantId = join(sessionId, "철수");

        assertThatThrownBy(() -> sessionService.appendClientEvent(
            sessionId,
            command(participantId, EventType.MESSAGE, null, content("   "))
        ))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.INVALID_REQUEST);
    }

    // ===== LEAVE / 종료 =====

    @Test
    void 메시지_payload에_content_외의_키가_있으면_INVALID_REQUEST() {
        Long sessionId = createSession();
        Long participantId = join(sessionId, "철수");
        JsonNode payload = content("안녕").put("extra", "x".repeat(10_000));

        assertThatThrownBy(() -> sessionService.appendClientEvent(
            sessionId,
            command(participantId, EventType.MESSAGE, null, payload)
        ))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.INVALID_REQUEST);
    }

    @Test
    void LEAVE_payload가_비어_있지_않으면_INVALID_REQUEST() {
        Long sessionId = createSession();
        Long participantId = join(sessionId, "철수");

        assertThatThrownBy(() -> sessionService.appendClientEvent(
            sessionId,
            command(participantId, EventType.LEAVE, null, content("잘 있어"))
        ))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.INVALID_REQUEST);
    }

    @Test
    void 나간_참여자는_메시지를_보낼_수_없다() {
        Long sessionId = createSession();
        Long participantId = join(sessionId, "철수");
        sessionService.appendClientEvent(sessionId, command(participantId, EventType.LEAVE, null, null));

        assertThat(participantRepository.findById(participantId).orElseThrow().getStatus())
            .isEqualTo(ParticipantStatus.LEFT);
        assertThatThrownBy(() -> sessionService.appendClientEvent(
            sessionId,
            command(participantId, EventType.MESSAGE, null, content("안녕"))
        ))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.PARTICIPANT_NOT_ACTIVE);
    }

    @Test
    void 세션을_종료하면_남은_참여자는_LEFT가_되고_세션은_COMPLETED가_된다() {
        Long sessionId = createSession();
        join(sessionId, "철수");
        join(sessionId, "영희");

        AppendResult result = sessionService.endSession(sessionId, newEventId());

        assertThat(result.event().eventType()).isEqualTo(EventType.SESSION_ENDED);
        assertThat(sessionRepository.findById(sessionId).orElseThrow().getStatus())
            .isEqualTo(SessionStatus.COMPLETED);
        assertThat(participantRepository.findBySessionId(sessionId))
            .extracting(Participant::getStatus)
            .containsOnly(ParticipantStatus.LEFT);
    }

    @Test
    void 종료된_세션에_메시지를_보내면_SESSION_COMPLETED() {
        Long sessionId = createSession();
        Long participantId = join(sessionId, "철수");
        sessionService.endSession(sessionId, newEventId());

        // 종료 시 참여자도 LEFT가 되지만, 세션 종료 검증이 먼저라 SESSION_COMPLETED로 응답한다
        assertThatThrownBy(() -> sessionService.appendClientEvent(
            sessionId,
            command(participantId, EventType.MESSAGE, null, content("안녕"))
        ))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.SESSION_COMPLETED);
    }

    // ===== 장애 주입 =====

    /**
     * 다른 트랜잭션이 session 행 Lock을 오래 잡고 있는 상황 (Hot Session, 느린 트랜잭션).
     * 커넥션을 무한정 붙잡고 기다리지 않고 Lock 대기 타임아웃 후 SESSION_BUSY(503)로 빠르게 실패한다.
     */
    @Test
    void 장애_다른_트랜잭션이_Lock을_잡고_있으면_대기_타임아웃_후_SESSION_BUSY() throws Exception {
        Long sessionId = createSession();
        Long participantId = join(sessionId, "철수");
        double timeoutBefore = lockTimeoutCount();
        CountDownLatch lockAcquired = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<?> holder = executor.submit(() -> holdSessionLock(sessionId, lockAcquired, release));
            lockAcquired.await();

            assertThatThrownBy(() -> sendMessage(sessionId, participantId, "안녕"))
                .isInstanceOfSatisfying(
                    PessimisticLockingFailureException.class,
                    e -> assertThat(ErrorCode.from(e)).isEqualTo(ErrorCode.SESSION_BUSY)
                );
            assertThat(lockTimeoutCount()).isEqualTo(timeoutBefore + 1);

            release.countDown();
            holder.get();
        } finally {
            release.countDown();
            executor.shutdown();
        }
        // 실패한 요청은 저장되지 않았다 (1: 생성, 2: JOIN)
        assertThat(sessionRepository.findById(sessionId).orElseThrow().getLastSequence()).isEqualTo(2);
    }

    @Test
    void 장애_커넥션_풀이_전부_사용_중이면_획득_대기_타임아웃_후_SERVER_BUSY() throws Exception {
        Long sessionId = createSession();
        Long participantId = join(sessionId, "철수");
        int poolSize = dataSource.unwrap(HikariDataSource.class).getMaximumPoolSize();
        List<Connection> heldConnections = new ArrayList<>();

        try {
            for (int i = 0; i < poolSize; i++) {
                heldConnections.add(dataSource.getConnection());
            }

            assertThatThrownBy(() -> sendMessage(sessionId, participantId, "안녕"))
                .satisfies(e -> assertThat(ErrorCode.from((Exception) e)).isEqualTo(ErrorCode.SERVER_BUSY));
        } finally {
            for (Connection connection : heldConnections) {
                connection.close();
            }
        }
        // 커넥션을 돌려주면 다시 정상 처리된다
        sendMessage(sessionId, participantId, "다시 안녕");
        assertThat(sessionRepository.findById(sessionId).orElseThrow().getLastSequence()).isEqualTo(3);
    }

    private Long createSession() {
        return sessionService.createSession(newEventId()).event().sessionId();
    }

    private Long join(Long sessionId, String displayName) {
        return sessionService.join(sessionId, new JoinCommand(newEventId(), displayName, null))
            .event()
            .participantId();
    }

    private String sendMessage(
        Long sessionId,
        Long participantId,
        String text
    ) {
        return sessionService.appendClientEvent(sessionId, command(participantId, EventType.MESSAGE, null, content(text)))
            .event()
            .eventId();
    }

    private ClientEventCommand command(
        Long participantId,
        EventType eventType,
        String targetEventId,
        JsonNode payload
    ) {
        return new ClientEventCommand(
            newEventId(),
            participantId,
            eventType,
            targetEventId,
            payload,
            null
        );
    }

    private ObjectNode content(String text) {
        return jsonMapper.createObjectNode().put("content", text);
    }

    private String newEventId() {
        return UUID.randomUUID().toString();
    }

    // 트랜잭션을 열고 session 행을 FOR UPDATE로 잡은 채 release 신호가 올 때까지 놓지 않는다
    private void holdSessionLock(
        Long sessionId,
        CountDownLatch lockAcquired,
        CountDownLatch release
    ) {
        transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.queryForObject("SELECT id FROM session WHERE id = ? FOR UPDATE", Long.class, sessionId);
            lockAcquired.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    private double lockTimeoutCount() {
        return meterRegistry.counter("session.lock.timeout").count();
    }

}

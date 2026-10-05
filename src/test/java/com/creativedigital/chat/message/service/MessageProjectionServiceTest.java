package com.creativedigital.chat.message.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import com.creativedigital.chat.TestcontainersConfiguration;
import com.creativedigital.chat.common.config.AsyncConfig;
import com.creativedigital.chat.common.error.BusinessException;
import com.creativedigital.chat.common.error.ErrorCode;
import com.creativedigital.chat.event.entity.EventType;
import com.creativedigital.chat.message.dto.MessageListResponse;
import com.creativedigital.chat.message.dto.MessageListResponse.MessageSummary;
import com.creativedigital.chat.message.entity.MessageProjection;
import com.creativedigital.chat.message.entity.MessageStatus;
import com.creativedigital.chat.message.repository.MessageProjectionRepository;
import com.creativedigital.chat.session.dto.ClientEventCommand;
import com.creativedigital.chat.session.dto.JoinCommand;
import com.creativedigital.chat.session.service.SessionService;
import com.creativedigital.chat.timeline.dto.TimelineResponse;
import com.creativedigital.chat.timeline.service.TimelineService;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 안전망 스케줄러는 끄고 catchUpLaggingSessions를 직접 호출해 타이밍을 테스트가 통제한다.
 * Projection을 일부러 망가뜨리는 테스트는 먼저 주 경로 작업이 전부 끝나길 기다린다 (남은 작업이 테스트 도중 복구하지 않게).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
    "app.snapshot.scheduler.enabled=false",
    "app.message-projection.scheduler.enabled=false"
})
class MessageProjectionServiceTest {

    private static final Duration ASYNC_TIMEOUT = Duration.ofSeconds(10);

    @Autowired
    private MessageProjectionService messageProjectionService;

    @Autowired
    private MessageService messageService;

    @Autowired
    private SessionService sessionService;

    @Autowired
    private TimelineService timelineService;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    @Qualifier(AsyncConfig.MESSAGE_PROJECTION_EXECUTOR)
    private ThreadPoolTaskExecutor messageProjectionExecutor;

    @Autowired
    private MeterRegistry meterRegistry;

    // 평소엔 진짜 저장소로 동작하고, 장애 주입 테스트에서만 일부 메서드를 실패시킨다 (테스트가 끝나면 자동 리셋)
    @MockitoSpyBean
    private MessageProjectionRepository messageProjectionRepository;

    // ===== 주 경로: 커밋 후 비동기 반영 =====

    @Test
    void 전송_수정_삭제가_비동기로_반영된다() {
        ChatRoom room = createRoom();
        String first = send(room, "안녕");
        String second = send(room, "ㅎㅇ");
        edit(room, first, "안녕하세요");
        delete(room, second);

        awaitProjected(room.sessionId());

        assertThat(messages(room.sessionId()))
            .extracting(MessageSummary::messageId, MessageSummary::content, MessageSummary::status)
            .containsExactly(
                tuple(second, null, MessageStatus.DELETED),
                tuple(first, "안녕하세요", MessageStatus.EDITED)
            );
    }

    @Test
    void 메시지가_아닌_이벤트도_반영_위치를_전진시킨다() {
        ChatRoom room = createRoom();

        // 생성(1) + JOIN(2)만 있어도 projectedSequence가 2까지 따라온다
        awaitProjected(room.sessionId());

        MessageListResponse response = messageService.getMessages(room.sessionId(), Long.MAX_VALUE, 50);
        assertThat(response.projectedSequence()).isEqualTo(2);
        assertThat(response.messages()).isEmpty();
    }

    // ===== Determinism: 현재 상태 == timeline(마지막 sequence) =====

    @Test
    void 현재_메시지_목록은_마지막_sequence_시점_timeline과_같다() {
        ChatRoom room = createRoom();
        String first = send(room, "하나");
        String second = send(room, "둘");
        send(room, "셋");
        edit(room, first, "하나(수정)");
        delete(room, second);
        edit(room, first, "하나(재수정)");
        awaitProjected(room.sessionId());

        TimelineResponse timeline = timelineService.restore(room.sessionId(), null, lastSequence(room.sessionId()));

        // timeline은 오름차순, 메시지 API는 최신순이라 뒤집어서 비교한다
        List<MessageSummary> projected = messages(room.sessionId()).reversed();
        assertThat(projected)
            .extracting(
                MessageSummary::messageId,
                MessageSummary::senderParticipantId,
                MessageSummary::content,
                MessageSummary::status,
                MessageSummary::sequence
            )
            .containsExactlyElementsOf(timeline.messages().stream()
                .map(message -> tuple(
                    message.messageId(),
                    message.senderParticipantId(),
                    message.content(),
                    message.status(),
                    message.sequence()
                ))
                .toList());
    }

    // ===== 안전망 catch-up =====

    @Test
    void 반영_위치를_되돌리고_rows를_지우면_안전망이_원래대로_복구한다() {
        ChatRoom room = createRoom();
        String first = send(room, "안녕");
        send(room, "ㅎㅇ");
        edit(room, first, "안녕하세요");
        awaitProjectionIdle(room.sessionId());
        List<MessageSummary> before = messages(room.sessionId());

        breakProjection(room.sessionId());
        assertThat(messages(room.sessionId())).isEmpty();

        messageProjectionService.catchUpLaggingSessions();

        assertThat(messages(room.sessionId())).isEqualTo(before);
        assertThat(checkpoint(room.sessionId())).isEqualTo(lastSequence(room.sessionId()));
    }

    @Test
    void 최근에_갱신되지_않은_세션은_안전망이_확인하지_않는다() {
        ChatRoom room = createRoom();
        send(room, "안녕");
        awaitProjectionIdle(room.sessionId());
        breakProjection(room.sessionId());
        jdbcTemplate.update(
            "UPDATE session SET updated_at = NOW(6) - INTERVAL 2 HOUR WHERE id = ?",
            room.sessionId()
        );

        messageProjectionService.catchUpLaggingSessions();

        assertThat(messages(room.sessionId())).isEmpty();
    }

    // 서버가 lookback(1시간)보다 오래 꺼져 있던 경우: 주기 안전망은 못 찾지만 앱 시작 시 전체 확인이 복구한다
    @Test
    void 오래전에_갱신된_세션도_시작_시_전체_확인으로_복구한다() {
        ChatRoom room = createRoom();
        send(room, "안녕");
        awaitProjectionIdle(room.sessionId());
        List<MessageSummary> before = messages(room.sessionId());
        breakProjection(room.sessionId());
        jdbcTemplate.update(
            "UPDATE session SET updated_at = NOW(6) - INTERVAL 2 HOUR WHERE id = ?",
            room.sessionId()
        );

        messageProjectionService.catchUpAllLaggingSessions();

        assertThat(messages(room.sessionId())).isEqualTo(before);
        assertThat(checkpoint(room.sessionId())).isEqualTo(lastSequence(room.sessionId()));
    }

    @Test
    void catchUp을_동시에_여러_번_실행해도_한_번씩만_반영된다() throws Exception {
        ChatRoom room = createRoom();
        String first = send(room, "안녕");
        send(room, "ㅎㅇ");
        edit(room, first, "안녕하세요");
        awaitProjectionIdle(room.sessionId());
        List<MessageSummary> before = messages(room.sessionId());

        // checkpoint 행까지 지워서 첫 생성 경쟁(INSERT IGNORE)도 같이 확인한다
        jdbcTemplate.update("DELETE FROM message_projection WHERE session_id = ?", room.sessionId());
        jdbcTemplate.update("DELETE FROM message_projection_checkpoint WHERE session_id = ?", room.sessionId());

        int threadCount = 5;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(threadCount)) {
            for (int i = 0; i < threadCount; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return messageProjectionService.catchUp(room.sessionId());
                }));
            }
            start.countDown();

            int totalApplied = 0;
            for (Future<Integer> future : futures) {
                totalApplied += future.get();
            }
            // 이벤트 5개(생성, JOIN, 메시지 2, 수정)를 여러 작업이 나눠 가질 수는 있어도 합은 정확히 5
            assertThat(totalApplied).isEqualTo(5);
        }
        assertThat(messages(room.sessionId())).isEqualTo(before);
    }

    // ===== 조회 =====

    @Test
    void beforeSequence로_이전_페이지를_조회한다() {
        ChatRoom room = createRoom();
        for (int i = 1; i <= 5; i++) {
            send(room, "메시지" + i);
        }
        awaitProjected(room.sessionId());

        MessageListResponse firstPage = messageService.getMessages(room.sessionId(), Long.MAX_VALUE, 2);
        long lastSequenceOfFirstPage = firstPage.messages().getLast().sequence();
        MessageListResponse secondPage = messageService.getMessages(room.sessionId(), lastSequenceOfFirstPage, 10);

        assertThat(firstPage.messages()).extracting(MessageSummary::content).containsExactly("메시지5", "메시지4");
        assertThat(firstPage.hasMore()).isTrue();
        assertThat(firstPage.projectedSequence()).isEqualTo(7);
        assertThat(secondPage.messages()).extracting(MessageSummary::content)
            .containsExactly("메시지3", "메시지2", "메시지1");
        assertThat(secondPage.hasMore()).isFalse();
    }

    @Test
    void 없는_세션의_메시지를_조회하면_SESSION_NOT_FOUND() {
        assertThatThrownBy(() -> messageService.getMessages(Long.MAX_VALUE, Long.MAX_VALUE, 50))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.SESSION_NOT_FOUND);
    }

    // ===== 장애 주입 =====

    /**
     * 비동기 반영의 DB 쓰기가 계속 실패하는 상황 (부분 실패).
     * 이벤트는 이미 커밋돼 안전하고, 재시도를 다 써서 포기해도 안전망이 나중에 따라잡는다.
     */
    @Test
    void 장애_반영_중_DB_쓰기가_실패해도_이벤트는_저장되고_안전망이_복구한다() {
        ChatRoom room = createRoom();
        awaitProjectionIdle(room.sessionId());
        double failedBefore = failedCount();
        doThrow(new RuntimeException("주입된 장애")).when(messageProjectionRepository).save(any(MessageProjection.class));

        String messageId = send(room, "안녕");

        // 재시도 3번을 다 쓰고 포기할 때까지 기다린다
        await().atMost(ASYNC_TIMEOUT).until(() -> failedCount() == failedBefore + 1);
        assertThat(lastSequence(room.sessionId())).isEqualTo(3);
        assertThat(messages(room.sessionId())).isEmpty();
        assertThat(checkpoint(room.sessionId())).isEqualTo(2);

        reset(messageProjectionRepository);
        messageProjectionService.catchUpLaggingSessions();

        assertThat(messages(room.sessionId())).extracting(MessageSummary::messageId).containsExactly(messageId);
        assertThat(checkpoint(room.sessionId())).isEqualTo(3);
    }

    // sequence 1: 생성, 2: JOIN
    private ChatRoom createRoom() {
        Long sessionId = sessionService.createSession(newEventId()).event().sessionId();
        Long participantId = sessionService.join(sessionId, new JoinCommand(newEventId(), "철수", null))
            .event()
            .participantId();
        return new ChatRoom(sessionId, participantId);
    }

    // 반환값 = messageId (MESSAGE 이벤트의 eventId)
    private String send(ChatRoom room, String content) {
        return appendEvent(room, EventType.MESSAGE, null, contentPayload(content));
    }

    private void edit(ChatRoom room, String messageId, String content) {
        appendEvent(room, EventType.MESSAGE_EDITED, messageId, contentPayload(content));
    }

    private void delete(ChatRoom room, String messageId) {
        appendEvent(room, EventType.MESSAGE_DELETED, messageId, jsonMapper.createObjectNode());
    }

    private String appendEvent(
        ChatRoom room,
        EventType eventType,
        String targetEventId,
        JsonNode payload
    ) {
        String eventId = newEventId();
        sessionService.appendClientEvent(
            room.sessionId(),
            new ClientEventCommand(
                eventId,
                room.participantId(),
                eventType,
                targetEventId,
                payload,
                null
            ));
        return eventId;
    }

    private JsonNode contentPayload(String content) {
        return jsonMapper.createObjectNode().put("content", content);
    }

    private void awaitProjected(Long sessionId) {
        long lastSequence = lastSequence(sessionId);
        await().atMost(ASYNC_TIMEOUT).until(() -> checkpoint(sessionId) == lastSequence);
    }

    // 반영 완료 + 스레드 풀에 남은 작업도 없음
    private void awaitProjectionIdle(Long sessionId) {
        awaitProjected(sessionId);
        await().atMost(ASYNC_TIMEOUT).until(() -> messageProjectionExecutor.getActiveCount() == 0
            && messageProjectionExecutor.getThreadPoolExecutor().getQueue().isEmpty());
    }

    private void breakProjection(Long sessionId) {
        jdbcTemplate.update("DELETE FROM message_projection WHERE session_id = ?", sessionId);
        jdbcTemplate.update("UPDATE message_projection_checkpoint SET last_sequence = 0 WHERE session_id = ?", sessionId);
    }

    private List<MessageSummary> messages(Long sessionId) {
        return messageService.getMessages(sessionId, Long.MAX_VALUE, 100).messages();
    }

    private long checkpoint(Long sessionId) {
        List<Long> result = jdbcTemplate.queryForList(
            "SELECT last_sequence FROM message_projection_checkpoint WHERE session_id = ?",
            Long.class,
            sessionId
        );
        return result.isEmpty() ? 0 : result.getFirst();
    }

    private long lastSequence(Long sessionId) {
        return jdbcTemplate.queryForObject("SELECT last_sequence FROM session WHERE id = ?", Long.class, sessionId);
    }

    private String newEventId() {
        return UUID.randomUUID().toString();
    }

    private double failedCount() {
        return meterRegistry.counter("message.projection", "result", "failed").count();
    }

    private record ChatRoom(
        Long sessionId,
        Long participantId
    ) {

    }

}

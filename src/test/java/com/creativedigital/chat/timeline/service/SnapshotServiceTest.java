package com.creativedigital.chat.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.creativedigital.chat.TestcontainersConfiguration;
import com.creativedigital.chat.common.error.BusinessException;
import com.creativedigital.chat.common.error.ErrorCode;
import com.creativedigital.chat.event.entity.EventType;
import com.creativedigital.chat.event.service.EventService;
import com.creativedigital.chat.session.dto.ClientEventCommand;
import com.creativedigital.chat.session.dto.JoinCommand;
import com.creativedigital.chat.session.service.SessionService;
import com.creativedigital.chat.timeline.config.SnapshotProperties;
import com.creativedigital.chat.timeline.dto.SnapshotResult;
import com.creativedigital.chat.timeline.dto.TimelineResponse;
import com.creativedigital.chat.timeline.state.SessionState;
import com.creativedigital.chat.timeline.state.SessionStateReducer;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Snapshot 생성을 눈으로 확인할 수 있게 간격을 5로 줄인다.
 * 안전망 스케줄러는 끄고 fillMissingSnapshots를 직접 호출해 타이밍을 테스트가 통제한다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
    "app.snapshot.interval=5",
    "app.snapshot.scheduler.enabled=false"
})
class SnapshotServiceTest {

    private static final Duration ASYNC_TIMEOUT = Duration.ofSeconds(10);

    @Autowired
    private SnapshotService snapshotService;

    @Autowired
    private TimelineService timelineService;

    @Autowired
    private SessionService sessionService;

    @Autowired
    private EventService eventService;

    @Autowired
    private SnapshotProperties snapshotProperties;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ===== 주 경로: 커밋 후 비동기 생성 =====

    @Test
    void 간격의_배수_sequence마다_비동기로_생성된다() {
        Long sessionId = createSessionWithEvents(12);

        awaitSnapshots(sessionId, 5L, 10L);
    }

    @Test
    void 자동_생성된_Snapshot은_처음부터_Replay한_결과와_같다() {
        Long sessionId = createSessionWithEvents(10);
        awaitSnapshots(sessionId, 5L, 10L);

        SessionState saved = jsonMapper.readValue(snapshotState(sessionId, 10), SessionState.class);

        SessionStateReducer reducer = SessionStateReducer.empty(snapshotProperties.recentMessages());
        eventService.findEventsForReplay(sessionId, 0, 10, 500).forEach(reducer::apply);
        assertThat(saved).isEqualTo(reducer.toState());
    }

    @Test
    void Snapshot이_있으면_timeline은_그_Snapshot에서_이어서_복원한다() {
        Long sessionId = createSessionWithEvents(12);
        awaitSnapshots(sessionId, 5L, 10L);

        TimelineResponse restored = timelineService.restore(sessionId, null, 12L);

        assertThat(restored.restoredFrom().snapshotSequence()).isEqualTo(10);
        assertThat(restored.restoredFrom().replayedEventCount()).isEqualTo(2);
    }

    // ===== 멱등 =====

    @Test
    void 같은_Snapshot을_동시에_만들어도_하나만_남는다() throws Exception {
        // 7은 간격(5)의 배수가 아니라 자동 생성과 겹치지 않는다
        Long sessionId = createSessionWithEvents(7);
        int threadCount = 5;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<SnapshotResult>> futures = new ArrayList<>();

        try (ExecutorService executor = Executors.newFixedThreadPool(threadCount)) {
            for (int i = 0; i < threadCount; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return snapshotService.create(sessionId, 7);
                }));
            }
            start.countDown();

            List<SnapshotResult> results = new ArrayList<>();
            for (Future<SnapshotResult> future : futures) {
                results.add(future.get());
            }
            assertThat(results).extracting(SnapshotResult::sequence).containsOnly(7L);
            assertThat(results).filteredOn(SnapshotResult::created).hasSize(1);
        }
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM snapshot WHERE session_id = ? AND sequence = 7",
            Long.class,
            sessionId
        )).isEqualTo(1);
    }

    // ===== 안전망 =====

    @Test
    void 안전망은_중간_구멍과_끝부분을_모두_채운다() {
        Long sessionId = createSessionWithEvents(17);
        awaitSnapshots(sessionId, 5L, 10L, 15L);
        deleteSnapshots(sessionId, 5L, 15L);

        snapshotService.fillMissingSnapshots();

        assertThat(snapshotSequences(sessionId)).containsExactly(5L, 10L, 15L);
    }

    @Test
    void 최근에_갱신되지_않은_세션은_안전망이_확인하지_않는다() {
        Long sessionId = createSessionWithEvents(12);
        awaitSnapshots(sessionId, 5L, 10L);
        deleteSnapshots(sessionId, 5L, 10L);
        jdbcTemplate.update(
            "UPDATE session SET updated_at = NOW(6) - INTERVAL 2 HOUR WHERE id = ?",
            sessionId
        );

        snapshotService.fillMissingSnapshots();

        assertThat(snapshotSequences(sessionId)).isEmpty();
    }

    // ===== 수동 생성 =====

    @Test
    void 수동_생성은_현재_last_sequence_기준이고_다시_호출하면_기존_것을_반환한다() {
        Long sessionId = createSessionWithEvents(3);

        SnapshotResult first = snapshotService.createLatest(sessionId);
        SnapshotResult second = snapshotService.createLatest(sessionId);

        assertThat(first.created()).isTrue();
        assertThat(first.sequence()).isEqualTo(3);
        assertThat(second.created()).isFalse();
        assertThat(second.sequence()).isEqualTo(3);
        assertThat(snapshotSequences(sessionId)).containsExactly(3L);
    }

    @Test
    void 없는_세션을_수동_생성하면_SESSION_NOT_FOUND() {
        assertThatThrownBy(() -> snapshotService.createLatest(Long.MAX_VALUE))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.SESSION_NOT_FOUND);
    }

    // sequence 1: 생성, 2: JOIN, 3~: 메시지
    private Long createSessionWithEvents(int totalEvents) {
        Long sessionId = sessionService.createSession(newEventId()).event().sessionId();
        Long participantId = sessionService.join(sessionId, new JoinCommand(newEventId(), "철수", null))
            .event()
            .participantId();
        for (int sequence = 3; sequence <= totalEvents; sequence++) {
            sessionService.appendClientEvent(
                sessionId,
                new ClientEventCommand(
                    newEventId(),
                    participantId,
                    EventType.MESSAGE,
                    null,
                    jsonMapper.createObjectNode().put("content", "메시지" + sequence),
                    null
                ));
        }
        return sessionId;
    }

    private void awaitSnapshots(Long sessionId, Long... sequences) {
        await().atMost(ASYNC_TIMEOUT)
            .untilAsserted(() -> assertThat(snapshotSequences(sessionId)).containsExactly(sequences));
    }

    private List<Long> snapshotSequences(Long sessionId) {
        return jdbcTemplate.queryForList(
            "SELECT sequence FROM snapshot WHERE session_id = ? ORDER BY sequence",
            Long.class,
            sessionId
        );
    }

    private String snapshotState(Long sessionId, long sequence) {
        return jdbcTemplate.queryForObject(
            "SELECT state FROM snapshot WHERE session_id = ? AND sequence = ?",
            String.class,
            sessionId,
            sequence
        );
    }

    private void deleteSnapshots(Long sessionId, Long... sequences) {
        for (Long sequence : sequences) {
            jdbcTemplate.update("DELETE FROM snapshot WHERE session_id = ? AND sequence = ?", sessionId, sequence);
        }
    }

    private String newEventId() {
        return UUID.randomUUID().toString();
    }

}

package com.creativedigital.chat.timeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.creativedigital.chat.TestcontainersConfiguration;
import com.creativedigital.chat.common.error.BusinessException;
import com.creativedigital.chat.common.error.ErrorCode;
import com.creativedigital.chat.event.dto.EventResult;
import com.creativedigital.chat.event.entity.EventType;
import com.creativedigital.chat.event.service.EventService;
import com.creativedigital.chat.message.entity.MessageStatus;
import com.creativedigital.chat.session.dto.ClientEventCommand;
import com.creativedigital.chat.session.dto.JoinCommand;
import com.creativedigital.chat.session.dto.SessionDetailResponse;
import com.creativedigital.chat.session.entity.ParticipantStatus;
import com.creativedigital.chat.session.service.SessionService;
import com.creativedigital.chat.timeline.config.SnapshotProperties;
import com.creativedigital.chat.timeline.dto.TimelineResponse;
import com.creativedigital.chat.timeline.dto.TimelineResponse.MessageSummary;
import com.creativedigital.chat.timeline.dto.TimelineResponse.ParticipantSummary;
import com.creativedigital.chat.timeline.entity.Snapshot;
import com.creativedigital.chat.timeline.repository.SnapshotRepository;
import com.creativedigital.chat.timeline.state.SessionStateReducer;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class TimelineServiceTest {

    @Autowired
    private TimelineService timelineService;

    @Autowired
    private SessionService sessionService;

    @Autowired
    private EventService eventService;

    @Autowired
    private SnapshotRepository snapshotRepository;

    @Autowired
    private SnapshotProperties snapshotProperties;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ===== sequence / at 지정 =====

    @Test
    void sequence를_지정하면_그_이벤트까지_적용한_상태를_복원한다() {
        // 1: 생성, 2: 철수 JOIN, 3: 영희 JOIN, 4: 메시지, 5: 수정, 6: 철수 LEAVE
        Long sessionId = createSession();
        Long chulsoo = join(sessionId, "철수");
        join(sessionId, "영희");
        String messageId = send(sessionId, chulsoo, EventType.MESSAGE, null, "원문").eventId();
        send(sessionId, chulsoo, EventType.MESSAGE_EDITED, messageId, "수정본");
        send(sessionId, chulsoo, EventType.LEAVE, null, null);

        TimelineResponse beforeEdit = timelineService.restore(sessionId, null, 4L);
        TimelineResponse latest = timelineService.restore(sessionId, null, 6L);

        assertThat(beforeEdit.targetSequence()).isEqualTo(4);
        assertThat(beforeEdit.participants())
            .extracting(ParticipantSummary::displayName, ParticipantSummary::status)
            .containsExactly(
                tuple("철수", ParticipantStatus.ACTIVE),
                tuple("영희", ParticipantStatus.ACTIVE)
            );
        assertThat(beforeEdit.messages())
            .extracting(MessageSummary::messageId, MessageSummary::content, MessageSummary::status)
            .containsExactly(tuple(messageId, "원문", MessageStatus.SENT));
        assertThat(beforeEdit.restoredFrom().snapshotSequence()).isNull();
        assertThat(beforeEdit.restoredFrom().replayedEventCount()).isEqualTo(4);

        assertThat(latest.participants())
            .extracting(ParticipantSummary::displayName, ParticipantSummary::status)
            .containsExactly(
                tuple("철수", ParticipantStatus.LEFT),
                tuple("영희", ParticipantStatus.ACTIVE)
            );
        assertThat(latest.messages())
            .extracting(MessageSummary::content, MessageSummary::status)
            .containsExactly(tuple("수정본", MessageStatus.EDITED));
    }

    @Test
    void 시점을_주면_그_시각까지_저장된_마지막_sequence로_변환한다() {
        Long sessionId = createSession();
        Long chulsoo = join(sessionId, "철수");
        List<EventResult> messages = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            messages.add(send(sessionId, chulsoo, EventType.MESSAGE, null, "메시지" + i));
        }
        LocalDateTime at = messages.get(1).createdAt();
        long expected = messages.stream()
            .filter(event -> !event.createdAt().isAfter(at))
            .mapToLong(EventResult::sequence)
            .max()
            .orElseThrow();

        TimelineResponse restored = timelineService.restore(sessionId, at, null);

        assertThat(restored.targetSequence()).isEqualTo(expected);
    }

    @Test
    void 이벤트_사이_시점이면_직전_이벤트의_sequence로_변환한다() {
        Long sessionId = createSession();
        Long chulsoo = join(sessionId, "철수");
        List<EventResult> messages = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            messages.add(send(sessionId, chulsoo, EventType.MESSAGE, null, "메시지" + i));
        }
        LocalDateTime at = messages.get(1).createdAt().plusNanos(1000);
        long expected = messages.stream()
            .filter(event -> !event.createdAt().isAfter(at))
            .mapToLong(EventResult::sequence)
            .max()
            .orElseThrow();

        TimelineResponse restored = timelineService.restore(sessionId, at, null);

        assertThat(restored.targetSequence()).isEqualTo(expected);
    }

    @Test
    void 미래_시점이면_현재_상태를_복원한다() {
        Long sessionId = createSession();
        join(sessionId, "철수");

        TimelineResponse restored = timelineService.restore(sessionId, LocalDateTime.now().plusDays(1), null);

        assertThat(restored.targetSequence()).isEqualTo(2);
    }

    @Test
    void 세션_생성_이전_시점이면_INVALID_REQUEST() {
        Long sessionId = createSession();
        LocalDateTime beforeCreated = sessionService.getSession(sessionId).createdAt()
            .toLocalDateTime()
            .minusSeconds(1);

        assertInvalidRequest(() -> timelineService.restore(sessionId, beforeCreated, null));
    }

    @Test
    void at과_sequence가_둘_다_있거나_둘_다_없으면_INVALID_REQUEST() {
        Long sessionId = createSession();

        assertInvalidRequest(() -> timelineService.restore(sessionId, LocalDateTime.now(), 1L));
        assertInvalidRequest(() -> timelineService.restore(sessionId, null, null));
    }

    @Test
    void 마지막_sequence보다_큰_sequence는_INVALID_REQUEST() {
        Long sessionId = createSession();

        assertInvalidRequest(() -> timelineService.restore(sessionId, null, 2L));
    }

    @Test
    void 없는_세션이면_SESSION_NOT_FOUND() {
        assertThatThrownBy(() -> timelineService.restore(Long.MAX_VALUE, null, 1L))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.SESSION_NOT_FOUND);
    }

    // ===== Snapshot + Replay =====

    @Test
    void Snapshot에서_이어서_Replay한_결과는_처음부터_Replay한_결과와_같다() {
        Long sessionId = createConversation();
        long snapshotSequence = 5;
        long target = sessionService.getSession(sessionId).lastSequence();
        TimelineResponse fullReplay = timelineService.restore(sessionId, null, target);

        saveSnapshot(sessionId, snapshotSequence);
        TimelineResponse fromSnapshot = timelineService.restore(sessionId, null, target);

        assertThat(fromSnapshot.restoredFrom().snapshotSequence()).isEqualTo(snapshotSequence);
        assertThat(fromSnapshot.restoredFrom().replayedEventCount()).isEqualTo((int) (target - snapshotSequence));
        assertThat(fromSnapshot)
            .usingRecursiveComparison()
            .ignoringFields("restoredFrom")
            .isEqualTo(fullReplay);
    }

    @Test
    void 목표보다_뒤의_Snapshot은_쓰지_않는다() {
        Long sessionId = createConversation();
        saveSnapshot(sessionId, 6);

        TimelineResponse restored = timelineService.restore(sessionId, null, 4L);

        assertThat(restored.restoredFrom().snapshotSequence()).isNull();
        assertThat(restored.targetSequence()).isEqualTo(4);
    }

    @Test
    void 읽을_수_없는_Snapshot은_무시하고_처음부터_Replay한다() {
        Long sessionId = createConversation();
        long target = sessionService.getSession(sessionId).lastSequence();
        saveSnapshot(sessionId, 5);
        // JSON으로는 유효하지만 SessionState로 읽을 수 없는 값 (데이터 손상 상황)
        jdbcTemplate.update("UPDATE snapshot SET state = '\"broken\"' WHERE session_id = ?", sessionId);

        TimelineResponse restored = timelineService.restore(sessionId, null, target);

        assertThat(restored.restoredFrom().snapshotSequence()).isNull();
        assertThat(restored.restoredFrom().replayedEventCount()).isEqualTo((int) target);
    }

    // ===== 현재 상태(동기 Projection) == timeline(마지막 sequence) =====

    @Test
    void 현재_세션_상태와_마지막_sequence_복원_결과가_같다() {
        Long sessionId = createConversation();

        assertSameAsCurrent(sessionId);
    }

    @Test
    void 종료된_세션도_현재_상태와_복원_결과가_같다() {
        Long sessionId = createConversation();
        sessionService.endSession(sessionId, newEventId());

        assertSameAsCurrent(sessionId);
    }

    private void assertSameAsCurrent(Long sessionId) {
        SessionDetailResponse current = sessionService.getSession(sessionId);

        TimelineResponse restored = timelineService.restore(sessionId, null, current.lastSequence());

        assertThat(current.participants()).isNotEmpty();
        assertThat(restored.sessionStatus()).isEqualTo(current.status());
        assertThat(restored.participants())
            .extracting(ParticipantSummary::participantId, ParticipantSummary::displayName, ParticipantSummary::status)
            .containsExactlyInAnyOrderElementsOf(
                current.participants().stream()
                    .map(participant -> tuple(
                        participant.participantId(),
                        participant.displayName(),
                        participant.status()
                    ))
                    .toList());
    }

    // 1: 생성, 2·3: JOIN, 4~6: 메시지, 7: 수정, 8: 삭제, 9: 철수 LEAVE
    private Long createConversation() {
        Long sessionId = createSession();
        Long chulsoo = join(sessionId, "철수");
        Long younghee = join(sessionId, "영희");
        String first = send(sessionId, chulsoo, EventType.MESSAGE, null, "안녕").eventId();
        String second = send(sessionId, younghee, EventType.MESSAGE, null, "반가워").eventId();
        send(sessionId, chulsoo, EventType.MESSAGE, null, "잘 지냈어?");
        send(sessionId, chulsoo, EventType.MESSAGE_EDITED, first, "안녕하세요");
        send(sessionId, younghee, EventType.MESSAGE_DELETED, second, null);
        send(sessionId, chulsoo, EventType.LEAVE, null, null);
        return sessionId;
    }

    // 6단계 SnapshotService가 만들 Snapshot과 같은 방식으로 직접 저장한다
    private void saveSnapshot(Long sessionId, long sequence) {
        SessionStateReducer reducer = SessionStateReducer.empty(snapshotProperties.recentMessages());
        eventService.findEventsForReplay(sessionId, 0, sequence, 500).forEach(reducer::apply);
        snapshotRepository.save(
            Snapshot.create(
                sessionId,
                sequence,
                jsonMapper.writeValueAsString(reducer.toState()),
                LocalDateTime.now()
            ));
    }

    private void assertInvalidRequest(Runnable restore) {
        assertThatThrownBy(restore::run)
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.INVALID_REQUEST);
    }

    private Long createSession() {
        return sessionService.createSession(newEventId()).event().sessionId();
    }

    private Long join(Long sessionId, String displayName) {
        return sessionService.join(sessionId, new JoinCommand(newEventId(), displayName, null))
            .event()
            .participantId();
    }

    private EventResult send(
        Long sessionId,
        Long participantId,
        EventType eventType,
        String targetEventId,
        String content
    ) {
        JsonNode payload = content == null
            ? jsonMapper.createObjectNode()
            : jsonMapper.createObjectNode().put("content", content);
        return sessionService.appendClientEvent(
                sessionId,
                new ClientEventCommand(
                    newEventId(),
                    participantId,
                    eventType,
                    targetEventId,
                    payload,
                    null
                ))
            .event();
    }

    private String newEventId() {
        return UUID.randomUUID().toString();
    }

}

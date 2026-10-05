package com.creativedigital.chat.timeline.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.creativedigital.chat.event.dto.EventResult;
import com.creativedigital.chat.event.entity.EventType;
import com.creativedigital.chat.message.entity.MessageStatus;
import com.creativedigital.chat.session.entity.ParticipantStatus;
import com.creativedigital.chat.session.entity.SessionStatus;
import com.creativedigital.chat.timeline.state.SessionState.MessageState;
import com.creativedigital.chat.timeline.state.SessionState.ParticipantState;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Spring 없이 이벤트 적용 규칙만 검증한다.
 * 이벤트는 event(타입, 참여자, 대상 메시지 ID, payload) 하나로 만들어 테스트 본문에서 이벤트 모양이 그대로 보이게 한다.
 */
class SessionStateReducerTest {

    private static final long CHULSOO = 1L;
    private static final long YOUNGHEE = 2L;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    private long nextSequence = 1;

    @Test
    void 참여와_메시지가_순서대로_적용된다() {
        SessionStateReducer reducer = SessionStateReducer.empty(100);
        reducer.apply(event(EventType.SESSION_CREATED, null, null, null));
        reducer.apply(event(EventType.JOIN, CHULSOO, null, Map.of("displayName", "철수")));
        reducer.apply(event(EventType.JOIN, YOUNGHEE, null, Map.of("displayName", "영희")));
        EventResult message = event(EventType.MESSAGE, CHULSOO, null, Map.of("content", "안녕"));
        reducer.apply(message);

        SessionState state = reducer.toState();

        assertThat(state.sequence()).isEqualTo(4);
        assertThat(state.status()).isEqualTo(SessionStatus.IN_PROGRESS);
        assertThat(state.participants())
            .extracting(ParticipantState::participantId, ParticipantState::displayName, ParticipantState::status)
            .containsExactly(
                tuple(CHULSOO, "철수", ParticipantStatus.ACTIVE),
                tuple(YOUNGHEE, "영희", ParticipantStatus.ACTIVE)
            );
        assertThat(state.messages())
            .extracting(MessageState::messageId, MessageState::senderParticipantId, MessageState::content, MessageState::status, MessageState::sequence)
            .containsExactly(tuple(message.eventId(), CHULSOO, "안녕", MessageStatus.SENT, 4L));
    }

    @Test
    void 수정하면_EDITED_삭제하면_내용없이_DELETED() {
        SessionStateReducer reducer = startedWith(100, CHULSOO);
        EventResult edited = event(EventType.MESSAGE, CHULSOO, null, Map.of("content", "원문"));
        EventResult deleted = event(EventType.MESSAGE, CHULSOO, null, Map.of("content", "지울 메시지"));
        reducer.apply(edited);
        reducer.apply(deleted);

        reducer.apply(event(EventType.MESSAGE_EDITED, CHULSOO, edited.eventId(), Map.of("content", "수정본")));
        reducer.apply(event(EventType.MESSAGE_DELETED, CHULSOO, deleted.eventId(), Map.of()));

        assertThat(reducer.toState().messages())
            .extracting(MessageState::content, MessageState::status)
            .containsExactly(
                tuple("수정본", MessageStatus.EDITED),
                tuple(null, MessageStatus.DELETED)
            );
    }

    @Test
    void 삭제된_메시지는_이후_수정이_와도_DELETED로_유지된다() {
        SessionStateReducer reducer = startedWith(100, CHULSOO);
        EventResult message = event(EventType.MESSAGE, CHULSOO, null, Map.of("content", "원문"));
        reducer.apply(message);
        reducer.apply(event(EventType.MESSAGE_DELETED, CHULSOO, message.eventId(), Map.of()));

        reducer.apply(event(EventType.MESSAGE_EDITED, CHULSOO, message.eventId(), Map.of("content", "수정본")));

        assertThat(reducer.toState().messages())
            .extracting(MessageState::content, MessageState::status)
            .containsExactly(tuple(null, MessageStatus.DELETED));
    }

    @Test
    void 최근_N개를_넘으면_가장_오래된_메시지부터_빠진다() {
        SessionStateReducer reducer = startedWith(2, CHULSOO);
        reducer.apply(event(EventType.MESSAGE, CHULSOO, null, Map.of("content", "1")));
        reducer.apply(event(EventType.MESSAGE, CHULSOO, null, Map.of("content", "2")));
        reducer.apply(event(EventType.MESSAGE, CHULSOO, null, Map.of("content", "3")));

        assertThat(reducer.toState().messages())
            .extracting(MessageState::content)
            .containsExactly("2", "3");
    }

    @Test
    void 최근_N개에서_밀려난_메시지의_수정과_삭제는_무시한다() {
        SessionStateReducer reducer = startedWith(1, CHULSOO);
        EventResult old = event(EventType.MESSAGE, CHULSOO, null, Map.of("content", "오래된 메시지"));
        reducer.apply(old);
        reducer.apply(event(EventType.MESSAGE, CHULSOO, null, Map.of("content", "최근 메시지")));

        reducer.apply(event(EventType.MESSAGE_EDITED, CHULSOO, old.eventId(), Map.of("content", "수정본")));
        reducer.apply(event(EventType.MESSAGE_DELETED, CHULSOO, old.eventId(), Map.of()));

        SessionState state = reducer.toState();
        assertThat(state.sequence()).isEqualTo(6);
        assertThat(state.messages())
            .extracting(MessageState::content, MessageState::status)
            .containsExactly(tuple("최근 메시지", MessageStatus.SENT));
    }

    @Test
    void 전원이_끊기면_SUSPENDED_재접속하면_IN_PROGRESS() {
        SessionStateReducer reducer = startedWith(100, CHULSOO, YOUNGHEE);

        reducer.apply(event(EventType.DISCONNECT, CHULSOO, null, Map.of()));
        assertThat(reducer.toState().status()).isEqualTo(SessionStatus.IN_PROGRESS);

        reducer.apply(event(EventType.DISCONNECT, YOUNGHEE, null, Map.of()));
        assertThat(reducer.toState().status()).isEqualTo(SessionStatus.SUSPENDED);

        reducer.apply(event(EventType.RECONNECT, CHULSOO, null, Map.of()));
        assertThat(reducer.toState().status()).isEqualTo(SessionStatus.IN_PROGRESS);
        assertThat(reducer.toState().participants())
            .extracting(ParticipantState::status)
            .containsExactly(ParticipantStatus.ACTIVE, ParticipantStatus.DISCONNECTED);
    }

    @Test
    void 끊긴_참여자만_남고_다른_참여자가_나가면_SUSPENDED() {
        SessionStateReducer reducer = startedWith(100, CHULSOO, YOUNGHEE);
        reducer.apply(event(EventType.DISCONNECT, CHULSOO, null, Map.of()));

        reducer.apply(event(EventType.LEAVE, YOUNGHEE, null, Map.of()));

        assertThat(reducer.toState().status()).isEqualTo(SessionStatus.SUSPENDED);
    }

    @Test
    void 종료되면_COMPLETED_남은_참여자는_LEFT로_바뀐다() {
        SessionStateReducer reducer = startedWith(100, CHULSOO, YOUNGHEE);
        reducer.apply(event(EventType.DISCONNECT, YOUNGHEE, null, Map.of()));

        reducer.apply(event(EventType.SESSION_ENDED, null, null, null));

        SessionState state = reducer.toState();
        assertThat(state.status()).isEqualTo(SessionStatus.COMPLETED);
        assertThat(state.participants())
            .extracting(ParticipantState::status)
            .containsOnly(ParticipantStatus.LEFT);
    }

    @Test
    void Snapshot_상태에서_이어서_적용한_결과는_처음부터_적용한_결과와_같다() {
        List<EventResult> ordered = new ArrayList<>();
        ordered.add(event(EventType.SESSION_CREATED, null, null, null));
        ordered.add(event(EventType.JOIN, CHULSOO, null, Map.of("displayName", "철수")));
        EventResult first = event(EventType.MESSAGE, CHULSOO, null, Map.of("content", "1"));
        ordered.add(first);
        ordered.add(event(EventType.MESSAGE, CHULSOO, null, Map.of("content", "2")));
        ordered.add(event(EventType.MESSAGE_EDITED, CHULSOO, first.eventId(), Map.of("content", "1 수정")));
        ordered.add(event(EventType.MESSAGE, CHULSOO, null, Map.of("content", "3")));
        ordered.add(event(EventType.DISCONNECT, CHULSOO, null, Map.of()));

        SessionStateReducer full = SessionStateReducer.empty(2);
        ordered.forEach(full::apply);

        SessionStateReducer head = SessionStateReducer.empty(2);
        ordered.subList(0, 4).forEach(head::apply);
        SessionStateReducer resumed = SessionStateReducer.from(head.toState(), 2);
        ordered.subList(4, ordered.size()).forEach(resumed::apply);

        assertThat(resumed.toState()).isEqualTo(full.toState());
    }

    @Test
    void sequence가_이어지지_않으면_예외() {
        SessionStateReducer reducer = SessionStateReducer.empty(100);
        reducer.apply(event(EventType.SESSION_CREATED, null, null, null));
        nextSequence++;

        assertThatThrownBy(() -> reducer.apply(event(EventType.JOIN, CHULSOO, null, Map.of("displayName", "철수"))))
            .isInstanceOf(IllegalStateException.class);
    }

    // 세션 생성 + 참여자 JOIN까지 적용한 Reducer (이름은 "참여자{번호}")
    private SessionStateReducer startedWith(int recentMessages, long... participantIds) {
        SessionStateReducer reducer = SessionStateReducer.empty(recentMessages);
        reducer.apply(event(EventType.SESSION_CREATED, null, null, null));
        for (long participantId : participantIds) {
            reducer.apply(event(EventType.JOIN, participantId, null, Map.of("displayName", "참여자" + participantId)));
        }
        return reducer;
    }

    // sequence는 만든 순서대로 1부터 발급한다. payload는 Map으로 받아 JSON으로 바꾼다 (null이면 payload 없음)
    private EventResult event(
        EventType eventType,
        Long participantId,
        String targetEventId,
        Map<String, String> payload
    ) {
        LocalDateTime now = LocalDateTime.now();
        return new EventResult(
            UUID.randomUUID().toString(),
            1L,
            nextSequence++,
            eventType,
            participantId,
            targetEventId,
            payload == null ? null : jsonMapper.valueToTree(payload),
            now,
            now
        );
    }

}

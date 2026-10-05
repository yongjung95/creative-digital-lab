package com.creativedigital.chat.timeline.state;

import com.creativedigital.chat.event.dto.EventResult;
import com.creativedigital.chat.message.entity.MessageStatus;
import com.creativedigital.chat.session.entity.ParticipantStatus;
import com.creativedigital.chat.session.entity.SessionStatus;
import com.creativedigital.chat.timeline.state.SessionState.MessageState;
import com.creativedigital.chat.timeline.state.SessionState.ParticipantState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;

/**
 * 이벤트를 sequence 순서대로 적용해 세션 상태를 계산한다. Spring과 DB에 의존하지 않는 순수 계산 로직이다.
 * timeline 복원, Snapshot 생성, message_projection이 이 규칙을 공유하므로 같은 이벤트면 항상 같은 결과가 나온다.
 * 상태 전이 규칙은 SessionStatus.calculate, MessageStatus.next를 그대로 사용해 동기 Projection과 결과가 같다.
 * 적용 중에는 내부 상태를 바꾸는 객체라 한 번의 복원에서만 사용하고 공유하지 않는다.
 */
public class SessionStateReducer {

    private final int recentMessages;
    private final Map<Long, ParticipantState> participants = new LinkedHashMap<>();
    private final SequencedMap<String, MessageState> messages = new LinkedHashMap<>();
    private long sequence;
    private SessionStatus status;

    private SessionStateReducer(
        SessionState initial,
        int recentMessages
    ) {
        this.recentMessages = recentMessages;
        this.sequence = initial.sequence();
        this.status = initial.status();
        initial.participants().forEach(participant -> participants.put(participant.participantId(), participant));
        initial.messages().forEach(message -> messages.put(message.messageId(), message));
        trimMessages();
    }

    // 처음부터 Replay할 때 (sequence 0)
    public static SessionStateReducer empty(int recentMessages) {
        return new SessionStateReducer(
            new SessionState(0, SessionStatus.IN_PROGRESS, List.of(), List.of()),
            recentMessages
        );
    }

    // Snapshot 상태에서 이어서 Replay할 때
    public static SessionStateReducer from(
        SessionState snapshotState,
        int recentMessages
    ) {
        return new SessionStateReducer(snapshotState, recentMessages);
    }

    /**
     * 이벤트 하나를 적용한다. sequence가 이어지지 않으면 결과를 믿을 수 없으므로 예외를 던진다.
     */
    public void apply(EventResult event) {
        validateNextSequence(event.sequence());

        switch (event.eventType()) {
            case SESSION_CREATED -> status = SessionStatus.IN_PROGRESS;
            case JOIN -> join(event);
            case LEAVE -> changeParticipantStatus(event.participantId(), ParticipantStatus.LEFT);
            case DISCONNECT -> changeParticipantStatus(event.participantId(), ParticipantStatus.DISCONNECTED);
            case RECONNECT -> changeParticipantStatus(event.participantId(), ParticipantStatus.ACTIVE);
            case SESSION_ENDED -> end();
            case MESSAGE -> addMessage(event);
            case MESSAGE_EDITED -> messages.computeIfPresent(
                event.targetEventId(),
                (messageId, message) -> message.edited(content(event))
            );
            case MESSAGE_DELETED -> messages.computeIfPresent(
                event.targetEventId(),
                (messageId, message) -> message.deleted()
            );
        }
        sequence = event.sequence();
    }

    public SessionState toState() {
        return new SessionState(
            sequence,
            status,
            new ArrayList<>(participants.values()),
            new ArrayList<>(messages.values())
        );
    }

    private void validateNextSequence(long eventSequence) {
        if (eventSequence == sequence + 1) return;
        throw new IllegalStateException(
            "이벤트 sequence가 이어지지 않습니다. expected=" + (sequence + 1) + " actual=" + eventSequence);
    }

    private void join(EventResult event) {
        participants.put(
            event.participantId(),
            new ParticipantState(
                event.participantId(),
                event.payload().path("displayName").asString(),
                ParticipantStatus.ACTIVE
            ));
        recalculateStatus();
    }

    private void changeParticipantStatus(
        Long participantId,
        ParticipantStatus newStatus
    ) {
        participants.computeIfPresent(participantId, (id, participant) -> participant.withStatus(newStatus));
        recalculateStatus();
    }

    // SESSION_ENDED 하나가 남은 참여자의 퇴장까지 의미한다 (SessionService.endSession과 같은 규칙)
    private void end() {
        participants.replaceAll((id, participant) -> participant.status().occupiesSeat()
            ? participant.withStatus(ParticipantStatus.LEFT)
            : participant);
        status = SessionStatus.COMPLETED;
    }

    private void recalculateStatus() {
        if (status == SessionStatus.COMPLETED) return;
        status = SessionStatus.calculate(participants.values().stream().map(ParticipantState::status).toList());
    }

    private void addMessage(EventResult event) {
        messages.put(
            event.eventId(),
            new MessageState(
                event.eventId(),
                event.participantId(),
                content(event),
                MessageStatus.SENT,
                event.sequence()
            ));
        trimMessages();
    }

    // 최근 N개만 유지한다. 여기서 밀려난 메시지를 수정/삭제하는 이벤트는 대상이 맵에 없어서 computeIfPresent가 아무것도 하지 않는다
    private void trimMessages() {
        while (messages.size() > recentMessages) {
            messages.pollFirstEntry();
        }
    }

    private String content(EventResult event) {
        return event.payload().path("content").asString();
    }

}

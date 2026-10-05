package com.creativedigital.chat.timeline.dto;

import com.creativedigital.chat.message.entity.MessageStatus;
import com.creativedigital.chat.session.entity.ParticipantStatus;
import com.creativedigital.chat.session.entity.SessionStatus;
import com.creativedigital.chat.timeline.state.SessionState.MessageState;
import com.creativedigital.chat.timeline.state.SessionState.ParticipantState;
import com.creativedigital.chat.timeline.state.SessionState;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 특정 시점의 세션 상태. Snapshot 저장 형태(SessionState)와 API 응답을 분리하기 위해 별도 record로 변환한다.
 * restoredFrom으로 어떤 Snapshot에서 몇 개 이벤트를 Replay했는지 알려준다.
 */
public record TimelineResponse(
    Long sessionId,
    long targetSequence,
    SessionStatus sessionStatus,
    List<ParticipantSummary> participants,
    List<MessageSummary> messages,
    RestoredFrom restoredFrom
) {

    public static TimelineResponse of(
        Long sessionId,
        SessionState state,
        Long snapshotSequence,
        int replayedEventCount
    ) {
        return new TimelineResponse(
            sessionId,
            state.sequence(),
            state.status(),
            state.participants().stream().map(ParticipantSummary::from).toList(),
            state.messages().stream().map(MessageSummary::from).toList(),
            new RestoredFrom(snapshotSequence, replayedEventCount)
        );
    }

    public record ParticipantSummary(
        Long participantId,
        String displayName,
        ParticipantStatus status
    ) {

        private static ParticipantSummary from(ParticipantState participant) {
            return new ParticipantSummary(
                participant.participantId(),
                participant.displayName(),
                participant.status()
            );
        }

    }

    public record MessageSummary(
        String messageId,
        Long senderParticipantId,
        String content,
        MessageStatus status,
        long sequence
    ) {

        private static MessageSummary from(MessageState message) {
            return new MessageSummary(
                message.messageId(),
                message.senderParticipantId(),
                message.content(),
                message.status(),
                message.sequence()
            );
        }

    }

    // Snapshot 없이 처음부터 Replay했으면 snapshotSequence는 null
    public record RestoredFrom(
        @Schema(description = "사용한 Snapshot의 sequence. null이면 Snapshot 없이 처음부터 Replay") Long snapshotSequence,
        @Schema(description = "Snapshot 이후 적용한 이벤트 수") int replayedEventCount
    ) {

    }

}

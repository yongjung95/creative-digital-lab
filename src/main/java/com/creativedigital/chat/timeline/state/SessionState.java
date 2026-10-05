package com.creativedigital.chat.timeline.state;

import com.creativedigital.chat.event.entity.EventType;
import com.creativedigital.chat.message.entity.MessageStatus;
import com.creativedigital.chat.session.entity.ParticipantStatus;
import com.creativedigital.chat.session.entity.SessionStatus;
import java.util.List;

/**
 * sequence번 이벤트까지 적용한 세션 상태 (불변).
 * timeline 복원 결과이자 Snapshot의 state JSON 저장 형태다.
 * 참여자는 JOIN 순서, 메시지는 sequence 오름차순이며 최근 N개만 담는다.
 */
public record SessionState(
    long sequence,
    SessionStatus status,
    List<ParticipantState> participants,
    List<MessageState> messages
) {

    public SessionState {
        participants = List.copyOf(participants);
        messages = List.copyOf(messages);
    }

    public record ParticipantState(
        Long participantId,
        String displayName,
        ParticipantStatus status
    ) {

        public ParticipantState withStatus(ParticipantStatus newStatus) {
            return new ParticipantState(
                participantId,
                displayName,
                newStatus
            );
        }

    }

    // messageId = 원본 MESSAGE 이벤트의 eventId. 삭제된 메시지는 content를 남기지 않는다 (message_projection과 동일)
    public record MessageState(
        String messageId,
        Long senderParticipantId,
        String content,
        MessageStatus status,
        long sequence
    ) {

        public MessageState edited(String newContent) {
            MessageStatus nextStatus = status.next(EventType.MESSAGE_EDITED);
            return new MessageState(
                messageId,
                senderParticipantId,
                nextStatus == MessageStatus.DELETED ? null : newContent,
                nextStatus,
                sequence
            );
        }

        public MessageState deleted() {
            return new MessageState(
                messageId,
                senderParticipantId,
                null,
                status.next(EventType.MESSAGE_DELETED),
                sequence
            );
        }

    }

}

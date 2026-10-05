package com.creativedigital.chat.session.dto;

import com.creativedigital.chat.event.dto.EventResult;
import com.creativedigital.chat.session.entity.ParticipantStatus;

/**
 * 재시도 시에도 처음과 같은 body를 반환하도록 JOIN 이벤트 기준으로 만든다.
 */
public record JoinSessionResponse(
    Long participantId,
    Long sessionId,
    long sequence,
    ParticipantStatus status
) {

    public static JoinSessionResponse from(EventResult event) {
        return new JoinSessionResponse(
            event.participantId(),
            event.sessionId(),
            event.sequence(),
            ParticipantStatus.ACTIVE
        );
    }

}

package com.creativedigital.chat.session.dto;

import com.creativedigital.chat.common.time.KstTime;
import com.creativedigital.chat.event.dto.EventResult;
import com.creativedigital.chat.session.entity.SessionStatus;
import java.time.OffsetDateTime;

/**
 * 재시도 시에도 처음과 같은 body를 반환하도록 SESSION_CREATED 이벤트 기준으로 만든다.
 */
public record CreateSessionResponse(
    Long sessionId,
    SessionStatus status,
    long sequence,
    OffsetDateTime createdAt
) {

    public static CreateSessionResponse from(EventResult event) {
        return new CreateSessionResponse(
            event.sessionId(),
            SessionStatus.IN_PROGRESS,
            event.sequence(),
            KstTime.toOffset(event.createdAt())
        );
    }

}

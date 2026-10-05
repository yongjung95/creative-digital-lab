package com.creativedigital.chat.session.dto;

import com.creativedigital.chat.event.entity.EventType;
import java.time.LocalDateTime;
import tools.jackson.databind.JsonNode;

/**
 * 클라이언트 이벤트(MESSAGE, MESSAGE_EDITED, MESSAGE_DELETED, LEAVE) 커맨드.
 * REST와 WS 입구가 모두 이 형태로 바꿔서 SessionService에 넘긴다.
 */
public record ClientEventCommand(
    String eventId,
    Long participantId,
    EventType eventType,
    String targetEventId,
    JsonNode payload,
    LocalDateTime occurredAt
) {

}

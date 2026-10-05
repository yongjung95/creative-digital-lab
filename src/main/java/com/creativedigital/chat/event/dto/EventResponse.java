package com.creativedigital.chat.event.dto;

import com.creativedigital.chat.common.time.KstTime;
import com.creativedigital.chat.event.entity.EventType;
import java.time.OffsetDateTime;
import tools.jackson.databind.JsonNode;

/**
 * 이벤트 응답. POST /sessions/{id}/events, GET /sessions/{id}/events, WS EVENT 메시지가 같은 형태를 쓴다.
 */
public record EventResponse(
    String eventId,
    Long sessionId,
    long sequence,
    EventType type,
    Long participantId,
    String targetEventId,
    JsonNode payload,
    OffsetDateTime occurredAt,
    OffsetDateTime createdAt
) {

    public static EventResponse from(EventResult event) {
        return new EventResponse(
            event.eventId(),
            event.sessionId(),
            event.sequence(),
            event.eventType(),
            event.participantId(),
            event.targetEventId(),
            event.payload(),
            KstTime.toOffset(event.occurredAt()),
            KstTime.toOffset(event.createdAt())
        );
    }

}

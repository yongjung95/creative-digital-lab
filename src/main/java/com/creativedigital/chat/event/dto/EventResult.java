package com.creativedigital.chat.event.dto;

import com.creativedigital.chat.event.entity.Event;
import com.creativedigital.chat.event.entity.EventType;
import java.time.LocalDateTime;
import tools.jackson.databind.JsonNode;

/**
 * 서비스 밖으로 전달하는 이벤트 정보. 엔티티는 서비스 계층 밖으로 내보내지 않는다.
 * REST 응답과 WS ACK/EVENT 메시지가 같이 사용한다.
 */
public record EventResult(
    String eventId,
    Long sessionId,
    long sequence,
    EventType eventType,
    Long participantId,
    String targetEventId,
    JsonNode payload,
    LocalDateTime occurredAt,
    LocalDateTime createdAt
) {

    public static EventResult of(Event event, JsonNode payload) {
        return new EventResult(
            event.getEventId(),
            event.getSessionId(),
            event.getSequence(),
            event.getEventType(),
            event.getParticipantId(),
            event.getTargetEventId(),
            payload,
            event.getOccurredAt(),
            event.getCreatedAt()
        );
    }

}

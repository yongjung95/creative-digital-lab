package com.creativedigital.chat.event.dto;

import com.creativedigital.chat.event.entity.EventType;
import java.time.LocalDateTime;
import tools.jackson.databind.JsonNode;

/**
 * 저장할 이벤트의 요청 내용.
 * sequence와 createdAt은 서버가 Lock 안에서 정하므로 포함하지 않는다.
 * 같은 eventId 재요청 시 이 값들로 중복 여부를 비교한다.
 */
public record NewEvent(
    String eventId,
    Long sessionId,
    EventType eventType,
    Long participantId,
    String targetEventId,
    JsonNode payload,
    LocalDateTime occurredAt
) {

    // 세션 생성 시 서버가 발급한 세션 번호를 채운다
    public NewEvent withSessionId(Long sessionId) {
        return new NewEvent(eventId, sessionId, eventType, participantId, targetEventId, payload, occurredAt);
    }

    // JOIN 시 서버가 발급한 참여자 번호를 채운다
    public NewEvent withParticipantId(Long participantId) {
        return new NewEvent(eventId, sessionId, eventType, participantId, targetEventId, payload, occurredAt);
    }

}

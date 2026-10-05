package com.creativedigital.chat.realtime.dto;

import com.creativedigital.chat.common.time.KstTime;
import com.creativedigital.chat.event.entity.EventType;
import com.creativedigital.chat.session.dto.ClientEventCommand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;
import org.hibernate.validator.constraints.UUID;
import tools.jackson.databind.JsonNode;

/**
 * 클라이언트 → 서버 SEND_EVENT. REST POST /sessions/{id}/events와 같은 검증 규칙을 쓴다.
 * participantId는 받지 않는다. 연결할 때 검증한 참여자로만 보낼 수 있게 해서 남의 이름으로 보내지 못하게 한다.
 */
public record SendEventMessage(
    @NotBlank String type,
    @NotBlank @UUID String eventId,
    @NotNull EventType eventType,
    @UUID String targetEventId,
    JsonNode payload,
    OffsetDateTime occurredAt
) {

    public static final String TYPE = "SEND_EVENT";

    public boolean isSendEvent() {
        return TYPE.equals(type);
    }

    public ClientEventCommand toCommand(Long participantId) {
        return new ClientEventCommand(
            eventId,
            participantId,
            eventType,
            targetEventId,
            payload,
            KstTime.toKst(occurredAt)
        );
    }

}

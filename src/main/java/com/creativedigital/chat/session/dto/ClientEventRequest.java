package com.creativedigital.chat.session.dto;

import com.creativedigital.chat.common.time.KstTime;
import com.creativedigital.chat.event.entity.EventType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;
import org.hibernate.validator.constraints.UUID;
import tools.jackson.databind.JsonNode;

public record ClientEventRequest(
    @Schema(description = "클라이언트가 만든 요청 ID (소문자 UUID). 재시도할 때도 같은 값을 보낸다", example = "3f6c2a8e-5b1d-4c7a-9e2f-8d4b6a1c0e57") @NotBlank @UUID String eventId,
    @Schema(description = "보내는 참여자 (JOIN에서 발급)", example = "1") @NotNull Long participantId,
    @Schema(
        description = "이벤트 종류. DISCONNECT/RECONNECT는 서버만 만든다",
        allowableValues = {"MESSAGE", "MESSAGE_EDITED", "MESSAGE_DELETED", "LEAVE"}
    ) @NotNull EventType type,
    @Schema(description = "수정/삭제할 메시지 ID (MESSAGE 이벤트의 eventId). 수정/삭제에만 필요") @UUID String targetEventId,
    @Schema(
        description = "MESSAGE/MESSAGE_EDITED는 {\"content\": \"...\"} (최대 2000자), MESSAGE_DELETED/LEAVE는 {}",
        example = "{\"content\": \"안녕\"}"
    ) JsonNode payload,
    @Schema(description = "클라이언트 시각 (선택). 보존만 하고 순서 기준으로는 쓰지 않는다", example = "2026-10-04T21:00:00+09:00") OffsetDateTime occurredAt
) {

    public ClientEventCommand toCommand() {
        return new ClientEventCommand(
            eventId,
            participantId,
            type,
            targetEventId,
            payload,
            KstTime.toKst(occurredAt)
        );
    }

}

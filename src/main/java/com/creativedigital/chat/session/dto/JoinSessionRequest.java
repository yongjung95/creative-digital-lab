package com.creativedigital.chat.session.dto;

import com.creativedigital.chat.common.time.KstTime;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import org.hibernate.validator.constraints.UUID;

public record JoinSessionRequest(
    @Schema(description = "클라이언트가 만든 요청 ID (소문자 UUID). 재시도할 때도 같은 값을 보낸다", example = "3f6c2a8e-5b1d-4c7a-9e2f-8d4b6a1c0e57") @NotBlank @UUID String eventId,
    @Schema(description = "표시 이름 (최대 50자)", example = "철수") @NotBlank @Size(max = 50) String displayName,
    @Schema(description = "클라이언트 시각 (선택). 보존만 하고 순서 기준으로는 쓰지 않는다", example = "2026-10-04T21:00:00+09:00") OffsetDateTime occurredAt
) {

    public JoinCommand toCommand() {
        return new JoinCommand(
            eventId,
            displayName,
            KstTime.toKst(occurredAt)
        );
    }

}

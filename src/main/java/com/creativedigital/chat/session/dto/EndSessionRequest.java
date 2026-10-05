package com.creativedigital.chat.session.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import org.hibernate.validator.constraints.UUID;

public record EndSessionRequest(
    @Schema(description = "클라이언트가 만든 요청 ID (소문자 UUID). 재시도할 때도 같은 값을 보낸다", example = "3f6c2a8e-5b1d-4c7a-9e2f-8d4b6a1c0e57") @NotBlank @UUID String eventId
) {

}

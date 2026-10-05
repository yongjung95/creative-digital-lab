package com.creativedigital.chat.timeline.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import java.time.OffsetDateTime;
import org.springframework.format.annotation.DateTimeFormat;

/**
 * GET /sessions/{id}/timeline 쿼리 파라미터. at(시점)과 sequence 중 하나만 받는다.
 * 둘 다 오거나 둘 다 없는 경우는 TimelineService에서 400으로 처리한다.
 */
public record TimelineRequest(
    @Schema(description = "이 시각 기준 (시간대 포함 ISO-8601). sequence와 함께 쓸 수 없다", example = "2026-10-04T21:00:00+09:00")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime at,
    @Schema(description = "이 sequence 기준. at과 함께 쓸 수 없다") @Min(1) Long sequence
) {

}

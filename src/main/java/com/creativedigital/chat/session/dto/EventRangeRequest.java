package com.creativedigital.chat.session.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * GET /sessions/{id}/events 쿼리 파라미터. fromSequence 초과 ~ toSequence 이하.
 * 시점 기준 조회는 timeline API가 담당하므로 sequence 기준만 지원한다.
 */
public record EventRangeRequest(
    @Schema(description = "이 번호 초과부터 (기본 0)") @Min(0) Long fromSequence,
    @Schema(description = "이 번호 이하까지 (기본 끝까지)") @Min(0) Long toSequence,
    @Schema(description = "최대 개수 (기본 100, 최대 500)") @Min(1) @Max(500) Integer limit
) {

    private static final int DEFAULT_LIMIT = 100;

    public long fromSequenceOrDefault() {
        return fromSequence != null ? fromSequence : 0;
    }

    public long toSequenceOrDefault() {
        return toSequence != null ? toSequence : Long.MAX_VALUE;
    }

    public int limitOrDefault() {
        return limit != null ? limit : DEFAULT_LIMIT;
    }

}

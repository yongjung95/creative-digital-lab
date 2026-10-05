package com.creativedigital.chat.message.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * GET /sessions/{id}/messages 쿼리 파라미터. beforeSequence 미만을 최신순으로 size개 조회한다.
 * beforeSequence가 없으면 가장 최근 메시지부터 조회한다.
 */
public record MessageListRequest(
    @Schema(description = "이 번호 미만 (없으면 가장 최근부터)") @Min(1) Long beforeSequence,
    @Schema(description = "개수 (기본 50, 최대 100)") @Min(1) @Max(100) Integer size
) {

    private static final int DEFAULT_SIZE = 50;

    public long beforeSequenceOrDefault() {
        return beforeSequence != null ? beforeSequence : Long.MAX_VALUE;
    }

    public int sizeOrDefault() {
        return size != null ? size : DEFAULT_SIZE;
    }

}

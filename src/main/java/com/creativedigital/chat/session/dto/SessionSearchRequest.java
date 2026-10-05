package com.creativedigital.chat.session.dto;

import com.creativedigital.chat.common.time.KstTime;
import com.creativedigital.chat.session.entity.SessionStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.OffsetDateTime;
import org.springframework.format.annotation.DateTimeFormat;

/**
 * GET /sessions 쿼리 파라미터. from/to는 세션 생성 시각 범위(from 이상, to 미만).
 */
public record SessionSearchRequest(
    @Schema(description = "세션 상태") SessionStatus status,
    @Schema(description = "세션 생성 시각 이상 (시간대 포함 ISO-8601)", example = "2026-10-04T00:00:00+09:00")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
    @Schema(description = "세션 생성 시각 미만 (시간대 포함 ISO-8601)", example = "2026-10-05T00:00:00+09:00")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
    @Schema(description = "참여자 이름 (정확히 일치)", example = "철수") String participantName,
    @Schema(description = "직전 응답의 nextCursor") Long cursor,
    @Schema(description = "페이지 크기 (기본 20, 최대 100)") @Min(1) @Max(100) Integer size
) {

    private static final int DEFAULT_SIZE = 20;

    public SessionSearchCondition toCondition() {
        return new SessionSearchCondition(
            status,
            KstTime.toKst(from),
            KstTime.toKst(to),
            participantName,
            cursor,
            size != null ? size : DEFAULT_SIZE
        );
    }

}

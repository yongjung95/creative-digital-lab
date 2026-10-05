package com.creativedigital.chat.timeline.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Snapshot 간격과 복원 결과에 담을 최근 메시지 수. 서로 독립된 설정값이다.
 */
@Validated
@ConfigurationProperties(prefix = "app.snapshot")
public record SnapshotProperties(
    @Min(1) int interval,
    @Min(1) int recentMessages,
    @Valid @NotNull Scheduler scheduler
) {

    /**
     * 빠진 Snapshot을 채우는 안전망 스케줄러.
     * lookback 안에 갱신된 세션만 확인한다 (구멍은 이벤트 저장 직후에만 생기므로).
     */
    public record Scheduler(
        boolean enabled,
        @NotNull Duration fixedDelay,
        @NotNull Duration lookback
    ) {

    }

}

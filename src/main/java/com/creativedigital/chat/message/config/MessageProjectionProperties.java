package com.creativedigital.chat.message.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * message_projection 안전망 스케줄러 설정.
 */
@Validated
@ConfigurationProperties(prefix = "app.message-projection")
public record MessageProjectionProperties(
    @Valid @NotNull Scheduler scheduler
) {

    /**
     * 반영이 밀린 세션을 따라잡는 안전망 스케줄러.
     * lookback 안에 갱신된 세션만 확인한다 (밀림은 이벤트 저장 직후에만 생기므로).
     */
    public record Scheduler(
        boolean enabled,
        @NotNull Duration fixedDelay,
        @NotNull Duration lookback
    ) {

    }

}

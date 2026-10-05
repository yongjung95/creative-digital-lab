package com.creativedigital.chat.message.service;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 안전망 스케줄러. 반영 담당이 아니라, 비동기 반영이 놓친 이벤트를 따라잡는 복구 담당이다.
 * 실제 로직은 MessageProjectionService에 두고 여기서는 주기만 정한다 (테스트는 스케줄러를 끄고 직접 호출).
 * 여러 인스턴스가 동시에 실행해도 checkpoint Lock 때문에 중복 반영이 없다. 중복 실행을 줄이려면 ShedLock 등 분산 락을 둔다.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.message-projection.scheduler.enabled", havingValue = "true")
public class MessageProjectionScheduler {

    private final MessageProjectionService messageProjectionService;

    @Scheduled(
        fixedDelayString = "${app.message-projection.scheduler.fixed-delay}",
        initialDelayString = "${app.message-projection.scheduler.fixed-delay}"
    )
    public void catchUpLaggingSessions() {
        messageProjectionService.catchUpLaggingSessions();
    }

    // 서버가 오래 꺼져 있던 동안 유실된 작업은 최근 갱신 세션만 보는 주기 실행이 찾지 못하므로, 시작할 때 한 번 전체를 확인한다
    @EventListener(ApplicationReadyEvent.class)
    public void catchUpAllLaggingSessionsOnStartup() {
        messageProjectionService.catchUpAllLaggingSessions();
    }

}

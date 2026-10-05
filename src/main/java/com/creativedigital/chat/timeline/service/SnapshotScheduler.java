package com.creativedigital.chat.timeline.service;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 안전망 스케줄러. Snapshot 생성 담당이 아니라, 비동기 생성이 놓친 Snapshot을 채우는 복구 담당이다.
 * 실제 로직은 SnapshotService에 두고 여기서는 주기만 정한다 (테스트는 스케줄러를 끄고 서비스 메서드를 직접 호출).
 * 운영에서 여러 인스턴스가 동시에 실행해도 생성이 멱등이라 결과는 같다. 중복 실행을 줄이려면 ShedLock 등 분산 락을 둔다.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.snapshot.scheduler.enabled", havingValue = "true")
public class SnapshotScheduler {

    private final SnapshotService snapshotService;

    @Scheduled(
        fixedDelayString = "${app.snapshot.scheduler.fixed-delay}",
        initialDelayString = "${app.snapshot.scheduler.fixed-delay}"
    )
    public void fillMissingSnapshots() {
        snapshotService.fillMissingSnapshots();
    }

}

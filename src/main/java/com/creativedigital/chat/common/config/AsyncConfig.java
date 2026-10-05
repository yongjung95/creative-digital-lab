package com.creativedigital.chat.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 비동기 작업용 전용 스레드 풀 + 스케줄링.
 * 스레드와 큐 크기를 제한해 비동기 작업이 DB 커넥션을 점유해 이벤트 저장(본업)을 느리게 만들지 않게 한다.
 * 큐가 차면 TaskRejectedException이 나고, 호출한 쪽이 로그/메트릭을 남긴 뒤 버린다. 버린 작업은 안전망 스케줄러가 복구한다.
 */
@Configuration
@EnableScheduling
public class AsyncConfig {

    public static final String SNAPSHOT_EXECUTOR = "snapshotExecutor";
    public static final String MESSAGE_PROJECTION_EXECUTOR = "messageProjectionExecutor";

    private static final int CORE_POOL_SIZE = 2;
    private static final int MAX_POOL_SIZE = 4;
    private static final int QUEUE_CAPACITY = 100;
    private static final int SHUTDOWN_AWAIT_SECONDS = 10;

    @Bean(name = SNAPSHOT_EXECUTOR)
    public ThreadPoolTaskExecutor snapshotExecutor() {
        return boundedExecutor("snapshot-");
    }

    // Snapshot 풀과 분리: Snapshot 작업이 몰려도 메시지 반영이 같이 밀리지 않게 한다
    @Bean(name = MESSAGE_PROJECTION_EXECUTOR)
    public ThreadPoolTaskExecutor messageProjectionExecutor() {
        return boundedExecutor("message-projection-");
    }

    private ThreadPoolTaskExecutor boundedExecutor(String threadNamePrefix) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(CORE_POOL_SIZE);
        executor.setMaxPoolSize(MAX_POOL_SIZE);
        executor.setQueueCapacity(QUEUE_CAPACITY);
        executor.setThreadNamePrefix(threadNamePrefix);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(SHUTDOWN_AWAIT_SECONDS);
        return executor;
    }

}

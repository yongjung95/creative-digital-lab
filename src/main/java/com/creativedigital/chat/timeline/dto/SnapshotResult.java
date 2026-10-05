package com.creativedigital.chat.timeline.dto;

import com.creativedigital.chat.timeline.entity.Snapshot;
import java.time.LocalDateTime;

/**
 * Snapshot 생성 결과. 이미 있던 Snapshot이면 created = false (멱등).
 */
public record SnapshotResult(
    Long sessionId,
    long sequence,
    LocalDateTime createdAt,
    boolean created
) {

    public static SnapshotResult created(Snapshot snapshot) {
        return of(snapshot, true);
    }

    public static SnapshotResult existing(Snapshot snapshot) {
        return of(snapshot, false);
    }

    private static SnapshotResult of(Snapshot snapshot, boolean created) {
        return new SnapshotResult(
            snapshot.getSessionId(),
            snapshot.getSequence(),
            snapshot.getCreatedAt(),
            created
        );
    }

}

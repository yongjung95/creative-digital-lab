package com.creativedigital.chat.timeline.dto;

import com.creativedigital.chat.common.time.KstTime;
import java.time.OffsetDateTime;

/**
 * POST /sessions/{id}/snapshots 응답.
 */
public record SnapshotResponse(
    Long sessionId,
    long sequence,
    OffsetDateTime createdAt
) {

    public static SnapshotResponse from(SnapshotResult result) {
        return new SnapshotResponse(
            result.sessionId(),
            result.sequence(),
            KstTime.toOffset(result.createdAt())
        );
    }

}

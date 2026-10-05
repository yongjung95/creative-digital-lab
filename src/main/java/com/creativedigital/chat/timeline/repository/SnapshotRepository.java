package com.creativedigital.chat.timeline.repository;

import com.creativedigital.chat.timeline.entity.Snapshot;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SnapshotRepository extends JpaRepository<Snapshot, Long> {

    // 목표 sequence 이하에서 가장 가까운 Snapshot (Replay 시작점)
    Optional<Snapshot> findFirstBySessionIdAndSequenceLessThanEqualOrderBySequenceDesc(
        Long sessionId,
        long sequence
    );

    // 같은 sequence Snapshot이 이미 있는지 (멱등 생성)
    Optional<Snapshot> findBySessionIdAndSequence(
        Long sessionId,
        long sequence
    );

    // 세션에 이미 있는 간격의 배수 Snapshot의 sequence (빠진 Snapshot 계산용. 수동 생성한 임의 sequence는 제외)
    @Query("""
        select s.sequence from Snapshot s
        where s.sessionId = :sessionId
          and mod(s.sequence, :interval) = 0
        """)
    List<Long> findBoundarySequences(
        @Param("sessionId") Long sessionId,
        @Param("interval") long interval
    );

    /**
     * 최근 갱신된 세션 중 간격의 배수 Snapshot이 빠진 세션 (안전망 스케줄러).
     * 있어야 할 개수(last_sequence / interval)보다 실제 간격의 배수 Snapshot 개수가 적으면 중간 구멍까지 포함해 빠진 것이 있다.
     * session은 idx_session_updated, snapshot은 uk_snapshot_session_sequence를 탄다.
     */
    @Query(
        value = """
            SELECT s.id FROM session s
            LEFT JOIN snapshot sn
              ON sn.session_id = s.id
             AND MOD(sn.sequence, :interval) = 0
            WHERE s.updated_at >= :since
            GROUP BY s.id, s.last_sequence
            HAVING COUNT(sn.id) < FLOOR(s.last_sequence / :interval)
            """,
        nativeQuery = true
    )
    List<Long> findSessionIdsMissingSnapshots(
        @Param("since") LocalDateTime since,
        @Param("interval") long interval
    );

}

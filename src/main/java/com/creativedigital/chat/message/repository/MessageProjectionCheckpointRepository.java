package com.creativedigital.chat.message.repository;

import com.creativedigital.chat.message.entity.MessageProjectionCheckpoint;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MessageProjectionCheckpointRepository extends JpaRepository<MessageProjectionCheckpoint, Long> {

    // 동시에 여러 작업이 만들려고 해도 하나만 생기고 나머지는 에러 없이 넘어간다
    @Modifying
    @Query(
        value = """
            INSERT IGNORE INTO message_projection_checkpoint (session_id, last_sequence, updated_at)
            VALUES (:sessionId, 0, NOW(6))
            """,
        nativeQuery = true
    )
    void insertIfAbsent(@Param("sessionId") Long sessionId);

    // 반영 작업을 세션당 한 번에 하나만 실행하기 위한 Lock (중복 반영 방지)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from MessageProjectionCheckpoint c where c.sessionId = :sessionId")
    Optional<MessageProjectionCheckpoint> findForUpdate(@Param("sessionId") Long sessionId);

    /**
     * 최근 갱신된 세션 중 반영 위치가 마지막 sequence보다 뒤처진 세션 (안전망 스케줄러).
     * checkpoint 행이 아직 없는 세션은 반영 위치 0으로 본다.
     * session은 idx_session_updated, checkpoint는 PK를 탄다.
     */
    @Query(
        value = """
            SELECT s.id FROM session s
            LEFT JOIN message_projection_checkpoint c ON c.session_id = s.id
            WHERE s.updated_at >= :since
              AND COALESCE(c.last_sequence, 0) < s.last_sequence
            """,
        nativeQuery = true
    )
    List<Long> findLaggingSessionIds(@Param("since") LocalDateTime since);

    /**
     * 기간 제한 없이 반영 위치가 뒤처진 모든 세션 (앱 시작 시 한 번).
     * 서버가 오래 꺼져 있던 동안 유실된 작업은 최근 갱신 세션만 보는 안전망이 찾지 못하므로, 시작할 때 전체를 한 번 확인한다.
     */
    @Query(
        value = """
            SELECT s.id FROM session s
            LEFT JOIN message_projection_checkpoint c ON c.session_id = s.id
            WHERE COALESCE(c.last_sequence, 0) < s.last_sequence
            """,
        nativeQuery = true
    )
    List<Long> findAllLaggingSessionIds();

}

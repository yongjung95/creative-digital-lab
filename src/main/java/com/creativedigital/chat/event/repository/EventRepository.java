package com.creativedigital.chat.event.repository;

import com.creativedigital.chat.event.entity.Event;
import com.creativedigital.chat.event.entity.EventType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EventRepository extends JpaRepository<Event, Long> {

    // 중복 eventId 확인
    Optional<Event> findByEventId(String eventId);

    // Replay 범위 조회: fromSequence 초과 ~ toSequence 이하, sequence 오름차순
    @Query("""
        select e from Event e
        where e.sessionId = :sessionId
          and e.sequence > :fromSequence
          and e.sequence <= :toSequence
        order by e.sequence
        """)
    List<Event> findRange(
        @Param("sessionId") Long sessionId,
        @Param("fromSequence") long fromSequence,
        @Param("toSequence") long toSequence,
        Limit limit
    );

    /**
     * timeline?at= → 그 시각까지 저장된 마지막 이벤트의 sequence.
     * 같은 세션에서는 sequence가 크면 저장 시각도 크거나 같으므로, MAX(sequence)로 범위 전체를 읽는 대신
     * (session_id, created_at, sequence) 인덱스에서 직전 1건만 읽는다 (docs/query.md).
     */
    @Query("""
        select e.sequence from Event e
        where e.sessionId = :sessionId
          and e.createdAt <= :at
        order by e.createdAt desc, e.sequence desc
        """)
    Optional<Long> findLastSequenceAtOrBefore(
        @Param("sessionId") Long sessionId,
        @Param("at") LocalDateTime at,
        Limit limit
    );

    // 대상 메시지가 이미 삭제됐는지 확인 (idx_event_target 사용)
    boolean existsByTargetEventIdAndEventType(String targetEventId, EventType eventType);

}

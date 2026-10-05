package com.creativedigital.chat.session.repository;

import com.creativedigital.chat.session.dto.SessionSearchCondition;
import com.creativedigital.chat.session.entity.Participant;
import com.creativedigital.chat.session.entity.Session;
import com.creativedigital.chat.session.entity.SessionStatus;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.time.LocalDateTime;
import org.springframework.data.jpa.domain.Specification;

/**
 * GET /sessions 동적 필터. 조건이 비어 있으면 null을 반환해 해당 조건을 건너뛴다.
 */
public final class SessionSpecs {

    private SessionSpecs() {
    }

    public static Specification<Session> matches(SessionSearchCondition condition) {
        return Specification.allOf(
            statusEquals(condition.status()),
            createdFrom(condition.from()),
            createdTo(condition.to()),
            hasParticipant(condition.participantName()),
            idBefore(condition.cursor())
        );
    }

    private static Specification<Session> statusEquals(SessionStatus status) {
        return (root, query, cb) -> status == null ? null : cb.equal(root.get("status"), status);
    }

    private static Specification<Session> createdFrom(LocalDateTime from) {
        return (root, query, cb) -> from == null ? null : cb.greaterThanOrEqualTo(root.get("createdAt"), from);
    }

    private static Specification<Session> createdTo(LocalDateTime to) {
        return (root, query, cb) -> to == null ? null : cb.lessThan(root.get("createdAt"), to);
    }

    // 참여자 이름 정확히 일치 (idx_participant_display_name 사용)
    private static Specification<Session> hasParticipant(String participantName) {
        return (root, query, cb) -> {
            if (participantName == null) return null;

            Subquery<Long> subquery = query.subquery(Long.class);
            Root<Participant> participant = subquery.from(Participant.class);
            subquery.select(participant.get("id"))
                .where(
                    cb.equal(participant.get("sessionId"), root.get("id")),
                    cb.equal(participant.get("displayName"), participantName)
                );
            return cb.exists(subquery);
        };
    }

    // 커서 페이징: 직전 페이지 마지막 sessionId보다 작은 것
    private static Specification<Session> idBefore(Long cursor) {
        return (root, query, cb) -> cursor == null ? null : cb.lessThan(root.get("id"), cursor);
    }

}

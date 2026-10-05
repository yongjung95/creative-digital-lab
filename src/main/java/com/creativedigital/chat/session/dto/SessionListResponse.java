package com.creativedigital.chat.session.dto;

import com.creativedigital.chat.common.time.KstTime;
import com.creativedigital.chat.session.entity.Session;
import com.creativedigital.chat.session.entity.SessionStatus;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 세션 목록. nextCursor가 null이면 마지막 페이지.
 */
public record SessionListResponse(
    List<SessionSummary> sessions,
    Long nextCursor
) {

    public static SessionListResponse of(
        List<Session> sessions,
        Map<Long, List<String>> participantNames,
        Long nextCursor
    ) {
        List<SessionSummary> summaries = sessions.stream()
            .map(session -> SessionSummary.of(session, participantNames.getOrDefault(session.getId(), List.of())))
            .toList();
        return new SessionListResponse(summaries, nextCursor);
    }

    public record SessionSummary(
        Long sessionId,
        SessionStatus status,
        List<String> participantNames,
        OffsetDateTime createdAt,
        OffsetDateTime endedAt
    ) {

        private static SessionSummary of(Session session, List<String> participantNames) {
            return new SessionSummary(
                session.getId(),
                session.getStatus(),
                participantNames,
                KstTime.toOffset(session.getCreatedAt()),
                KstTime.toOffset(session.getEndedAt())
            );
        }

    }

}

package com.creativedigital.chat.session.dto;

import com.creativedigital.chat.common.time.KstTime;
import com.creativedigital.chat.session.entity.Participant;
import com.creativedigital.chat.session.entity.ParticipantStatus;
import com.creativedigital.chat.session.entity.Session;
import com.creativedigital.chat.session.entity.SessionStatus;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 현재 세션 상태 + 참여자/presence (동기 Projection 조회).
 */
public record SessionDetailResponse(
    Long sessionId,
    SessionStatus status,
    long lastSequence,
    OffsetDateTime createdAt,
    OffsetDateTime endedAt,
    List<ParticipantSummary> participants
) {

    public static SessionDetailResponse of(Session session, List<Participant> participants) {
        return new SessionDetailResponse(
            session.getId(),
            session.getStatus(),
            session.getLastSequence(),
            KstTime.toOffset(session.getCreatedAt()),
            KstTime.toOffset(session.getEndedAt()),
            participants.stream().map(ParticipantSummary::from).toList()
        );
    }

    public record ParticipantSummary(
        Long participantId,
        String displayName,
        ParticipantStatus status,
        OffsetDateTime joinedAt
    ) {

        private static ParticipantSummary from(Participant participant) {
            return new ParticipantSummary(
                participant.getId(),
                participant.getDisplayName(),
                participant.getStatus(),
                KstTime.toOffset(participant.getJoinedAt())
            );
        }

    }

}

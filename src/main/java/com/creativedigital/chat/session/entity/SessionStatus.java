package com.creativedigital.chat.session.entity;

import java.util.Collection;
import java.util.List;

public enum SessionStatus {

    IN_PROGRESS,
    SUSPENDED,
    COMPLETED;

    /**
     * 참여자 상태로부터 세션 상태를 계산한다. 동기 Projection과 Replay가 같은 규칙을 공유한다.
     * COMPLETED는 종료 이벤트로만 전이되므로 여기서 계산하지 않는다.
     */
    public static SessionStatus calculate(Collection<ParticipantStatus> participantStatuses) {
        List<ParticipantStatus> remaining = participantStatuses.stream()
            .filter(ParticipantStatus::occupiesSeat)
            .toList();
        if (remaining.isEmpty()) return IN_PROGRESS;

        boolean allDisconnected = remaining.stream()
            .allMatch(status -> status == ParticipantStatus.DISCONNECTED);
        return allDisconnected ? SUSPENDED : IN_PROGRESS;
    }

}

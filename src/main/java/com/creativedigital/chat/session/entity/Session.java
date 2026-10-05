package com.creativedigital.chat.session.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.Collection;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 세션의 현재 상태와 세션별 sequence 발급을 담당한다. 이벤트 저장 시 이 행에 Lock을 건다. 시각은 KST 기준이며, Lock 안에서 조회한 DB
 * 시각(NOW(6))을 사용한다.
 */
@Entity
@Table(name = "session")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(access = AccessLevel.PRIVATE)
public class Session {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "varchar(20)")
    private SessionStatus status;

    @Column(nullable = false)
    private long lastSequence;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    private LocalDateTime endedAt;

    public static Session create(LocalDateTime now) {
        return Session.builder()
            .status(SessionStatus.IN_PROGRESS)
            .lastSequence(0)
            .createdAt(now)
            .updatedAt(now)
            .build();
    }

    public long issueNextSequence(LocalDateTime now) {
        lastSequence++;
        updatedAt = now;
        return lastSequence;
    }

    public void recalculateStatus(Collection<ParticipantStatus> participantStatuses) {
        if (isCompleted()) return;
        status = SessionStatus.calculate(participantStatuses);
    }

    public void end(LocalDateTime now) {
        status = SessionStatus.COMPLETED;
        endedAt = now;
        updatedAt = now;
    }

    public boolean isCompleted() {
        return status == SessionStatus.COMPLETED;
    }

}

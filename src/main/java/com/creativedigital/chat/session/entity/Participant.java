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
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 세션 참여자. 이벤트로부터 파생되는 동기 Projection이며 Command 검증에 사용한다. 모든 값은 이벤트로부터 재생성 가능해야 한다.
 */
@Entity
@Table(name = "participant")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(access = AccessLevel.PRIVATE)
public class Participant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long sessionId;

    @Column(nullable = false, length = 50)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "varchar(20)")
    private ParticipantStatus status;

    @Column(nullable = false)
    private LocalDateTime joinedAt;

    private LocalDateTime leftAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    public static Participant join(
        Long sessionId,
        String displayName,
        LocalDateTime now
    ) {
        return Participant.builder()
            .sessionId(sessionId)
            .displayName(displayName)
            .status(ParticipantStatus.ACTIVE)
            .joinedAt(now)
            .updatedAt(now)
            .build();
    }

    public void disconnect(LocalDateTime now) {
        changeStatus(ParticipantStatus.DISCONNECTED, now);
    }

    public void reconnect(LocalDateTime now) {
        changeStatus(ParticipantStatus.ACTIVE, now);
    }

    public void leave(LocalDateTime now) {
        changeStatus(ParticipantStatus.LEFT, now);
        leftAt = now;
    }

    private void changeStatus(ParticipantStatus status, LocalDateTime now) {
        this.status = status;
        this.updatedAt = now;
    }

    public boolean isActive() {
        return status == ParticipantStatus.ACTIVE;
    }

}

package com.creativedigital.chat.message.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 세션별 message_projection 반영 위치. session 행과 분리해 비동기 작업과 이벤트 저장의 Lock 경합을 피한다.
 * 행 생성은 Repository의 INSERT IGNORE로만 한다 (동시 생성 시 하나만 생기게).
 */
@Entity
@Table(name = "message_projection_checkpoint")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MessageProjectionCheckpoint {

    @Id
    private Long sessionId;

    @Column(nullable = false)
    private long lastSequence;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    public void advanceTo(long sequence, LocalDateTime now) {
        this.lastSequence = sequence;
        this.updatedAt = now;
    }

}

package com.creativedigital.chat.timeline.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * sequence번 이벤트까지 적용한 세션 상태. 과거 시점 복원 시 Replay 시작점으로 쓴다.
 */
@Entity
@Table(name = "snapshot")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(access = AccessLevel.PRIVATE)
public class Snapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long sessionId;

    @Column(nullable = false)
    private long sequence;

    @Column(nullable = false, columnDefinition = "json")
    private String state;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    public static Snapshot create(
        Long sessionId,
        long sequence,
        String state,
        LocalDateTime now
    ) {
        return Snapshot.builder()
            .sessionId(sessionId)
            .sequence(sequence)
            .state(state)
            .createdAt(now)
            .build();
    }

}

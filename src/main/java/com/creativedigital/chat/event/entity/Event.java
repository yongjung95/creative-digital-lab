package com.creativedigital.chat.event.entity;

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
import org.hibernate.annotations.Immutable;

/**
 * 세션에서 발생한 모든 사건의 불변 이력 (Source of Truth). append-only이므로 수정하지 않는다. 다른 도메인을 참조하지 않도록 연관관계 없이 ID로만
 * 참조한다.
 */
@Entity
@Table(name = "event")
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(access = AccessLevel.PRIVATE)
public class Event {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 클라이언트가 생성한 UUID 멱등성 키. MESSAGE 이벤트에서는 메시지 ID로도 쓰인다
    @Column(nullable = false, columnDefinition = "char(36)")
    private String eventId;

    @Column(nullable = false)
    private Long sessionId;

    @Column(nullable = false)
    private long sequence;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "varchar(30)")
    private EventType eventType;

    private Long participantId;

    // 수정/삭제 대상 메시지의 eventId
    @Column(columnDefinition = "char(36)")
    private String targetEventId;

    // 클라이언트가 보낸 내용만 저장한다
    @Column(columnDefinition = "json")
    private String payload;

    @Column(nullable = false)
    private LocalDateTime occurredAt;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    public static Event create(
        String eventId,
        Long sessionId,
        long sequence,
        EventType eventType,
        Long participantId,
        String targetEventId,
        String payload,
        LocalDateTime occurredAt,
        LocalDateTime createdAt
    ) {
        return Event.builder()
            .eventId(eventId)
            .sessionId(sessionId)
            .sequence(sequence)
            .eventType(eventType)
            .participantId(participantId)
            .targetEventId(targetEventId)
            .payload(payload)
            .occurredAt(occurredAt)
            .createdAt(createdAt)
            .build();
    }

}

package com.creativedigital.chat.message.entity;

import com.creativedigital.chat.event.entity.EventType;
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
 * 수정/삭제가 반영된 현재 메시지 1건. 이벤트 커밋 후 비동기로 갱신되는 조회 전용 Projection.
 */
@Entity
@Table(name = "message_projection")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(access = AccessLevel.PRIVATE)
public class MessageProjection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 원본 MESSAGE 이벤트의 eventId
    @Column(nullable = false, columnDefinition = "char(36)")
    private String messageId;

    @Column(nullable = false)
    private Long sessionId;

    // 원본 MESSAGE 이벤트의 sequence (정렬/페이징 기준)
    @Column(nullable = false)
    private long sequence;

    @Column(nullable = false)
    private Long senderParticipantId;

    @Column(columnDefinition = "text")
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "varchar(20)")
    private MessageStatus status;

    @Column(nullable = false)
    private LocalDateTime sentAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    public static MessageProjection sent(
        String messageId,
        Long sessionId,
        long sequence,
        Long senderParticipantId,
        String content,
        LocalDateTime sentAt
    ) {
        return MessageProjection.builder()
            .messageId(messageId)
            .sessionId(sessionId)
            .sequence(sequence)
            .senderParticipantId(senderParticipantId)
            .content(content)
            .status(MessageStatus.SENT)
            .sentAt(sentAt)
            .updatedAt(sentAt)
            .build();
    }

    public void edit(String content, LocalDateTime now) {
        if (status == MessageStatus.DELETED) return;
        this.content = content;
        this.status = status.next(EventType.MESSAGE_EDITED);
        this.updatedAt = now;
    }

    public void delete(LocalDateTime now) {
        this.content = null;
        this.status = status.next(EventType.MESSAGE_DELETED);
        this.updatedAt = now;
    }

}

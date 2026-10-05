package com.creativedigital.chat.message.dto;

import com.creativedigital.chat.common.time.KstTime;
import com.creativedigital.chat.message.entity.MessageProjection;
import com.creativedigital.chat.message.entity.MessageStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 현재 메시지 목록 (수정/삭제 반영, 최신순).
 * 비동기 Projection이라 최신 이벤트가 잠깐 늦게 반영될 수 있어 projectedSequence로 반영 위치를 알려준다.
 * hasMore가 true면 마지막 메시지의 sequence를 beforeSequence로 이전 페이지를 조회한다.
 */
public record MessageListResponse(
    List<MessageSummary> messages,
    @Schema(description = "비동기 반영이 끝난 마지막 sequence. 세션의 lastSequence보다 작으면 아직 반영 중") long projectedSequence,
    boolean hasMore
) {

    public static MessageListResponse of(
        List<MessageProjection> messages,
        long projectedSequence,
        boolean hasMore
    ) {
        return new MessageListResponse(
            messages.stream().map(MessageSummary::from).toList(),
            projectedSequence,
            hasMore
        );
    }

    public record MessageSummary(
        String messageId,
        Long senderParticipantId,
        String content,
        MessageStatus status,
        long sequence,
        OffsetDateTime sentAt,
        OffsetDateTime updatedAt
    ) {

        private static MessageSummary from(MessageProjection message) {
            return new MessageSummary(
                message.getMessageId(),
                message.getSenderParticipantId(),
                message.getContent(),
                message.getStatus(),
                message.getSequence(),
                KstTime.toOffset(message.getSentAt()),
                KstTime.toOffset(message.getUpdatedAt())
            );
        }

    }

}

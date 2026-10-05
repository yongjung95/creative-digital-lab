package com.creativedigital.chat.message.service;

import com.creativedigital.chat.common.error.BusinessException;
import com.creativedigital.chat.common.error.ErrorCode;
import com.creativedigital.chat.message.dto.MessageListResponse;
import com.creativedigital.chat.message.entity.MessageProjection;
import com.creativedigital.chat.message.entity.MessageProjectionCheckpoint;
import com.creativedigital.chat.message.repository.MessageProjectionCheckpointRepository;
import com.creativedigital.chat.message.repository.MessageProjectionRepository;
import com.creativedigital.chat.session.repository.SessionRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 현재 메시지 목록 조회 (비동기 Projection).
 */
@Service
@RequiredArgsConstructor
public class MessageService {

    private final MessageProjectionRepository messageProjectionRepository;
    private final MessageProjectionCheckpointRepository checkpointRepository;
    private final SessionRepository sessionRepository;

    /**
     * size + 1개를 조회해 이전 페이지 여부를 판단한다.
     * 한 트랜잭션 안에서 읽으므로(REPEATABLE READ) 메시지 목록과 projectedSequence가 같은 시점 기준으로 맞는다.
     */
    @Transactional(readOnly = true)
    public MessageListResponse getMessages(
        Long sessionId,
        long beforeSequence,
        int size
    ) {
        if (!sessionRepository.existsById(sessionId)) throw new BusinessException(ErrorCode.SESSION_NOT_FOUND);

        List<MessageProjection> messages = messageProjectionRepository.findBySessionIdAndSequenceLessThanOrderBySequenceDesc(
            sessionId,
            beforeSequence,
            Limit.of(size + 1)
        );
        long projectedSequence = checkpointRepository.findById(sessionId)
            .map(MessageProjectionCheckpoint::getLastSequence)
            .orElse(0L);
        boolean hasMore = messages.size() > size;
        return MessageListResponse.of(
            hasMore ? messages.subList(0, size) : messages,
            projectedSequence,
            hasMore
        );
    }

}

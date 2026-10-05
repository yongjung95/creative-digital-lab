package com.creativedigital.chat.message.repository;

import com.creativedigital.chat.message.entity.MessageProjection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MessageProjectionRepository extends JpaRepository<MessageProjection, Long> {

    // 수정/삭제 대상 메시지 (uk_mp_message_id)
    Optional<MessageProjection> findByMessageId(String messageId);

    // beforeSequence 미만, 최신순 (uk_mp_session_sequence)
    List<MessageProjection> findBySessionIdAndSequenceLessThanOrderBySequenceDesc(
        Long sessionId,
        long beforeSequence,
        Limit limit
    );

}

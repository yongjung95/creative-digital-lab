package com.creativedigital.chat.session.repository;

import com.creativedigital.chat.session.entity.Participant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ParticipantRepository extends JpaRepository<Participant, Long> {

    // 정원 체크, 세션 상태 재계산, 종료 처리 (idx_participant_session_status 사용)
    List<Participant> findBySessionId(Long sessionId);

    // 세션 목록의 참여자 이름을 한 번에 조회해 N+1을 피한다
    List<Participant> findBySessionIdIn(Collection<Long> sessionIds);

}

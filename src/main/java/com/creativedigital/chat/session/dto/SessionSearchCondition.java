package com.creativedigital.chat.session.dto;

import com.creativedigital.chat.session.entity.SessionStatus;
import java.time.LocalDateTime;

/**
 * 세션 목록 조회 조건. 모든 필터는 선택이며 null이면 적용하지 않는다.
 */
public record SessionSearchCondition(
    SessionStatus status,
    LocalDateTime from,
    LocalDateTime to,
    String participantName,
    Long cursor,
    int size
) {

}

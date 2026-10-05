package com.creativedigital.chat.event.dto;

import java.util.List;

/**
 * 이벤트 범위 조회 결과. hasMore가 true면 마지막 이벤트의 sequence를 fromSequence로 다음 페이지를 조회한다.
 */
public record EventListResponse(
    List<EventResponse> events,
    boolean hasMore
) {

}

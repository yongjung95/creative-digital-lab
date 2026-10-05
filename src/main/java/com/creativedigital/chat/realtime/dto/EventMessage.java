package com.creativedigital.chat.realtime.dto;

import com.creativedigital.chat.event.dto.EventResponse;
import com.creativedigital.chat.event.dto.EventResult;

/**
 * 서버 → 클라이언트 EVENT. 이벤트는 REST 이벤트 조회 응답과 같은 형태(EventResponse)로 담는다.
 * 클라이언트는 내 메시지든 상대 메시지든 EVENT로만 화면에 반영한다.
 */
public record EventMessage(
    String type,
    EventResponse event
) {

    public static EventMessage from(EventResult event) {
        return new EventMessage(
            "EVENT",
            EventResponse.from(event)
        );
    }

    // 재전송과 실시간 EVENT가 겹칠 때 걸러내는 기준 (ClientConnection)
    public long sequence() {
        return event.sequence();
    }

}

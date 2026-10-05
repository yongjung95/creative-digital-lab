package com.creativedigital.chat.event.dto;

/**
 * 이벤트 저장 알림 (Spring 애플리케이션 이벤트). 커밋 이후 WS push, message_projection 반영, Snapshot 생성이 구독한다.
 * WS push가 이벤트 내용(payload 등)을 그대로 보내야 해서 저장된 이벤트 전체를 담는다.
 */
public record EventAppended(EventResult event) {

    public Long sessionId() {
        return event.sessionId();
    }

    public long sequence() {
        return event.sequence();
    }

}

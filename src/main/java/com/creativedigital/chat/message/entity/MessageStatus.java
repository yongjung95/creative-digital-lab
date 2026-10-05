package com.creativedigital.chat.message.entity;

import com.creativedigital.chat.event.entity.EventType;

public enum MessageStatus {

    SENT,
    EDITED,
    DELETED;

    /**
     * 메시지 이벤트를 적용한 다음 상태. 비동기 Projection과 Replay가 같은 규칙을 공유한다.
     * 삭제된 메시지는 이후 이벤트가 와도 DELETED로 유지한다.
     */
    public MessageStatus next(EventType eventType) {
        if (this == DELETED) return DELETED;

        return switch (eventType) {
            case MESSAGE_EDITED -> EDITED;
            case MESSAGE_DELETED -> DELETED;
            default -> this;
        };
    }

}

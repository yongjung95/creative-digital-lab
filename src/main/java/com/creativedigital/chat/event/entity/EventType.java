package com.creativedigital.chat.event.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum EventType {

    SESSION_CREATED(false, true, true),
    JOIN(false, false, true),
    MESSAGE(true, false, false),
    MESSAGE_EDITED(true, false, false),
    MESSAGE_DELETED(true, false, false),
    LEAVE(true, false, false),
    DISCONNECT(false, false, false),
    RECONNECT(false, false, false),
    SESSION_ENDED(false, false, false);

    // POST /sessions/{id}/events, WS SEND_EVENT로 받을 수 있는 타입인지
    private final boolean clientSubmittable;

    // 서버가 발급하는 값이라 같은 eventId 재요청 비교에서 제외하는지
    private final boolean sessionIdAssignedByServer;
    private final boolean participantIdAssignedByServer;

}

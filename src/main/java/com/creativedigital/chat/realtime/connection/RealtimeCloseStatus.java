package com.creativedigital.chat.realtime.connection;

import com.creativedigital.chat.common.error.ErrorCode;
import org.springframework.web.socket.CloseStatus;

/**
 * WS close 코드 (docs/websocket.md 2장).
 * 브라우저 WebSocket은 handshake 거절 시 HTTP 상태를 읽을 수 없어서, 연결을 받은 뒤 ERROR를 보내고 커스텀 close 코드로 이유를 알린다.
 */
public final class RealtimeCloseStatus {

    // 같은 참여자의 새 연결로 대체됨
    public static final CloseStatus REPLACED = new CloseStatus(4000, "REPLACED");

    private RealtimeCloseStatus() {
    }

    public static CloseStatus from(ErrorCode errorCode) {
        return switch (errorCode) {
            case INVALID_REQUEST -> new CloseStatus(4400, errorCode.name());
            case SESSION_NOT_FOUND, PARTICIPANT_NOT_FOUND -> new CloseStatus(4404, errorCode.name());
            case SESSION_COMPLETED, PARTICIPANT_NOT_ACTIVE -> new CloseStatus(4409, errorCode.name());
            default -> CloseStatus.SERVER_ERROR;
        };
    }

}

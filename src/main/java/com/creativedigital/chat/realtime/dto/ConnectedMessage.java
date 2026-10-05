package com.creativedigital.chat.realtime.dto;

/**
 * 서버 → 클라이언트 CONNECTED. 연결 검증과 등록이 끝났고, replayFromSequence번부터 놓친 이벤트 재전송을 시작한다는 알림.
 */
public record ConnectedMessage(
    String type,
    Long sessionId,
    Long participantId,
    long replayFromSequence
) {

    public static ConnectedMessage of(ConnectionRequest request) {
        return new ConnectedMessage(
            "CONNECTED",
            request.sessionId(),
            request.participantId(),
            request.lastAppliedSequence() + 1
        );
    }

}

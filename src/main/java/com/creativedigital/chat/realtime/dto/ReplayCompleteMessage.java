package com.creativedigital.chat.realtime.dto;

/**
 * 서버 → 클라이언트 REPLAY_COMPLETE. 놓친 이벤트 재전송이 끝났고 이후는 실시간 EVENT라는 알림.
 * lastSequence는 재전송으로 보낸 마지막 이벤트 번호다 (보낸 게 없으면 lastAppliedSequence 그대로).
 */
public record ReplayCompleteMessage(
    String type,
    long lastSequence
) {

    public static ReplayCompleteMessage of(long lastSequence) {
        return new ReplayCompleteMessage(
            "REPLAY_COMPLETE",
            lastSequence
        );
    }

}

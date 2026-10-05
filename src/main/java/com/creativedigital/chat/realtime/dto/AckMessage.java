package com.creativedigital.chat.realtime.dto;

import com.creativedigital.chat.event.dto.AppendResult;

/**
 * 서버 → 보낸 사람 ACK. SEND_EVENT 요청이 성공했다는 확인이고, 화면 반영은 EVENT로 한다.
 * duplicate=true면 같은 eventId의 재시도라 기존 결과를 돌려준 것 (REST의 200).
 */
public record AckMessage(
    String type,
    String eventId,
    long sequence,
    boolean duplicate
) {

    public static AckMessage from(AppendResult result) {
        return new AckMessage(
            "ACK",
            result.event().eventId(),
            result.event().sequence(),
            result.duplicate()
        );
    }

}

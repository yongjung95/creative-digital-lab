package com.creativedigital.chat.event.dto;

/**
 * 이벤트 저장 결과. duplicate가 true면 같은 eventId의 재시도라 새로 저장하지 않고 기존 이벤트를 반환한 것 (201 / 200 구분).
 */
public record AppendResult(
    EventResult event,
    boolean duplicate
) {

    public static AppendResult created(EventResult event) {
        return new AppendResult(event, false);
    }

    public static AppendResult duplicate(EventResult event) {
        return new AppendResult(event, true);
    }

}

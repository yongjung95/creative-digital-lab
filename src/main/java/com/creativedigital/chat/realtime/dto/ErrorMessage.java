package com.creativedigital.chat.realtime.dto;

import com.creativedigital.chat.common.error.ErrorCode;

/**
 * 서버 → 보낸 사람 ERROR. 에러 코드는 REST와 같다 (docs/api.md).
 * eventId는 어떤 요청이 실패했는지 알려주기 위한 값이고, 연결 실패나 형식을 읽지 못한 경우는 null이다.
 */
public record ErrorMessage(
    String type,
    String eventId,
    ErrorCode code,
    String message
) {

    public static ErrorMessage of(
        String eventId,
        ErrorCode errorCode
    ) {
        return new ErrorMessage(
            "ERROR",
            eventId,
            errorCode,
            errorCode.getMessage()
        );
    }

}

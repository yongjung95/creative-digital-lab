package com.creativedigital.chat.common.error;

import java.sql.SQLTransientConnectionException;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "잘못된 요청입니다."),
    NOT_MESSAGE_OWNER(HttpStatus.FORBIDDEN, "본인이 보낸 메시지만 수정/삭제할 수 있습니다."),
    SESSION_NOT_FOUND(HttpStatus.NOT_FOUND, "세션을 찾을 수 없습니다."),
    PARTICIPANT_NOT_FOUND(HttpStatus.NOT_FOUND, "세션 참여자를 찾을 수 없습니다."),
    MESSAGE_NOT_FOUND(HttpStatus.NOT_FOUND, "메시지를 찾을 수 없습니다."),
    EVENT_ID_CONFLICT(HttpStatus.CONFLICT, "같은 eventId로 다른 내용의 요청이 이미 처리되었습니다."),
    SESSION_FULL(HttpStatus.CONFLICT, "세션 정원(2명)이 가득 찼습니다."),
    SESSION_COMPLETED(HttpStatus.CONFLICT, "이미 종료된 세션입니다."),
    PARTICIPANT_NOT_ACTIVE(HttpStatus.CONFLICT, "요청을 처리할 수 없는 참여자 상태입니다."),
    MESSAGE_ALREADY_DELETED(HttpStatus.CONFLICT, "이미 삭제된 메시지입니다."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "요청을 처리하는 중 오류가 발생했습니다."),
    SESSION_BUSY(HttpStatus.SERVICE_UNAVAILABLE, "요청이 몰려 처리하지 못했습니다. 잠시 후 다시 시도해주세요."),
    SERVER_BUSY(HttpStatus.SERVICE_UNAVAILABLE, "서버가 요청을 처리할 여유가 없습니다. 잠시 후 다시 시도해주세요.");

    private static final String EVENT_ID_UNIQUE_KEY = "uk_event_event_id";

    private final HttpStatus status;
    private final String message;

    /**
     * 예외를 에러 코드로 바꾼다. REST(GlobalExceptionHandler)와 같은 규칙으로 WS ERROR 메시지에 사용한다.
     * 예상하지 못한 예외는 내부 정보를 노출하지 않도록 INTERNAL_ERROR로 숨긴다.
     * - 무결성 위반은 eventId UNIQUE에 걸린 경우만 EVENT_ID_CONFLICT (서로 다른 세션에 같은 eventId가 동시에 들어온 경우). 나머지는 버그라 INTERNAL_ERROR
     * - DB 커넥션을 얻지 못한 경우(풀 고갈, DB 연결 불가)는 재시도하면 되는 상황이라 SERVER_BUSY
     */
    public static ErrorCode from(Exception e) {
        return switch (e) {
            case BusinessException businessException -> businessException.getErrorCode();
            case PessimisticLockingFailureException lockTimeout -> SESSION_BUSY;
            case DataIntegrityViolationException integrityViolation when isEventIdDuplicate(integrityViolation) -> EVENT_ID_CONFLICT;
            default -> isConnectionUnavailable(e) ? SERVER_BUSY : INTERNAL_ERROR;
        };
    }

    private static boolean isEventIdDuplicate(DataIntegrityViolationException e) {
        String message = e.getMostSpecificCause().getMessage();
        return message != null && message.contains(EVENT_ID_UNIQUE_KEY);
    }

    // HikariCP는 connection-timeout 안에 커넥션을 얻지 못하면 SQLTransientConnectionException을 던진다. 스프링이 감싸서 던지므로 원인을 따라가며 찾는다
    private static boolean isConnectionUnavailable(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLTransientConnectionException) return true;
        }
        return false;
    }

}

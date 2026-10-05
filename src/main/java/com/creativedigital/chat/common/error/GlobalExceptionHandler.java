package com.creativedigital.chat.common.error;

import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 모든 에러를 RFC 7807 ProblemDetail + 확장 속성 code로 응답한다.
 * 예상하지 못한 에러는 내부 정보를 노출하지 않고 일반 메시지만 응답하며, 자세한 내용은 서버 로그로만 남긴다.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final String CODE_PROPERTY = "code";
    private static final String INVALID_FORMAT_MESSAGE = "형식이 올바르지 않습니다";

    @ExceptionHandler(BusinessException.class)
    public ProblemDetail handleBusiness(BusinessException e) {
        return problem(e.getErrorCode(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
            .map(error -> error.getField() + ": " + resolveFieldMessage(error))
            .collect(Collectors.joining(", "));
        return problem(ErrorCode.INVALID_REQUEST, detail);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ProblemDetail handleUnreadable(Exception e) {
        return problem(ErrorCode.INVALID_REQUEST, ErrorCode.INVALID_REQUEST.getMessage());
    }

    // session 행 Lock 대기 타임아웃. Hot Session에서 커넥션이 묶이지 않도록 빠르게 실패시킨다
    // 로그는 sessionId를 아는 SessionService.lockSession에서 남긴다
    @ExceptionHandler(PessimisticLockingFailureException.class)
    public ProblemDetail handleLockTimeout(PessimisticLockingFailureException e) {
        return problem(ErrorCode.SESSION_BUSY, ErrorCode.SESSION_BUSY.getMessage());
    }

    // 서로 다른 세션에 같은 eventId가 동시에 들어와 UNIQUE(event_id)에 걸린 경우만 409. 다른 무결성 위반은 예상하지 못한 에러로 본다
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleIntegrityViolation(DataIntegrityViolationException e) {
        ErrorCode errorCode = ErrorCode.from(e);
        if (errorCode == ErrorCode.EVENT_ID_CONFLICT) {
            log.warn("event_id_duplicate reason={}", e.getMostSpecificCause().getMessage());
        } else {
            log.error("unexpected integrity violation", e);
        }
        return problem(errorCode, errorCode.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception e) {
        // 404(없는 경로), 405 같은 Spring MVC 표준 예외는 원래 응답을 유지한다
        if (e instanceof ErrorResponse errorResponse) return errorResponse.getBody();

        // DB 커넥션을 얻지 못한 경우(풀 고갈 등)는 재시도 가능한 503
        ErrorCode errorCode = ErrorCode.from(e);
        if (errorCode == ErrorCode.SERVER_BUSY) {
            log.warn("db_connection_unavailable reason={}", e.getMessage());
        } else {
            log.error("unexpected error", e);
        }
        return problem(errorCode, errorCode.getMessage());
    }

    private ProblemDetail problem(ErrorCode errorCode, String detail) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(errorCode.getStatus(), detail);
        problemDetail.setProperty(CODE_PROPERTY, errorCode.name());
        return problemDetail;
    }

    /**
     * @ModelAttribute 바인딩에서는 타입 변환 실패(?at=abc)도 검증 실패와 같은 필드 에러로 담긴다.
     * 변환 실패의 기본 메시지에는 자바 타입명이 들어 있어 노출하지 않고 고정 문구로 바꾼다.
     */
    private String resolveFieldMessage(FieldError error) {
        if (error.isBindingFailure()) return INVALID_FORMAT_MESSAGE;
        return error.getDefaultMessage();
    }

}

package com.creativedigital.chat.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.SQLTransientConnectionException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * 예외 → 에러 코드 변환 규칙. REST와 WS가 같은 규칙을 쓴다.
 */
class ErrorCodeTest {

    @Test
    void eventId_UNIQUE_위반은_EVENT_ID_CONFLICT() {
        DataIntegrityViolationException e = new DataIntegrityViolationException(
            "could not execute statement",
            new SQLIntegrityConstraintViolationException("Duplicate entry 'abc' for key 'uk_event_event_id'")
        );

        assertThat(ErrorCode.from(e)).isEqualTo(ErrorCode.EVENT_ID_CONFLICT);
    }

    @Test
    void eventId가_아닌_무결성_위반은_INTERNAL_ERROR() {
        DataIntegrityViolationException e = new DataIntegrityViolationException(
            "could not execute statement",
            new SQLIntegrityConstraintViolationException("Column 'session_id' cannot be null")
        );

        assertThat(ErrorCode.from(e)).isEqualTo(ErrorCode.INTERNAL_ERROR);
    }

    @Test
    void 커넥션을_얻지_못한_예외는_감싸져_있어도_SERVER_BUSY() {
        DataAccessResourceFailureException e = new DataAccessResourceFailureException(
            "Unable to acquire JDBC Connection",
            new SQLTransientConnectionException("HikariPool-1 - Connection is not available, request timed out after 3000ms")
        );

        assertThat(ErrorCode.from(e)).isEqualTo(ErrorCode.SERVER_BUSY);
    }

}

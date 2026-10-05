package com.creativedigital.chat.common.time;

import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * DB 시각(KST) 조회. session 행 Lock 안에서 호출해 sequence 순서와 시각 순서를 일치시킨다.
 * 앱 서버 시계를 쓰지 않으므로 서버 간 시계 차이의 영향을 받지 않는다.
 */
@Component
@RequiredArgsConstructor
public class DbClock {

    private final JdbcTemplate jdbcTemplate;

    public LocalDateTime now() {
        return jdbcTemplate.queryForObject("SELECT NOW(6)", LocalDateTime.class);
    }

}

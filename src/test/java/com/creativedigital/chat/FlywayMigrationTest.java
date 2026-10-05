package com.creativedigital.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class FlywayMigrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void V1_마이그레이션으로_테이블_6개가_생성된다() {
        List<String> tables = jdbcTemplate.queryForList(
            """
            SELECT table_name FROM information_schema.tables
            WHERE table_schema = DATABASE() AND table_name <> 'flyway_schema_history'
            """,
            String.class
        );

        assertThat(tables).containsExactlyInAnyOrder(
            "session",
            "participant",
            "event",
            "message_projection",
            "message_projection_checkpoint",
            "snapshot"
        );
    }

}

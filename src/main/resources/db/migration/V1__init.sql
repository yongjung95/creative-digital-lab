-- 스키마 설계 근거: docs/erd.md

-- 세션: 현재 상태 + sequence 발급 (Lock 대상)
CREATE TABLE session (
  id             BIGINT       NOT NULL AUTO_INCREMENT,
  status         VARCHAR(20)  NOT NULL,
  last_sequence  BIGINT       NOT NULL DEFAULT 0,
  created_at     DATETIME(6)  NOT NULL,
  updated_at     DATETIME(6)  NOT NULL,
  ended_at       DATETIME(6)  NULL,
  PRIMARY KEY (id),
  KEY idx_session_status_created (status, created_at),
  KEY idx_session_created (created_at),
  KEY idx_session_updated (updated_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- 참여자: 동기 Projection
CREATE TABLE participant (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  session_id    BIGINT       NOT NULL,
  display_name  VARCHAR(50)  NOT NULL,
  status        VARCHAR(20)  NOT NULL,
  joined_at     DATETIME(6)  NOT NULL,
  left_at       DATETIME(6)  NULL,
  updated_at    DATETIME(6)  NOT NULL,
  PRIMARY KEY (id),
  KEY idx_participant_session_status (session_id, status),
  KEY idx_participant_display_name (display_name),
  CONSTRAINT fk_participant_session FOREIGN KEY (session_id) REFERENCES session (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- 이벤트: Source of Truth
CREATE TABLE event (
  id               BIGINT       NOT NULL AUTO_INCREMENT,
  event_id         CHAR(36)     NOT NULL,
  session_id       BIGINT       NOT NULL,
  sequence         BIGINT       NOT NULL,
  event_type       VARCHAR(30)  NOT NULL,
  participant_id   BIGINT       NULL,
  target_event_id  CHAR(36)     NULL,
  payload          JSON         NULL,
  occurred_at      DATETIME(6)  NOT NULL,
  created_at       DATETIME(6)  NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_event_event_id (event_id),
  UNIQUE KEY uk_event_session_sequence (session_id, sequence),
  KEY idx_event_session_created_sequence (session_id, created_at, sequence),
  KEY idx_event_target (target_event_id),
  CONSTRAINT fk_event_session     FOREIGN KEY (session_id)     REFERENCES session (id),
  CONSTRAINT fk_event_participant FOREIGN KEY (participant_id) REFERENCES participant (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- 메시지 Projection: 비동기, 현재 메시지 목록 (FK 없음: 이벤트 저장 중인 session 행 Lock 경합 방지)
CREATE TABLE message_projection (
  id                     BIGINT       NOT NULL AUTO_INCREMENT,
  message_id             CHAR(36)     NOT NULL,
  session_id             BIGINT       NOT NULL,
  sequence               BIGINT       NOT NULL,
  sender_participant_id  BIGINT       NOT NULL,
  content                TEXT         NULL,
  status                 VARCHAR(20)  NOT NULL,
  sent_at                DATETIME(6)  NOT NULL,
  updated_at             DATETIME(6)  NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_mp_message_id (message_id),
  UNIQUE KEY uk_mp_session_sequence (session_id, sequence)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- 메시지 Projection 반영 위치
CREATE TABLE message_projection_checkpoint (
  session_id     BIGINT       NOT NULL,
  last_sequence  BIGINT       NOT NULL DEFAULT 0,
  updated_at     DATETIME(6)  NOT NULL,
  PRIMARY KEY (session_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- 스냅샷: 비동기, 과거 시점 복원 최적화 (FK 없음)
CREATE TABLE snapshot (
  id             BIGINT       NOT NULL AUTO_INCREMENT,
  session_id     BIGINT       NOT NULL,
  sequence       BIGINT       NOT NULL,
  state          JSON         NOT NULL,
  created_at     DATETIME(6)  NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_snapshot_session_sequence (session_id, sequence)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

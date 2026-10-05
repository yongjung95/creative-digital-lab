-- docs/query.md 측정을 재현하는 스크립트.
-- 앱이 쓰는 chat DB와 분리된 chat_bench 스키마에 대량 데이터를 넣고 핫패스 쿼리의 실행 계획을 확인한다.
--
-- 실행 (docker compose로 MariaDB가 떠 있는 상태):
--   docker exec -i chat-mariadb mariadb -uroot -proot -e "DROP DATABASE IF EXISTS chat_bench; CREATE DATABASE chat_bench CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci"
--   docker exec -i chat-mariadb mariadb -uroot -proot chat_bench < src/main/resources/db/migration/V1__init.sql
--   docker exec -i chat-mariadb mariadb -uroot -proot -t chat_bench < docs/query-bench.sql
-- 정리:
--   docker exec -i chat-mariadb mariadb -uroot -proot -e "DROP DATABASE chat_bench"

-- ===== 데이터 =====
-- 세션 1,000개 × 이벤트 1,000개 = 100만 건 + 이벤트 10만 개짜리 긴 세션 1개 (1001번)
-- seq_1_to_N은 MariaDB에 내장된 숫자 시퀀스 테이블이다

SET FOREIGN_KEY_CHECKS = 0;
SET unique_checks = 0;

INSERT INTO session (id, status, last_sequence, created_at, updated_at)
SELECT seq, 'IN_PROGRESS', 1000,
       TIMESTAMP('2026-10-01 00:00:00') + INTERVAL seq * 90 SECOND,
       TIMESTAMP('2026-10-01 00:00:00') + INTERVAL seq * 90 + 1000 SECOND
FROM seq_1_to_1000;

INSERT INTO session (id, status, last_sequence, created_at, updated_at)
VALUES (1001, 'IN_PROGRESS', 100000, '2026-10-03 00:00:00', '2026-10-03 00:00:00');

INSERT INTO participant (id, session_id, display_name, status, joined_at, updated_at)
SELECT seq, (seq + 1) DIV 2, IF(seq % 2 = 1, '철수', '영희'), 'ACTIVE', NOW(6), NOW(6)
FROM seq_1_to_2002;

-- 1: 생성, 2~3: JOIN, 4~1000: 메시지. 1초 간격
INSERT INTO event (event_id, session_id, sequence, event_type, participant_id, payload, occurred_at, created_at)
SELECT UUID(), s.seq, e.seq,
       CASE WHEN e.seq = 1 THEN 'SESSION_CREATED' WHEN e.seq <= 3 THEN 'JOIN' ELSE 'MESSAGE' END,
       CASE WHEN e.seq = 1 THEN NULL ELSE s.seq * 2 - (e.seq % 2) END,
       CASE WHEN e.seq <= 3 THEN NULL ELSE JSON_OBJECT('content', CONCAT('메시지 ', e.seq)) END,
       TIMESTAMP('2026-10-01 00:00:00') + INTERVAL s.seq * 90 + e.seq SECOND,
       TIMESTAMP('2026-10-01 00:00:00') + INTERVAL s.seq * 90 + e.seq SECOND
FROM seq_1_to_1000 s CROSS JOIN seq_1_to_1000 e
ORDER BY s.seq, e.seq;

INSERT INTO event (event_id, session_id, sequence, event_type, participant_id, payload, occurred_at, created_at)
SELECT UUID(), 1001, seq, 'MESSAGE', 2001, JSON_OBJECT('content', CONCAT('메시지 ', seq)),
       TIMESTAMP('2026-10-03 00:00:00') + INTERVAL seq SECOND,
       TIMESTAMP('2026-10-03 00:00:00') + INTERVAL seq SECOND
FROM seq_1_to_100000;

INSERT INTO message_projection (message_id, session_id, sequence, sender_participant_id, content, status, sent_at, updated_at)
SELECT event_id, session_id, sequence, participant_id, JSON_VALUE(payload, '$.content'), 'SENT', created_at, created_at
FROM event WHERE event_type = 'MESSAGE';

SET unique_checks = 1;
SET FOREIGN_KEY_CHECKS = 1;

ANALYZE TABLE session, participant, event, message_projection;

SELECT 'event' AS tbl, COUNT(*) AS cnt FROM event
UNION ALL SELECT 'message_projection', COUNT(*) FROM message_projection;

-- ===== Q1. 이벤트 범위 조회 (재연결 재전송, Replay, 빈틈 채우기) =====

ANALYZE SELECT * FROM event
WHERE session_id = 500 AND sequence > 200 AND sequence <= 1000
ORDER BY sequence LIMIT 500;

-- 긴 세션(10만 건)의 끝부분: 세션 길이와 무관하게 필요한 만큼만 읽는지
ANALYZE SELECT * FROM event
WHERE session_id = 1001 AND sequence > 99500 AND sequence <= 100000
ORDER BY sequence LIMIT 500;

-- 비교: 인덱스 없이
ANALYZE SELECT * FROM event IGNORE INDEX (uk_event_session_sequence, idx_event_session_created_sequence)
WHERE session_id = 500 AND sequence > 200 AND sequence <= 1000
ORDER BY sequence LIMIT 500;

-- ===== Q2. 시점 → sequence 변환 (timeline?at=) =====
-- 긴 세션(10만 건)에서 9만 번째 이벤트 시각 기준

ANALYZE SELECT sequence FROM event
WHERE session_id = 1001 AND created_at <= '2026-10-04 01:00:00'
ORDER BY created_at DESC, sequence DESC LIMIT 1;

-- 비교: MAX(sequence) + 같은 인덱스
ANALYZE SELECT MAX(sequence) FROM event
WHERE session_id = 1001 AND created_at <= '2026-10-04 01:00:00';

-- 비교: MAX(sequence) + sequence가 없는 인덱스 (session_id, created_at)
CREATE INDEX idx_bench_session_created ON event (session_id, created_at);
ANALYZE SELECT MAX(sequence) FROM event IGNORE INDEX (idx_event_session_created_sequence)
WHERE session_id = 1001 AND created_at <= '2026-10-04 01:00:00';
DROP INDEX idx_bench_session_created ON event;

-- ===== Q3. 현재 메시지 목록 (GET /messages, 최신순 페이징) =====

ANALYZE SELECT * FROM message_projection
WHERE session_id = 500 AND sequence < 1001
ORDER BY sequence DESC LIMIT 50;

-- 비교: 인덱스 없이
ANALYZE SELECT * FROM message_projection IGNORE INDEX (uk_mp_session_sequence)
WHERE session_id = 500 AND sequence < 1001
ORDER BY sequence DESC LIMIT 50;

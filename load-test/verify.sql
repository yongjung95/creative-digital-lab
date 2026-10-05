-- 부하 테스트 후 정합성 확인
-- k6 시작 시 출력된 세션 번호를 아래 두 변수에 넣고 실행한다 (시나리오 1은 둘 다 같은 번호, 시나리오 2는 sessionIds 범위)
-- docker exec -i chat-mariadb mariadb -uchat -pchat chat < load-test/verify.sql
-- 메시지 목록 반영은 비동기라, 테스트가 끝나고 안전망 스케줄러 주기(1분)가 지난 뒤 실행한다
SET @from_session = 1;
SET @to_session = 1;

-- 1. 저장된 메시지 수: k6 요약의 checks 성공 수(워밍업 포함)와 같아야 한다
SELECT SUM(event_type = 'MESSAGE') AS messages
FROM event
WHERE session_id BETWEEN @from_session AND @to_session;

-- 2. sequence 빈틈·중복이 있는 세션: 결과가 비어 있어야 정상
SELECT s.id, s.last_sequence, COUNT(*) AS events, COUNT(DISTINCT e.sequence) AS distinct_sequences,
       MIN(e.sequence) AS min_sequence, MAX(e.sequence) AS max_sequence
FROM session s
JOIN event e ON e.session_id = s.id
WHERE s.id BETWEEN @from_session AND @to_session
GROUP BY s.id, s.last_sequence
HAVING MIN(e.sequence) <> 1
    OR MAX(e.sequence) <> COUNT(*)
    OR COUNT(DISTINCT e.sequence) <> COUNT(*)
    OR s.last_sequence <> MAX(e.sequence);

-- 3. 메시지 목록 반영이 마지막 sequence까지 따라오지 못한 세션: 결과가 비어 있어야 정상
SELECT s.id, s.last_sequence, c.last_sequence AS projected_sequence
FROM session s
LEFT JOIN message_projection_checkpoint c ON c.session_id = s.id
WHERE s.id BETWEEN @from_session AND @to_session
  AND (c.last_sequence IS NULL OR c.last_sequence <> s.last_sequence);

-- 4. Snapshot 수: 있어야 할 수(세션별 "마지막 sequence ÷ 1000" 합계)와 실제 수가 같아야 한다
SELECT SUM(FLOOR(s.last_sequence / 1000)) AS expected_snapshots,
       (SELECT COUNT(*)
        FROM snapshot sn
        WHERE sn.session_id BETWEEN @from_session AND @to_session
          AND sn.sequence % 1000 = 0) AS actual_snapshots
FROM session s
WHERE s.id BETWEEN @from_session AND @to_session;

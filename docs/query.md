# 쿼리 최적화

대화 데이터가 대량으로 쌓였을 때의 핫패스 쿼리 3개를 실제 데이터로 측정한 결과와 인덱스 설계 근거.

관련 문서: [설계 문서](design.md) · [ERD / 인덱스 목록](erd.md)

## 측정 방법

- 데이터: 세션 1,000개 × 이벤트 1,000개 + 이벤트 10만 개짜리 긴 세션 1개 → **이벤트 110만 건**, 메시지 목록 약 110만 행
- 환경: MariaDB 11.4 (Docker, Apple Silicon 로컬). 앱 DB와 분리된 `chat_bench` 스키마
- `ANALYZE SELECT`로 실행 계획(`key`)과 **실제로 읽은 행 수**(`r_rows`)를 보고, 시간은 3회 측정 중 안정된 값
- 재현: [query-bench.sql](query-bench.sql) (데이터 생성 + 아래 쿼리 전부, 약 15초)

## 요약

| 쿼리 | 쓰는 곳 | 인덱스 | 읽은 행 | 시간 | 인덱스 없이 |
|---|---|---|---|---|---|
| Q1. 이벤트 범위 조회 | 재연결 재전송, Replay, 빈틈 채우기 | `uk_event_session_sequence` | 500 | 0.45ms | 110만 행 + 정렬, 약 270ms |
| Q2. 시점 → sequence | `timeline?at=` | `idx_event_session_created_sequence` | 1 | 0.02ms | 110만 행, 약 270ms |
| Q3. 현재 메시지 목록 | 채팅방 입장, 이전 페이지 | `uk_mp_session_sequence` | 50 | 0.06ms | 110만 행 + 정렬, 약 230ms |

세 쿼리 모두 **세션 길이나 전체 데이터 양과 무관하게 필요한 만큼만** 읽는다.

---

## Q1. 이벤트 범위 조회

재연결할 때 놓친 이벤트 재전송, 과거 상태 복원의 Replay, 클라이언트 빈틈 채우기(`GET /events`)가 모두 이 쿼리를 쓴다. 500개씩 끊어 읽는다.

```sql
SELECT * FROM event
WHERE session_id = ? AND sequence > ? AND sequence <= ?
ORDER BY sequence
LIMIT 500;
```

**인덱스**: `UNIQUE (session_id, sequence)`. 세션 안에서 sequence 순서로 정렬돼 있어서 시작 위치를 바로 찾고 순서대로 500개만 읽는다. 정렬이 따로 필요 없다. 같은 세션에 같은 sequence가 두 번 들어가는 것도 이 제약이 막는다.

| 조건 | key | 읽은 행 | 시간 |
|---|---|---|---|
| 일반 세션 (1,000건) | `uk_event_session_sequence` | 500 | 0.45ms |
| 긴 세션 (10만 건)의 끝부분 | `uk_event_session_sequence` | 500 | 0.45ms |
| 인덱스 없이 | 없음 (전체 스캔) | 110만 + filesort | 약 270ms |

**병목과 개선 방향**
- 읽는 양이 세션 길이와 무관해서 데이터가 늘어도 느려지지 않는다.
- 한 번에 읽는 양이 커지면 응답 크기와 메모리가 문제가 된다. 그래서 500개씩 끊고, 복원 중에는 묶음마다 영속성 컨텍스트를 비운다.

---

## Q2. 시점 → sequence 변환

`timeline?at=`의 입구. 그 시각까지 저장된 마지막 이벤트의 sequence를 구한 뒤 sequence 기준으로 복원한다.

```sql
SELECT sequence FROM event
WHERE session_id = ? AND created_at <= ?
ORDER BY created_at DESC, sequence DESC
LIMIT 1;
```

**인덱스**: `(session_id, created_at, sequence)`. 그 시각 바로 앞 항목으로 가서 한 건만 읽고, 필요한 값이 전부 인덱스에 있어 테이블을 보지 않는다(`Using index`).

**대안 비교** (10만 건 세션에서 9만 번째 이벤트 시각, 세 방식의 결과는 모두 90000으로 같다)

| 쿼리 | 인덱스 | key | 읽은 행 | 시간 |
|---|---|---|---|---|
| `MAX(sequence)` | `(session_id, created_at)` | 없음 (전체 스캔 선택) | 110만 | 약 270ms |
| `MAX(sequence)` | `(session_id, created_at, sequence)` | 새 인덱스 (`Using index`) | 9만 | 13ms |
| **직전 1건 (`DESC LIMIT 1`)** | **`(session_id, created_at, sequence)`** | **새 인덱스 (`Using index`)** | **1** | **0.02ms** |

- `MAX(sequence)`는 조건이 `session_id`만이면 인덱스 끝값 하나로 끝나지만, `created_at` 범위 조건이 붙으면 범위 안의 행을 전부 봐야 한다. 인덱스에 sequence가 없으면 행마다 테이블을 찾아가야 해서, 옵티마이저가 차라리 전체 스캔을 고른다.
- 이벤트 1,000건짜리 세션에서는 어느 방식이든 1ms 미만이라 차이가 보이지 않는다. 세션이 길어질수록 벌어진다.
- **직전 1건으로 바꿀 수 있는 이유**: 같은 세션 안에서는 sequence가 크면 저장 시각도 크거나 같다. 저장 시각을 앱 서버 시계가 아니라 DB 시각으로, sequence 발급과 같은 session 행 Lock 안에서 기록하기 때문이다 ([설계 문서 4장](design.md#4-순서-처리)). 시각이 마이크로초까지 같은 이벤트가 있어도 `sequence DESC`로 큰 쪽을 고른다.

---

## Q3. 현재 메시지 목록

채팅방에 들어갈 때와 이전 메시지를 더 불러올 때(`GET /messages?beforeSequence=`). 커서 방식이라 OFFSET을 쓰지 않는다.

```sql
SELECT * FROM message_projection
WHERE session_id = ? AND sequence < ?
ORDER BY sequence DESC
LIMIT 50;
```

**인덱스**: `UNIQUE (session_id, sequence)`. 인덱스를 거꾸로 읽어서 최신순 50개를 정렬 없이 가져온다.

| 조건 | key | 읽은 행 | 시간 |
|---|---|---|---|
| 인덱스 사용 | `uk_mp_session_sequence` | 50 | 0.06ms |
| 인덱스 없이 | 없음 (전체 스캔) | 110만 + filesort | 약 230ms |

**병목과 개선 방향**
- OFFSET 페이징이면 뒤 페이지로 갈수록 건너뛸 행을 전부 읽어야 한다. sequence 커서라 몇 번째 페이지든 50개만 읽는다.
- 비동기로 반영되는 테이블이라 이벤트 저장 경로와 Lock을 다투지 않는다 ([설계 문서 8장](design.md#8-비동기-처리)).

---

## 쓰기 경로

이벤트 저장은 요청마다 아래 쿼리를 실행한다. 조회는 전부 PK나 UNIQUE로 한 건을 찾는다.

| 단계 | 쿼리 | 인덱스 |
|---|---|---|
| session 행 Lock | `SELECT ... FROM session WHERE id = ? FOR UPDATE` | PK |
| 같은 eventId 확인 | `SELECT ... FROM event WHERE event_id = ?` | `uk_event_event_id` |
| 저장 | `INSERT INTO event ...` | PK + UNIQUE 2개 + 보조 인덱스 2개 갱신 |

- 병목은 쿼리가 아니라 **같은 session 행 Lock 경쟁**이다. 한 세션에 요청이 몰리면 줄을 선다 (Hot Session, [설계 문서 10장](design.md#10-수평-확장)). Lock 대기는 3초로 제한한다.
- 이벤트 테이블의 인덱스가 늘수록 저장이 느려지므로, 조회에 필요한 것만 둔다. JSON payload 내부는 검색하지 않아 인덱스를 두지 않는다.

## 데이터가 더 커지면

- **모든 핫패스 쿼리가 `session_id`로 시작**한다. 전체 데이터가 늘어도 한 세션 범위만 읽으므로 응답 시간이 거의 변하지 않는다. 늘어나는 것은 인덱스 높이(로그 수준)뿐이다.
- **아카이빙**: 종료된 지 오래된 세션의 이벤트는 별도 저장소로 옮긴다. 복원이 필요하면 Snapshot + 남은 이벤트로 처리한다.
- **파티셔닝**: 이벤트 테이블을 생성 시각 범위로 나누면 오래된 데이터 정리가 파티션 삭제로 끝난다. 다만 MariaDB는 파티션 키가 모든 UNIQUE 키에 들어가야 해서, `uk_event_event_id`(전역 멱등성 키)를 유지하려면 별도 테이블로 분리하는 등 설계 변경이 필요하다.
- **읽기 분리**: 복원과 조회는 읽기 전용이라 읽기 복제본으로 보낼 수 있다. 단, 복제 지연 때문에 방금 저장한 이벤트가 안 보일 수 있으므로 재연결 재전송처럼 최신성이 중요한 조회는 원본 DB에 둔다.

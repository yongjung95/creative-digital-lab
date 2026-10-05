# 실시간 1:1 채팅 + 이벤트 기반 상태 복원

1:1 실시간 채팅에서 일어난 모든 일을 이벤트로 저장하고, 그 이벤트로 **특정 시점의 대화 상태를 복원**하는 백엔드다.
중복·순서 뒤바뀜·재연결 상황에서도 같은 이벤트면 항상 같은 상태가 되도록(Determinism) 설계했다.

`Java 25` · `Spring Boot 4.1` · `MariaDB 11.4` · `raw WebSocket` · `Flyway` · `Testcontainers`

---

## 1. 빠른 시작

Docker만 있으면 된다.

```bash
docker compose up -d --build
```

| 주소 | 내용 |
|---|---|
| http://localhost:8080/ | 브라우저 테스트 클라이언트 |
| http://localhost:8080/swagger-ui/index.html | REST API 문서 (Swagger UI) |
| http://localhost:8080/actuator/health | 헬스 체크 |

- `demo` 프로필로 뜬다. Snapshot 간격이 20이라 메시지 몇십 개만 보내도 Snapshot이 만들어진다 (기본값 1000).
- 종료: `docker compose down` (데이터까지 지우려면 `docker compose down -v`)

---

## 2. 동작 확인

### 2-1. 브라우저 테스트 클라이언트

http://localhost:8080/ 을 탭 두 개로 연다. 탭 하나가 참여자 한 명이다.

1. 탭 A: [세션 생성] → 이름 입력 → [JOIN] (자동으로 연결된다)
2. 탭 B: `http://localhost:8080/?session={세션 번호}` → 다른 이름으로 [JOIN]

| 해볼 것 | 확인할 곳 |
|---|---|
| 메시지 전송 (WS / REST 전환 가능) | 상대 탭에 실시간 표시, 로그에 `ACK`와 `EVENT` |
| [마지막 요청 재전송] | 같은 eventId라 화면은 그대로, 로그에 `duplicate=true` |
| B에서 [끊기] → A가 메시지 전송 → B에서 [연결] | A에 "연결 끊김", B는 놓친 이벤트를 재전송(노란 줄)으로 받고 `REPLAY_COMPLETE` |
| B 주소를 새 탭에 열기 | 기존 B 탭은 `close 4000`(대체됨), A에는 끊김이 찍히지 않음 |
| [이 번호로 재연결]에 작은 번호 입력 | 서버가 다시 보낸 이벤트를 클라이언트가 "이미 적용함 → 무시" |
| timeline 칸에 sequence 입력 | 그 시점 상태와 `snapshot N + replay M개` |

### 2-2. curl

eventId는 소문자 UUID만 받는다. macOS의 `uuidgen`은 대문자라서 `tr`로 바꾼다. 응답의 번호로 아래 `1`을 바꿔 쓴다.

```bash
H='Content-Type: application/json'

# 세션 생성 → {"sessionId":1, ...}
curl -s -X POST localhost:8080/sessions -H "$H" -d "{\"eventId\":\"$(uuidgen | tr A-Z a-z)\"}"

# 참여 → {"participantId":1, ...}
curl -s -X POST localhost:8080/sessions/1/join -H "$H" \
  -d "{\"eventId\":\"$(uuidgen | tr A-Z a-z)\",\"displayName\":\"철수\"}"

# 메시지 → 201
E=$(uuidgen | tr A-Z a-z)
curl -s -w '\n%{http_code}\n' -X POST localhost:8080/sessions/1/events -H "$H" \
  -d "{\"eventId\":\"$E\",\"participantId\":1,\"type\":\"MESSAGE\",\"payload\":{\"content\":\"안녕\"}}"

# 같은 eventId로 재시도 → 200 + 처음과 같은 응답 (새로 저장하지 않음)
curl -s -w '\n%{http_code}\n' -X POST localhost:8080/sessions/1/events -H "$H" \
  -d "{\"eventId\":\"$E\",\"participantId\":1,\"type\":\"MESSAGE\",\"payload\":{\"content\":\"안녕\"}}"

# 같은 eventId에 내용만 다르게 → 409 EVENT_ID_CONFLICT
curl -s -w '\n%{http_code}\n' -X POST localhost:8080/sessions/1/events -H "$H" \
  -d "{\"eventId\":\"$E\",\"participantId\":1,\"type\":\"MESSAGE\",\"payload\":{\"content\":\"바뀜\"}}"

# 특정 시점 복원 (sequence 또는 at 중 하나)
curl -s 'localhost:8080/sessions/1/timeline?sequence=2'
curl -s -G localhost:8080/sessions/1/timeline --data-urlencode 'at=2030-01-01T00:00:00+09:00'

# 이벤트 조회 / 현재 메시지 목록 / 현재 세션 상태
curl -s 'localhost:8080/sessions/1/events?fromSequence=0'
curl -s localhost:8080/sessions/1/messages
curl -s localhost:8080/sessions/1
```

### 2-3. wscat

```bash
# participantId=1로 연결. lastAppliedSequence 이후 이벤트를 재전송받은 뒤 REPLAY_COMPLETE가 온다
npx wscat -c 'ws://localhost:8080/ws/sessions/1?participantId=1&lastAppliedSequence=0'

# REPLAY_COMPLETE가 온 뒤 입력하면 메시지 전송 → EVENT와 ACK를 받는다
# 같은 줄을 한 번 더 보내면 같은 eventId라 ACK가 duplicate=true로 온다
{"type":"SEND_EVENT","eventId":"3f6c2a8e-5b1d-4c7a-9e2f-8d4b6a1c0e57","eventType":"MESSAGE","payload":{"content":"wscat에서 보냄"}}
```

Ctrl+C로 끊으면 서버가 DISCONNECT 이벤트를 만들고, 다시 연결하면 RECONNECT를 만든다. 메시지 형식과 close 코드는 [websocket.md](docs/websocket.md)에 있다.

---

## 3. 구현 범위

**필수**

| 항목 | 구현 |
|---|---|
| 실시간 메시지 송수신 | raw WebSocket + JSON. REST로 보내도 같은 처리를 거쳐 상대에게 push |
| join / leave | `POST /sessions/{id}/join`, `LEAVE` 이벤트. 정원 2명 |
| presence | 서버가 WebSocket 연결 상태를 보고 DISCONNECT/RECONNECT 이벤트를 생성. 세션 상태 진행중/중단/완료 |
| 이벤트 수집 API | `POST /sessions/{id}/events` (메시지 전송/수정/삭제, 나가기) |
| 중복 이벤트 방지 | 클라이언트 eventId 멱등성. 재시도는 200 + 처음 결과, 내용이 다르면 409 |
| 순서 기준 | session 행 Lock 안에서 발급하는 세션별 sequence |
| 특정 시점 복원 | `GET /sessions/{id}/timeline?at=` 또는 `?sequence=` |

**추가로 구현한 것**

| 항목 | 내용 |
|---|---|
| Snapshot + Replay 복원 | 일정 간격마다 커밋 후 비동기로 Snapshot 생성, 빠진 Snapshot은 안전망 스케줄러가 채움 |
| 비동기 Projection | 현재 메시지 목록을 커밋 후 비동기로 반영. 재시도(지수 백오프), 멱등 반영, 안전망 catch-up |
| 재연결 resume | `lastAppliedSequence` 이후 이벤트를 순서 보장 재전송(재전송 중 실시간 이벤트는 버퍼링) |
| 관측 | key=value 로그, Micrometer 메트릭 (`/actuator/metrics`) |
| 테스트 | 통합·WebSocket·단위·장애 주입 테스트 115개, 결정성(Determinism) 검증 |
| 쿼리 측정 | 이벤트 110만 건으로 핫패스 쿼리 측정 + 재현 스크립트. 측정으로 찾은 병목 개선 |
| 부하 테스트 | k6로 한 세션 집중(Lock 상한) / 여러 세션 분산 측정, 부하 후 정합성 확인 ([load-test.md](docs/load-test.md)) |
| API 문서 | OpenAPI(Swagger). 에러 응답을 `ErrorCode` enum에서 자동 생성 |
| 도구 | 브라우저 테스트 클라이언트, Docker Compose 한 번으로 실행 |

**설계로만 다룬 것** ([design.md](docs/design.md))

| 항목 | 내용 |
|---|---|
| 수평 확장 | 서버 간 이벤트 전파(Redis Pub/Sub), 연결 위치 공유, 스케줄러 분산 락 |
| 비동기 작업 유실 방지 강화 | Transactional Outbox |
| presence 고도화 | heartbeat, 끊김 유예(grace period), 장시간 끊긴 참여자 자동 퇴장 |
| 관측 고도화 | Prometheus + Grafana 알림, 분산 추적(OpenTelemetry) |
| 인증 | 추측 불가능한 참여 토큰 |

---

## 4. 주요 설계 결정

- **이벤트가 유일한 원본**: 모든 변경을 `event`에 append-only로 저장한다. 요청 검증에 쓰는 상태(참여자, 세션 상태)는 같은 트랜잭션에서 동기로 갱신하고, 조회 전용 데이터(메시지 목록, Snapshot)는 커밋 후 비동기로 만든다 ([2장](docs/design.md#2-이벤트-모델)).
- **중복**: 클라이언트가 만든 eventId에 전역 UNIQUE를 건다. 같은 eventId가 오면 저장된 이벤트와 비교해서, 같으면 처음 결과를 돌려주고 다르면 409로 응답한다 ([3장](docs/design.md#3-중복-이벤트-처리)).
- **순서**: 클라이언트 시각이 아니라 서버가 발급한 세션별 sequence를 기준으로 한다. 저장 시각도 DB 시각을 Lock 안에서 기록해서 순서가 뒤집히지 않는다 ([4장](docs/design.md#4-순서-처리)).
- **동시성**: 세션 단위 비관적 Lock으로 직렬화하고, 실시간 push는 커밋 후에만 한다. Lock 대기는 3초로 제한한다 ([5장](docs/design.md#5-트랜잭션과-동시성)).
- **presence**: 끊긴 클라이언트는 이벤트를 보낼 수 없으므로 서버가 연결 상태를 보고 이벤트를 만든다. 참여자당 연결은 하나만 유지한다 ([6장](docs/design.md#6-세션-상태와-presence)).
- **복원**: 시점을 sequence로 바꾼 뒤, 가장 가까운 Snapshot에서 시작해 이후 이벤트를 Replay한다. 이벤트 적용 로직은 순수 함수 하나를 공유한다 ([7장](docs/design.md#7-상태-복원)).
- **비동기 실패**: 실패한 작업을 보관하지 않는다. 원본 이벤트와 결과의 차이를 안전망 스케줄러가 주기적으로 찾아 복구한다 ([8장](docs/design.md#8-비동기-처리)).

---

## 5. 테스트

```bash
# Docker 필요 (Testcontainers가 MariaDB 11.4를 띄운다)
./gradlew test
```

| 종류 | 개수 | 확인하는 것 |
|---|---|---|
| 통합 (Testcontainers MariaDB) | 72 | 멱등성(200/409), 동시 50건 → sequence 1~50 빈틈 없음, 동시 JOIN 3명 → 2명만 성공, 시점 → sequence 변환, Snapshot 안전망, 메시지 목록 catch-up(오래 꺼졌다 켜진 경우 포함), presence와 세션 상태(늦게 도착한 끊김 처리 포함), 요청 형식 검증 |
| WebSocket (실제 포트) | 22 | 실시간 전달, 재연결 재전송 순서, 연결 교체(4000), 검증 실패 close 코드, 끊김 → DISCONNECT/SUSPENDED |
| 단위 | 21 | 이벤트 적용 로직(Reducer), 재전송 중 실시간 이벤트가 끼어들어도 순서 유지, 재전송 버퍼 상한, 재전송 중 종료 이벤트를 보낸 뒤 연결 종료, 예외 → 에러 코드 변환 |

**결정성**: 같은 시점을 Snapshot + Replay로 복원한 결과 == 처음부터 전체 Replay한 결과, 현재 상태 == 마지막 시점 복원 결과

**장애 주입**: 메시지 목록 반영 DB 쓰기 실패 → 이벤트는 저장되고 안전망이 복구 / session 행 Lock 점유 → 3초 후 `SESSION_BUSY` / 커넥션 풀 고갈 → 3초 후 `SERVER_BUSY` / 실시간 push 실패 → 요청은 성공, 상대는 재연결로 수신

---

## 6. 로컬 개발

```bash
# DB만 띄운다
docker compose up -d mariadb

# local 프로필 (Snapshot 간격 1000)
./gradlew bootRun

# demo 프로필 (Snapshot 간격 20)
SPRING_PROFILES_ACTIVE=demo ./gradlew bootRun
```

- JDK 25가 없으면 Gradle toolchain이 자동으로 내려받는다.
- `docker compose up`으로 띄운 `chat-app`이 있으면 8080이 겹치므로 `docker compose stop app` 후 실행한다.

```text
com.creativedigital.chat
├── common/     에러(ProblemDetail), 설정(비동기 스레드 풀, OpenAPI), DB 시각
├── session/    세션·참여자. 상태를 바꾸는 모든 요청의 단일 진입점(SessionService)
├── event/      이벤트 저장소 (중복 확인, 저장, 범위 조회)
├── timeline/   과거 시점 복원 (Reducer, Snapshot, 안전망 스케줄러)
├── message/    현재 메시지 목록 (비동기 Projection, catch-up)
└── realtime/   WebSocket (연결 명단, 순서 보장 재전송, 커밋 후 push)
```

---

## 7. 문서

| 문서 | 내용 |
|---|---|
| [design.md](docs/design.md) | 설계 결정과 근거, 재연결·확장·관측·장애 대응, 한계와 트레이드오프 |
| [erd.md](docs/erd.md) | ERD, DDL, 인덱스 근거, 정규화 선택 |
| [api.md](docs/api.md) · [openapi.json](docs/openapi.json) | REST API |
| [websocket.md](docs/websocket.md) | WebSocket 프로토콜, 재연결 재전송 |
| [query.md](docs/query.md) · [query-bench.sql](docs/query-bench.sql) | 핫패스 쿼리 측정과 재현 스크립트 |
| [load-test.md](docs/load-test.md) | 부하 테스트 결과와 실행 방법 (`load-test/`) |

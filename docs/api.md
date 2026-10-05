# REST API 설계

> 전체 명세(필드 설명, 예시, API별 에러 코드)는 앱 실행 후 Swagger UI(`/swagger-ui/index.html`) 또는 [openapi.json](openapi.json)에서 볼 수 있다. 이 문서는 흐름과 규칙을 요약한다.
> 성공 응답은 공통 래퍼 없이 바로 반환하고, 리스트는 객체 안의 필드로 감싼다. 에러는 RFC 7807 ProblemDetail.

## 1. 엔드포인트 목록

| # | Method | Path | 설명 | 조회 대상 |
|---|---|---|---|---|
| 1 | POST | `/sessions` | 세션 생성 | |
| 2 | POST | `/sessions/{id}/join` | 참여, participantId 발급 | |
| 3 | POST | `/sessions/{id}/events` | 이벤트 수집 (MESSAGE, MESSAGE_EDITED, MESSAGE_DELETED, LEAVE) | |
| 4 | POST | `/sessions/{id}/end` | 세션 종료 | |
| 5 | GET | `/sessions` | 세션 목록 (상태/기간/참여자 이름 필터, 커서 페이징) | session, participant |
| 6 | GET | `/sessions/{id}/timeline` | 특정 시점 상태 복원 (`at` 또는 `sequence`) | snapshot + event (과거) |
| 7 | GET | `/sessions/{id}/events` | sequence 범위 이벤트 조회 (디버깅, 재연결 누락 구간 재요청) | event |
| 8 | POST | `/sessions/{id}/snapshots` | 수동 Snapshot 생성 (현재 last_sequence 기준) | |
| 9 | GET | `/sessions/{id}` | 현재 세션 상태 + 참여자/presence | session, participant (동기 Projection) |
| 10 | GET | `/sessions/{id}/messages` | 현재 메시지 목록 (수정/삭제 반영) | message_projection (비동기 Projection) |

- 과거 상태 = 6번(Replay), 현재 상태 = 9·10번(Projection)

## 2. 멱등성 응답 규칙

| 상황 | 응답 |
|---|---|
| 처음 요청 | 201 (end는 200) |
| 같은 eventId 재시도, 내용 동일 | **200 + 처음과 같은 body** (새로 저장하지 않음) |
| 같은 eventId, 내용 다름 | **409 `EVENT_ID_CONFLICT`** |

- "내용"은 세션, 이벤트 타입, 참여자, 대상 메시지(`targetEventId`), payload를 비교한다. 서버가 발급하는 값(세션 번호, 참여자 번호)은 재시도 요청에 있을 수 없어서 타입별로 비교에서 뺀다
- `occurredAt`(클라이언트 시각)은 비교하지 않는다. 기록용 값이라, 재시도할 때 클라이언트가 시각을 다시 찍어 보내도 같은 요청으로 본다

## 3. 요청/응답

### 1. `POST /sessions`
```json
// 요청
{ "eventId": "uuid" }
// 응답 201
{ "sessionId": 1, "status": "IN_PROGRESS", "sequence": 1, "createdAt": "2026-10-01T14:00:00.123456+09:00" }
```

### 2. `POST /sessions/{id}/join`
```json
// 요청
{ "eventId": "uuid", "displayName": "철수", "occurredAt": "선택" }
// 응답 201
{ "participantId": 7, "sessionId": 1, "sequence": 2, "status": "ACTIVE" }
```

### 3. `POST /sessions/{id}/events`
```json
// 요청
{
  "eventId": "uuid",
  "participantId": 7,
  "type": "MESSAGE",
  "targetEventId": null,
  "payload": { "content": "안녕" },
  "occurredAt": "선택, 없으면 서버 시각"
}
// 응답 201 (GET /events, WS EVENT 메시지와 같은 형태)
{
  "eventId": "uuid", "sessionId": 1, "sequence": 3, "type": "MESSAGE",
  "participantId": 7, "targetEventId": null, "payload": { "content": "안녕" },
  "occurredAt": "2026-10-02T13:45:00+09:00", "createdAt": "2026-10-02T13:45:22.022935+09:00"
}
```

| type | targetEventId | payload | 참여자 조건 |
|---|---|---|---|
| `MESSAGE` | 없음 | `{ "content": "..." }` | ACTIVE |
| `MESSAGE_EDITED` | 대상 메시지 ID (필수) | `{ "content": "..." }` | ACTIVE + 본인 메시지 + 삭제 안 됨 |
| `MESSAGE_DELETED` | 대상 메시지 ID (필수) | `{}` | ACTIVE + 본인 메시지 + 삭제 안 됨 |
| `LEAVE` | 없음 | `{}` | ACTIVE 또는 DISCONNECTED |

- payload는 표의 형식만 받는다. `MESSAGE`/`MESSAGE_EDITED`는 `content` 하나만(공백 불가, 최대 2000자), `MESSAGE_DELETED`/`LEAVE`는 비어 있거나 생략. 다른 키가 있으면 400
- DISCONNECT/RECONNECT는 서버가 WebSocket 연결 상태를 보고 만든다 → 이 API로 보내면 400
- 메시지 ID = MESSAGE 이벤트의 eventId
- 같은 요청을 WebSocket `SEND_EVENT`로 보내도 같은 처리를 거친다 ([websocket.md](websocket.md)).

### 4. `POST /sessions/{id}/end`
```json
// 요청
{ "eventId": "uuid" }
// 응답 200
{ "sessionId": 1, "status": "COMPLETED", "sequence": 10, "endedAt": "..." }
```

### 5. `GET /sessions?status=&from=&to=&participantName=&cursor=&size=`
- `from`/`to`: 세션 생성 시각 범위 (`from` 이상, `to` 미만, 시간대가 붙은 ISO-8601)
- `participantName`: 참여자 이름 정확히 일치
- 정렬: 최신 세션부터 (sessionId 내림차순)
- 커서 페이징: `cursor` = 직전 페이지 마지막 sessionId, `size` 기본 20 / 최대 100
```json
// 응답 200
{
  "sessions": [
    { "sessionId": 3, "status": "IN_PROGRESS", "participantNames": ["철수", "영희"], "createdAt": "...", "endedAt": null }
  ],
  "nextCursor": 3
}
```

### 6. `GET /sessions/{id}/timeline?at=...` 또는 `?sequence=...`
- 둘 중 하나만 허용, 둘 다 / 둘 다 없음 → 400
- `at`은 그 시각까지 서버가 저장한 마지막 이벤트 기준. 세션 생성 이전 시각이면 400, 미래 시각이면 현재 상태
```json
// 응답 200
{
  "sessionId": 1,
  "targetSequence": 42,
  "sessionStatus": "IN_PROGRESS",
  "participants": [
    { "participantId": 7, "displayName": "철수", "status": "ACTIVE" }
  ],
  "messages": [
    { "messageId": "uuid", "senderParticipantId": 7, "content": "안녕하세요", "status": "EDITED", "sequence": 10 }
  ],
  "restoredFrom": { "snapshotSequence": 40, "replayedEventCount": 2 }
}
```
- `messages`는 최근 N개 (설정 `app.snapshot.recent-messages`, 기본 100)
- `restoredFrom`: 어떤 Snapshot에서 몇 개 이벤트를 Replay했는지 (Snapshot이 없으면 `snapshotSequence: null`)

### 7. `GET /sessions/{id}/events?fromSequence=&toSequence=&limit=`
- `fromSequence` 초과 ~ `toSequence` 이하, sequence 오름차순, `limit` 기본 100 / 최대 500
- 시점 기준 조회는 6번 timeline이 담당하므로 이 API는 sequence 기준만 지원
```json
// 응답 200
{
  "events": [
    { "eventId": "uuid", "sessionId": 1, "sequence": 101, "type": "MESSAGE", "participantId": 7, "targetEventId": null, "payload": {}, "occurredAt": "...", "createdAt": "..." }
  ],
  "hasMore": false
}
```

### 8. `POST /sessions/{id}/snapshots`
```json
// 응답 201 (이미 같은 sequence의 Snapshot이 있으면 200)
{ "sessionId": 1, "sequence": 42, "createdAt": "..." }
```

### 9. `GET /sessions/{id}`
```json
// 응답 200
{
  "sessionId": 1, "status": "IN_PROGRESS", "lastSequence": 42, "createdAt": "...", "endedAt": null,
  "participants": [
    { "participantId": 7, "displayName": "철수", "status": "ACTIVE", "joinedAt": "..." }
  ]
}
```

### 10. `GET /sessions/{id}/messages?beforeSequence=&size=`
- `beforeSequence` 미만, sequence 내림차순, `size` 기본 50 / 최대 100
- 비동기 Projection이라 최신 이벤트가 잠깐 늦게 반영될 수 있음 → `projectedSequence`로 반영 위치를 알려줌
```json
// 응답 200
{
  "messages": [
    { "messageId": "uuid", "senderParticipantId": 7, "content": "안녕하세요", "status": "EDITED", "sequence": 10, "sentAt": "...", "updatedAt": "..." }
  ],
  "projectedSequence": 41,
  "hasMore": true
}
```

## 4. 에러

형식: RFC 7807 ProblemDetail (`Content-Type: application/problem+json`) + 확장 속성 `code`

- `code`는 아래 표의 애플리케이션 에러에만 붙는다. 없는 경로(404), 지원하지 않는 HTTP 메서드(405) 같은 스프링 기본 에러는 ProblemDetail 형식이지만 `code`가 없다

```json
{
  "title": "Conflict",
  "status": 409,
  "detail": "세션 정원(2명)이 가득 찼습니다.",
  "instance": "/sessions/1/join",
  "code": "SESSION_FULL"
}
```

클라이언트는 `code`로 분기한다.

| HTTP | code | 상황 |
|---|---|---|
| 400 | `INVALID_REQUEST` | 검증 실패, 형식 오류(`?at=abc` 등), payload에 허용하지 않는 키, timeline 파라미터 조합 오류, DISCONNECT/RECONNECT 타입 전송 |
| 403 | `NOT_MESSAGE_OWNER` | 남의 메시지 수정/삭제 |
| 404 | `SESSION_NOT_FOUND` | 세션 없음 |
| 404 | `PARTICIPANT_NOT_FOUND` | 참여자 없음 또는 다른 세션 소속 |
| 404 | `MESSAGE_NOT_FOUND` | 수정/삭제 대상 메시지 없음 |
| 409 | `EVENT_ID_CONFLICT` | 같은 eventId인데 내용이 다름, 또는 다른 세션에 같은 eventId가 동시에 들어옴 |
| 409 | `SESSION_FULL` | 정원(2명, 끊긴 참여자 포함) 초과 |
| 409 | `SESSION_COMPLETED` | 종료된 세션에 상태를 바꾸는 요청 |
| 409 | `PARTICIPANT_NOT_ACTIVE` | 조건에 맞지 않는 참여자 상태 |
| 409 | `MESSAGE_ALREADY_DELETED` | 삭제된 메시지 수정/삭제 |
| 500 | `INTERNAL_ERROR` | 예상하지 못한 오류 (내부 정보는 응답하지 않고 서버 로그로만) |
| 503 | `SESSION_BUSY` | session 행 Lock 대기 타임아웃(3초). 잠시 후 재시도 |
| 503 | `SERVER_BUSY` | DB 커넥션 획득 대기 타임아웃(3초, 커넥션 풀 고갈 등). 잠시 후 재시도 |

- 시각은 모두 `+09:00`이 붙은 ISO-8601로 응답한다. 요청의 `occurredAt`은 어느 시간대로 보내도 KST로 변환해 저장한다.

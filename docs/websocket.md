# WebSocket 프로토콜

> 구현은 단일 인스턴스 기준이며, 다중 인스턴스 확장은 [설계 문서 10장](design.md#10-수평-확장)에서 다룬다. presence와 연결 교체 규칙의 근거는 [설계 문서 6장](design.md#6-세션-상태와-presence).

## 1. 방식

raw WebSocket + JSON 메시지 (STOMP 미사용)

- 1:1 채팅이라 topic 구독(pub/sub)이 필요 없음
- 재연결 시 replay 순서 보장을 소켓 하나에서 직접 제어하기 쉬움
- 브라우저 기본 `WebSocket` API, `wscat`으로 바로 테스트 가능
- STOMP 등 다른 방식과의 비교: [설계 문서 13장](design.md#13-통신-방식-비교)

## 2. 연결

```text
ws://{host}/ws/sessions/{sessionId}?participantId={participantId}&lastAppliedSequence={n}
```

- `lastAppliedSequence` 생략 시 0 (처음부터 replay)
- REST `POST /sessions/{id}/join`으로 participantId를 먼저 발급받아야 함
- 검증에 실패해도 일단 연결을 받은 뒤 `ERROR` 메시지를 보내고 커스텀 close 코드로 종료
  - 브라우저 WebSocket은 handshake 거절 시 HTTP 상태 코드를 읽을 수 없기 때문

| close 코드 | 의미 |
|---|---|
| 1000 | 세션 종료(SESSION_ENDED) 또는 본인 LEAVE로 정상 종료 |
| 4000 | 같은 참여자의 새 연결로 대체됨. 클라이언트는 **자동 재연결하지 않음** (다른 곳에서 접속한 것이므로, 재연결하면 서로 밀어내는 핑퐁이 생김) |
| 4400 | 잘못된 요청 (participantId 없음 등) |
| 4404 | 세션/참여자 없음, 다른 세션 소속 |
| 4409 | 참여자가 이미 LEFT, 또는 세션 COMPLETED |
| 1009 | 메시지가 크기 제한(Tomcat 기본 8192자)을 넘음 |
| 1011 | 서버 내부 오류 |
| 1013 | 재전송 중 쌓인 실시간 이벤트가 버퍼 상한(1,000개)을 넘음. lastAppliedSequence로 다시 연결하면 이어서 받음. 버퍼가 넘친 순간 세션 종료나 본인 LEAVE가 겹쳤다면 재연결이 4409로 거부된다. 이때 클라이언트는 REST `GET /sessions/{id}/events`로 남은 이벤트를 받아야 한다 (클라이언트가 따라야 할 복구 절차. 브라우저 테스트 클라이언트는 자동으로 하지 않음) |

## 3. 연결 직후 처리 — 순서 보장 replay

목표: 재연결 시 **누락 없이, 순서가 뒤집히지 않게** 이벤트를 전달

```text
⓪ 검증 (세션/참여자 존재, 이 세션 소속, LEFT 아님, 세션 종료 아님). 실패하면 ERROR + close 코드
① 연결을 맵에 "replay 중" 상태로 등록 → 이후 실시간 이벤트는 바로 보내지 않고 버퍼에 쌓음
② 참여자가 DISCONNECTED였으면 RECONNECT 이벤트 생성
③ CONNECTED 전송
④ lastAppliedSequence 초과 이벤트를 DB에서 sequence 순으로 조회해 EVENT로 전송 (500개 단위)
⑤ REPLAY_COMPLETE 전송
⑥ 버퍼 중 replay로 보낸 마지막 sequence보다 큰 이벤트만 전송
⑦ "실시간" 상태로 전환 → 이후 이벤트는 즉시 전송
```

- ⓪을 ①보다 먼저 하는 이유: 등록하면 같은 참여자의 기존 연결이 끊기므로, 남의 participantId로 접속해 멀쩡한 연결을 끊지 못하게 함
- ①을 ②보다 먼저 하는 이유: 옛 연결의 끊김 처리가 언제 끼어들어도 마지막엔 ACTIVE로 끝나게 함
  - 등록 전에 끼어들면 → DISCONNECT가 찍히지만 ②가 RECONNECT로 되돌림
  - 등록 후에 끼어들면 → 맵에 새 연결이 있어서 옛 연결 종료는 무시됨
  - ②를 먼저 하면 그 사이에 끼어든 DISCONNECT를 되돌릴 단계가 없어서 접속 중인데 DISCONNECTED로 남음
- ②는 Lock 안에서 다시 검증함. ⓪ 이후 상태가 바뀌었으면(그 사이 LEAVE 등) 맵에서 빼고 ERROR + close
- ①을 replay 조회보다 먼저 하는 이유: 조회 후에 등록하면 그 사이 커밋된 이벤트를 놓침
- 버퍼링하는 이유: replay 도중 실시간 이벤트가 먼저 도착해 순서가 뒤집히는 것을 방지
- replay와 버퍼가 겹치는 구간은 ⑥에서 sequence로 걸러냄
- 버퍼는 sequence 순으로 정렬해서 보냄 (서로 다른 요청의 push는 도착 순서가 뒤바뀔 수 있으므로)
- replay 조회 중 오류가 나면 연결을 1011로 끊음 (버퍼가 계속 쌓이지 않게). 클라이언트는 재연결
- 버퍼 상한 1,000개: replay가 오래 걸리는 동안 실시간 이벤트가 계속 쌓이면 메모리가 끝없이 늘 수 있어서, 넘치면 버퍼를 비우고 1013으로 끊음. replay 루프도 DB 조회를 멈춤. 클라이언트는 lastAppliedSequence로 재연결해 이어 받으므로 잃는 이벤트는 없음
- 클라이언트도 sequence 기준 중복 무시와 gap 감지를 수행 (이중 방어). gap이 생기면 REST `GET /sessions/{id}/events`로 채우거나 재연결

### 연결 종료 처리

- 맵의 현재 연결과 같을 때만 맵에서 제거하고 DISCONNECT 생성 (`remove(participantId, connection)`). 새 연결로 교체된 옛 연결의 종료는 무시
- DISCONNECT는 참여자가 ACTIVE일 때만 생성 (LEAVE/세션 종료로 이미 LEFT면 생성 안 함). Lock 안에서 확인하므로 같은 끊김이 두 번 처리돼도 하나만 생김
- Lock 안에서 같은 참여자의 연결이 맵에 다시 생겼는지도 확인하고, 있으면 DISCONNECT를 만들지 않음. 옛 연결이 맵에서 빠진 직후 새 연결의 등록과 접속 처리(②)가 먼저 끝난 경우, ②는 아직 ACTIVE라 아무것도 하지 않았으므로 여기서 DISCONNECTED로 바꾸면 접속 중인데 오프라인으로 남기 때문
- DISCONNECT 저장이 실패하면 로그만 남김 → 참여자가 ACTIVE로 남는 한계. 재연결하면 정상으로 돌아오며, 운영에서는 heartbeat + 타임아웃으로 해결 (설계 문서)

구현 스케치:

```java
class ClientConnection {
    private final WebSocketSession session;   // ConcurrentWebSocketSessionDecorator로 감쌈
    private boolean replaying = true;
    private final List<EventMessage> buffer = new ArrayList<>();
    private long lastSentSequence;
    private boolean bufferOverflowed;
    private CloseStatus pendingClose;

    synchronized void push(EventMessage event) {
        if (bufferOverflowed) return;
        if (!replaying) {
            send(event);
            return;
        }
        if (buffer.size() >= MAX_BUFFERED_EVENTS) {   // 1,000개
            bufferOverflowed = true;
            buffer.clear();
            close(SERVICE_OVERLOAD);                   // 1013, 클라이언트는 재연결
            return;
        }
        buffer.add(event);
    }

    synchronized void finishReplay() {
        if (bufferOverflowed) return;
        buffer.stream()
              .filter(e -> e.sequence() > lastSentSequence)
              .forEach(this::send);
        buffer.clear();
        replaying = false;
        if (pendingClose != null) close(pendingClose);
    }

    // LEAVE, SESSION_ENDED 뒤 연결 종료. 재전송 중이면 버퍼를 보낸 뒤 닫도록 예약만 한다
    synchronized void closeAfterPendingEvents(CloseStatus status) {
        if (replaying && !bufferOverflowed) {
            pendingClose = status;
            return;
        }
        close(status);
    }
}
```

## 4. 메시지 형식

공통: `{ "type": "...", ...필드 }`

### 클라이언트 → 서버

| type | 필드 | 설명 |
|---|---|---|
| `SEND_EVENT` | `eventId, eventType, targetEventId, payload, occurredAt` | REST `POST /sessions/{id}/events`와 같은 서비스로 처리. eventType은 MESSAGE / MESSAGE_EDITED / MESSAGE_DELETED / LEAVE |

### 서버 → 클라이언트

| type | 필드 | 설명 |
|---|---|---|
| `CONNECTED` | `sessionId, participantId, replayFromSequence` | 연결 성공, replay 시작 |
| `EVENT` | `event` (REST 이벤트 응답과 같은 형태: `eventId, sessionId, sequence, type, participantId, targetEventId, payload, occurredAt, createdAt`) | replay와 실시간 공통 |
| `REPLAY_COMPLETE` | `lastSequence` | replay 종료, 이후 실시간 |
| `ACK` | `eventId, sequence, duplicate` | 보낸 SEND_EVENT 성공. `duplicate=true`면 재시도로 기존 결과 반환 |
| `ERROR` | `eventId(nullable), code, message` | 실패. 에러 코드는 REST와 동일 (`docs/api.md`) |

- presence 전용 메시지는 없음. DISCONNECT/RECONNECT/LEAVE가 EVENT로 전달됨 (이벤트가 유일한 출처)
- 발신자는 ACK와 EVENT를 모두 받음. ACK = 요청 성공 확인, 화면 반영은 EVENT로만 (내 메시지와 상대 메시지를 같은 방식으로 sequence 순서대로 적용)
  - ACK와 EVENT의 도착 순서는 보장하지 않음 (EVENT는 커밋 직후 push, ACK는 서비스 호출이 끝난 뒤 전송)
- `SEND_EVENT`에는 participantId가 없음. 연결할 때 검증한 참여자로만 보낼 수 있음 (남의 이름으로 전송 방지)
- 세션 종료 시 SESSION_ENDED EVENT를 push한 뒤 모든 연결을 1000으로 종료
- 재전송 중인 연결은 종료 EVENT가 아직 버퍼에 있으므로 바로 닫지 않고, 재전송이 끝나 버퍼를 보낸 뒤 닫음. 바로 닫으면 종료 EVENT가 전달되지 않고, 종료 뒤에는 재연결도 거부돼 다시 받을 방법이 없음
- LEAVE 시 LEAVE EVENT를 push한 뒤 나간 사람의 연결만 1000으로 종료. 연결이 먼저 닫혀서 나간 사람은 ACK를 못 받을 수 있음 (자기 LEAVE EVENT가 성공 확인 역할)
- 서로 다른 요청의 push는 각자의 스레드에서 실행돼 sequence 순서와 뒤바뀌어 도착할 수 있음 → 클라이언트가 sequence로 정렬하고 빈틈을 확인

### 예시 흐름 (재연결 후 메시지 전송)

```text
철수 → 서버   (연결) ws://.../sessions/1?participantId=7&lastAppliedSequence=100
서버 → 철수   CONNECTED        { replayFromSequence: 101 }
서버 → 철수   EVENT            { event: { sequence: 101, ... } }
서버 → 철수   EVENT            { event: { sequence: 102, ... } }
서버 → 철수   REPLAY_COMPLETE  { lastSequence: 102 }
철수 → 서버   SEND_EVENT       { eventId: "abc", eventType: "MESSAGE", payload: { content: "안녕" } }
서버 → 철수   EVENT            { event: { sequence: 103, type: "MESSAGE", ... } }
서버 → 철수   ACK              { eventId: "abc", sequence: 103, duplicate: false }
서버 → 영희   EVENT            { event: { sequence: 103, type: "MESSAGE", ... } }
```

package com.creativedigital.chat.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import com.creativedigital.chat.TestcontainersConfiguration;
import com.creativedigital.chat.event.entity.EventType;
import com.creativedigital.chat.realtime.service.EventBroadcaster;
import com.creativedigital.chat.session.dto.ClientEventCommand;
import com.creativedigital.chat.session.dto.EventRangeRequest;
import com.creativedigital.chat.session.dto.JoinCommand;
import com.creativedigital.chat.session.entity.ParticipantStatus;
import com.creativedigital.chat.session.entity.SessionStatus;
import com.creativedigital.chat.session.service.SessionService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 실제 서버 포트를 띄우고 테스트용 WS 클라이언트로 접속해 확인한다.
 * 보낸 사람의 ACK와 EVENT는 도착 순서를 정하지 않으므로(EVENT는 커밋 직후, ACK는 서비스 호출이 끝난 뒤) 순서 없이 확인한다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "app.snapshot.scheduler.enabled=false",
        "app.message-projection.scheduler.enabled=false"
    }
)
class ChatWebSocketTest {

    private static final long TIMEOUT_SECONDS = 5;
    private static final long NO_MESSAGE_WAIT_MILLIS = 300;

    @LocalServerPort
    private int port;

    @Autowired
    private SessionService sessionService;

    @Autowired
    private JsonMapper jsonMapper;

    // 평소엔 진짜로 push하고, 장애 주입 테스트에서만 실패시킨다 (테스트가 끝나면 자동 리셋)
    @MockitoSpyBean
    private EventBroadcaster eventBroadcaster;

    // ===== 실시간 전달 =====

    @Test
    void lastAppliedSequence_없이_연결하면_처음부터_재전송받고_REPLAY_COMPLETE를_받는다() throws Exception {
        Room room = createRoom();

        TestClient client = connect(room.sessionId(), room.first());

        JsonNode connected = client.next();
        assertThat(connected.path("type").asString()).isEqualTo("CONNECTED");
        assertThat(connected.path("participantId").asLong()).isEqualTo(room.first());
        assertThat(connected.path("replayFromSequence").asLong()).isEqualTo(1);
        assertThat(client.nextEventSequences(3)).containsExactly(1L, 2L, 3L);
        assertReplayComplete(client.next(), 3);
    }

    @Test
    void 재연결하면_lastAppliedSequence_이후_이벤트만_순서대로_재전송받는다() throws Exception {
        Room room = createRoom();
        appendEvent(room.sessionId(), room.second(), EventType.MESSAGE, "4번");
        appendEvent(room.sessionId(), room.second(), EventType.MESSAGE, "5번");
        appendEvent(room.sessionId(), room.second(), EventType.MESSAGE, "6번");

        TestClient client = connect(uri(room.sessionId(), room.first(), 4L));

        assertThat(client.next().path("replayFromSequence").asLong()).isEqualTo(5);
        assertThat(client.nextEventSequences(2)).containsExactly(5L, 6L);
        assertReplayComplete(client.next(), 6);
    }

    @Test
    void WS로_보낸_메시지는_보낸_사람에게_ACK와_EVENT_상대에게_EVENT로_전달된다() throws Exception {
        Room room = createRoom();
        TestClient sender = connectAndSkipConnected(room.sessionId(), room.first());
        TestClient receiver = connectAndSkipConnected(room.sessionId(), room.second());
        String eventId = newEventId();

        sender.send(sendEvent(eventId, "MESSAGE", null, Map.of("content", "안녕")));

        List<JsonNode> senderMessages = List.of(sender.next(), sender.next());
        assertThat(senderMessages).extracting(message -> message.path("type").asString())
            .containsExactlyInAnyOrder("ACK", "EVENT");
        JsonNode ack = findByType(senderMessages, "ACK");
        assertThat(ack.path("eventId").asString()).isEqualTo(eventId);
        assertThat(ack.path("duplicate").asBoolean()).isFalse();

        JsonNode received = receiver.next();
        assertThat(received.path("type").asString()).isEqualTo("EVENT");
        assertThat(received.path("event").path("eventId").asString()).isEqualTo(eventId);
        assertThat(received.path("event").path("payload").path("content").asString()).isEqualTo("안녕");
        assertThat(received.path("event").path("sequence").asLong()).isEqualTo(ack.path("sequence").asLong());
    }

    @Test
    void REST_경로로_저장된_이벤트도_WS로_push된다() throws Exception {
        Room room = createRoom();
        TestClient receiver = connectAndSkipConnected(room.sessionId(), room.second());

        // REST Controller가 호출하는 것과 같은 서비스 메서드
        String eventId = appendEvent(room.sessionId(), room.first(), EventType.MESSAGE, "REST로 보냄");

        JsonNode received = receiver.next();
        assertThat(received.path("type").asString()).isEqualTo("EVENT");
        assertThat(received.path("event").path("eventId").asString()).isEqualTo(eventId);
    }

    @Test
    void 같은_eventId로_다시_보내면_duplicate_ACK를_받고_EVENT는_다시_오지_않는다() throws Exception {
        Room room = createRoom();
        TestClient sender = connectAndSkipConnected(room.sessionId(), room.first());
        String message = sendEvent(newEventId(), "MESSAGE", null, Map.of("content", "안녕"));
        sender.send(message);
        sender.next();
        sender.next();

        sender.send(message);

        JsonNode ack = sender.next();
        assertThat(ack.path("type").asString()).isEqualTo("ACK");
        assertThat(ack.path("duplicate").asBoolean()).isTrue();
        sender.assertNoMessage();
    }

    // ===== 요청 실패는 보낸 사람에게만 =====

    @Test
    void 남의_메시지를_수정하면_보낸_사람에게만_ERROR가_간다() throws Exception {
        Room room = createRoom();
        TestClient owner = connectAndSkipConnected(room.sessionId(), room.first());
        TestClient other = connectAndSkipConnected(room.sessionId(), room.second());
        String messageId = newEventId();
        owner.send(sendEvent(messageId, "MESSAGE", null, Map.of("content", "내 메시지")));
        owner.next();
        owner.next();
        other.next();
        String editEventId = newEventId();

        other.send(sendEvent(editEventId, "MESSAGE_EDITED", messageId, Map.of("content", "몰래 수정")));

        JsonNode error = other.next();
        assertThat(error.path("type").asString()).isEqualTo("ERROR");
        assertThat(error.path("code").asString()).isEqualTo("NOT_MESSAGE_OWNER");
        assertThat(error.path("eventId").asString()).isEqualTo(editEventId);
        owner.assertNoMessage();
    }

    @Test
    void 형식이_잘못된_메시지는_INVALID_REQUEST() throws Exception {
        Room room = createRoom();
        TestClient client = connectAndSkipConnected(room.sessionId(), room.first());

        client.send("이건 JSON이 아님");
        client.send(sendEvent("uuid-아님", "MESSAGE", null, Map.of("content", "안녕")));

        assertThat(client.next().path("code").asString()).isEqualTo("INVALID_REQUEST");
        assertThat(client.next().path("code").asString()).isEqualTo("INVALID_REQUEST");
    }

    @Test
    void 서버가_만드는_이벤트_타입은_보낼_수_없다() throws Exception {
        Room room = createRoom();
        TestClient client = connectAndSkipConnected(room.sessionId(), room.first());

        client.send(sendEvent(newEventId(), "DISCONNECT", null, Map.of()));

        assertThat(client.next().path("code").asString()).isEqualTo("INVALID_REQUEST");
    }

    // ===== 연결 검증 =====

    @Test
    void participantId가_없으면_ERROR_후_4400으로_끊긴다() throws Exception {
        Room room = createRoom();

        TestClient client = connect(uri(room.sessionId(), null));

        assertThat(client.next().path("code").asString()).isEqualTo("INVALID_REQUEST");
        assertThat(client.awaitClose().getCode()).isEqualTo(4400);
    }

    @Test
    void 없는_참여자면_4404로_끊긴다() throws Exception {
        Room room = createRoom();

        TestClient client = connect(room.sessionId(), Long.MAX_VALUE);

        assertThat(client.next().path("code").asString()).isEqualTo("PARTICIPANT_NOT_FOUND");
        assertThat(client.awaitClose().getCode()).isEqualTo(4404);
    }

    @Test
    void 다른_세션의_참여자면_4404로_끊긴다() throws Exception {
        Room room = createRoom();
        Room otherRoom = createRoom();

        TestClient client = connect(room.sessionId(), otherRoom.first());

        assertThat(client.next().path("code").asString()).isEqualTo("PARTICIPANT_NOT_FOUND");
        assertThat(client.awaitClose().getCode()).isEqualTo(4404);
    }

    @Test
    void 이미_나간_참여자면_4409로_끊긴다() throws Exception {
        Room room = createRoom();
        appendEvent(room.sessionId(), room.first(), EventType.LEAVE, null);

        TestClient client = connect(room.sessionId(), room.first());

        assertThat(client.next().path("code").asString()).isEqualTo("PARTICIPANT_NOT_ACTIVE");
        assertThat(client.awaitClose().getCode()).isEqualTo(4409);
    }

    @Test
    void 종료된_세션이면_4409로_끊긴다() throws Exception {
        Room room = createRoom();
        sessionService.endSession(room.sessionId(), newEventId());

        TestClient client = connect(room.sessionId(), room.second());

        assertThat(client.next().path("code").asString()).isEqualTo("SESSION_COMPLETED");
        assertThat(client.awaitClose().getCode()).isEqualTo(4409);
    }

    // ===== 연결 종료 =====

    @Test
    void 같은_참여자가_새로_연결하면_기존_연결은_4000으로_끊기고_새_연결로_받는다() throws Exception {
        Room room = createRoom();
        TestClient oldClient = connectAndSkipConnected(room.sessionId(), room.first());

        TestClient newClient = connectAndSkipConnected(room.sessionId(), room.first());

        assertThat(oldClient.awaitClose().getCode()).isEqualTo(4000);
        String eventId = appendEvent(room.sessionId(), room.second(), EventType.MESSAGE, "새 연결로 와야 함");
        assertThat(newClient.next().path("event").path("eventId").asString()).isEqualTo(eventId);
    }

    @Test
    void 세션이_종료되면_SESSION_ENDED를_받고_1000으로_끊긴다() throws Exception {
        Room room = createRoom();
        TestClient first = connectAndSkipConnected(room.sessionId(), room.first());
        TestClient second = connectAndSkipConnected(room.sessionId(), room.second());

        sessionService.endSession(room.sessionId(), newEventId());

        for (TestClient client : List.of(first, second)) {
            assertThat(client.next().path("event").path("type").asString()).isEqualTo("SESSION_ENDED");
            assertThat(client.awaitClose().getCode()).isEqualTo(1000);
        }
    }

    @Test
    void LEAVE하면_나간_사람만_1000으로_끊기고_상대는_연결이_유지된다() throws Exception {
        Room room = createRoom();
        TestClient leaver = connectAndSkipConnected(room.sessionId(), room.first());
        TestClient stayer = connectAndSkipConnected(room.sessionId(), room.second());

        leaver.send(sendEvent(newEventId(), "LEAVE", null, null));

        assertThat(leaver.awaitClose().getCode()).isEqualTo(1000);
        assertThat(stayer.next().path("event").path("type").asString()).isEqualTo("LEAVE");
        assertThat(stayer.isOpen()).isTrue();
    }

    @Test
    void 메시지가_크기_제한을_넘으면_1009로_끊긴다() throws Exception {
        Room room = createRoom();
        TestClient client = connectAndSkipConnected(room.sessionId(), room.first());

        client.send("a".repeat(10_000));

        assertThat(client.awaitClose().getCode()).isEqualTo(CloseStatus.TOO_BIG_TO_PROCESS.getCode());
    }

    // ===== 끊김 / 재연결 (presence) =====

    @Test
    void 끊기면_상대방이_DISCONNECT를_받고_재연결하면_RECONNECT를_받는다() throws Exception {
        Room room = createRoom();
        TestClient leaver = connectAndSkipConnected(room.sessionId(), room.first());
        TestClient stayer = connectAndSkipConnected(room.sessionId(), room.second());

        leaver.closeByClient();

        JsonNode disconnect = stayer.next().path("event");
        assertThat(disconnect.path("type").asString()).isEqualTo("DISCONNECT");
        assertThat(disconnect.path("participantId").asLong()).isEqualTo(room.first());

        // 3번(영희 JOIN)까지 받은 상태로 재연결 → 4번 DISCONNECT, 5번 RECONNECT를 재전송받는다
        TestClient returned = connect(uri(room.sessionId(), room.first(), 3L));

        JsonNode reconnect = stayer.next().path("event");
        assertThat(reconnect.path("type").asString()).isEqualTo("RECONNECT");
        assertThat(reconnect.path("participantId").asLong()).isEqualTo(room.first());
        assertThat(returned.next().path("type").asString()).isEqualTo("CONNECTED");
        assertThat(returned.next().path("event").path("type").asString()).isEqualTo("DISCONNECT");
        assertThat(returned.next().path("event").path("type").asString()).isEqualTo("RECONNECT");
        assertReplayComplete(returned.next(), 5);
    }

    @Test
    void 혼자_있는_방에서_끊기면_SUSPENDED_재연결하면_IN_PROGRESS가_된다() throws Exception {
        Long sessionId = sessionService.createSession(newEventId()).event().sessionId();
        Long participantId = sessionService.join(sessionId, new JoinCommand(newEventId(), "철수", null))
            .event()
            .participantId();
        TestClient client = connectAndSkipConnected(sessionId, participantId);

        client.closeByClient();
        await().atMost(Duration.ofSeconds(TIMEOUT_SECONDS))
            .until(() -> sessionService.getSession(sessionId).status() == SessionStatus.SUSPENDED);

        connectAndSkipConnected(sessionId, participantId);
        assertThat(sessionService.getSession(sessionId).status()).isEqualTo(SessionStatus.IN_PROGRESS);
        assertThat(participantStatus(sessionId, participantId)).isEqualTo(ParticipantStatus.ACTIVE);
    }

    @Test
    void 새_연결로_교체된_옛_연결이_끊겨도_DISCONNECT는_생기지_않는다() throws Exception {
        Room room = createRoom();
        TestClient stayer = connectAndSkipConnected(room.sessionId(), room.second());
        TestClient oldClient = connectAndSkipConnected(room.sessionId(), room.first());

        connectAndSkipConnected(room.sessionId(), room.first());

        assertThat(oldClient.awaitClose().getCode()).isEqualTo(4000);
        stayer.assertNoMessage();
        assertThat(participantStatus(room.sessionId(), room.first())).isEqualTo(ParticipantStatus.ACTIVE);
        assertThat(lastEventType(room.sessionId())).isEqualTo("JOIN");
    }

    @Test
    void 세션_종료로_닫히면_DISCONNECT는_생기지_않는다() throws Exception {
        Room room = createRoom();
        TestClient client = connectAndSkipConnected(room.sessionId(), room.first());

        sessionService.endSession(room.sessionId(), newEventId());

        assertThat(client.awaitClose().getCode()).isEqualTo(1000);
        // 닫힘 처리(afterConnectionClosed)가 끝날 시간을 준 뒤에도 마지막 이벤트가 SESSION_ENDED인지 확인
        Thread.sleep(NO_MESSAGE_WAIT_MILLIS);
        assertThat(lastEventType(room.sessionId())).isEqualTo("SESSION_ENDED");
    }

    // ===== 장애 주입 =====

    /**
     * 커밋 후 실시간 push가 통째로 실패하는 상황.
     * push는 best-effort라 보낸 사람의 요청은 성공하고, 못 받은 사람은 재연결하면 놓친 이벤트를 재전송받는다.
     */
    @Test
    void 장애_push가_실패해도_요청은_성공하고_재연결하면_놓친_이벤트를_재전송받는다() throws Exception {
        Room room = createRoom();
        TestClient receiver = connectAndSkipConnected(room.sessionId(), room.second());
        doThrow(new RuntimeException("주입된 장애")).when(eventBroadcaster).onEventAppended(any());

        String messageId = appendEvent(room.sessionId(), room.first(), EventType.MESSAGE, "안녕");

        receiver.assertNoMessage();
        reset(eventBroadcaster);

        // 3번(영희 JOIN)까지 받은 상태로 재연결 → 놓친 4번 MESSAGE를 재전송받는다
        TestClient returned = connect(uri(room.sessionId(), room.second(), 3L));
        assertThat(returned.next().path("type").asString()).isEqualTo("CONNECTED");
        JsonNode missed = returned.next().path("event");
        assertThat(missed.path("eventId").asString()).isEqualTo(messageId);
        assertThat(missed.path("sequence").asLong()).isEqualTo(4);
        assertReplayComplete(returned.next(), 4);
    }

    // sequence 1: 생성, 2: 철수 JOIN, 3: 영희 JOIN
    private Room createRoom() {
        Long sessionId = sessionService.createSession(newEventId()).event().sessionId();
        Long first = sessionService.join(sessionId, new JoinCommand(newEventId(), "철수", null)).event().participantId();
        Long second = sessionService.join(sessionId, new JoinCommand(newEventId(), "영희", null)).event().participantId();
        return new Room(sessionId, first, second);
    }

    private String appendEvent(
        Long sessionId,
        Long participantId,
        EventType eventType,
        String content
    ) {
        String eventId = newEventId();
        sessionService.appendClientEvent(
            sessionId,
            new ClientEventCommand(
                eventId,
                participantId,
                eventType,
                null,
                content != null ? jsonMapper.createObjectNode().put("content", content) : null,
                null
            ));
        return eventId;
    }

    private String sendEvent(
        String eventId,
        String eventType,
        String targetEventId,
        Map<String, Object> payload
    ) {
        // targetEventId, payload가 null일 수 있어서 Map.of 대신 HashMap을 쓴다
        Map<String, Object> message = new HashMap<>();
        message.put("type", "SEND_EVENT");
        message.put("eventId", eventId);
        message.put("eventType", eventType);
        message.put("targetEventId", targetEventId);
        message.put("payload", payload);
        return jsonMapper.writeValueAsString(message);
    }

    // 현재 마지막 이벤트까지 받은 상태로 연결해서 CONNECTED와 REPLAY_COMPLETE만 받고 넘어간다
    private TestClient connectAndSkipConnected(Long sessionId, Long participantId) throws Exception {
        long lastSequence = sessionService.getSession(sessionId).lastSequence();
        TestClient client = connect(uri(sessionId, participantId, lastSequence));
        assertThat(client.next().path("type").asString()).isEqualTo("CONNECTED");
        JsonNode next = client.next();
        // 끊겼던 참여자면 재연결하면서 만든 RECONNECT가 먼저 재전송된다
        while (next.path("type").asString().equals("EVENT")) {
            next = client.next();
        }
        assertThat(next.path("type").asString()).isEqualTo("REPLAY_COMPLETE");
        return client;
    }

    private TestClient connect(Long sessionId, Long participantId) throws Exception {
        return connect(uri(sessionId, participantId));
    }

    private TestClient connect(String uri) throws Exception {
        TestClient client = new TestClient(jsonMapper);
        client.session = new StandardWebSocketClient().execute(client, uri).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        return client;
    }

    private String uri(Long sessionId, Long participantId) {
        return uri(sessionId, participantId, null);
    }

    private String uri(
        Long sessionId,
        Long participantId,
        Long lastAppliedSequence
    ) {
        String base = "ws://localhost:" + port + "/ws/sessions/" + sessionId;
        if (participantId == null) return base;
        String withParticipant = base + "?participantId=" + participantId;
        return lastAppliedSequence != null ? withParticipant + "&lastAppliedSequence=" + lastAppliedSequence : withParticipant;
    }

    private void assertReplayComplete(
        JsonNode message,
        long lastSequence
    ) {
        assertThat(message.path("type").asString()).isEqualTo("REPLAY_COMPLETE");
        assertThat(message.path("lastSequence").asLong()).isEqualTo(lastSequence);
    }

    private ParticipantStatus participantStatus(
        Long sessionId,
        Long participantId
    ) {
        return sessionService.getSession(sessionId).participants().stream()
            .filter(participant -> participant.participantId().equals(participantId))
            .findFirst()
            .orElseThrow()
            .status();
    }

    private String lastEventType(Long sessionId) {
        long lastSequence = sessionService.getSession(sessionId).lastSequence();
        return sessionService.getEvents(sessionId, new EventRangeRequest(lastSequence - 1, lastSequence, 1))
            .events()
            .getFirst()
            .type()
            .name();
    }

    private JsonNode findByType(List<JsonNode> messages, String type) {
        return messages.stream()
            .filter(message -> message.path("type").asString().equals(type))
            .findFirst()
            .orElseThrow();
    }

    private String newEventId() {
        return UUID.randomUUID().toString();
    }

    private record Room(
        Long sessionId,
        Long first,
        Long second
    ) {

    }

    /**
     * 받은 메시지를 큐에 쌓아두고 테스트가 순서대로 꺼내 확인하는 클라이언트.
     */
    private static class TestClient extends TextWebSocketHandler {

        private final BlockingQueue<JsonNode> messages = new LinkedBlockingQueue<>();
        private final CompletableFuture<CloseStatus> closed = new CompletableFuture<>();
        private final JsonMapper jsonMapper;
        private WebSocketSession session;

        private TestClient(JsonMapper jsonMapper) {
            this.jsonMapper = jsonMapper;
        }

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            messages.add(jsonMapper.readTree(message.getPayload()));
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
            closed.complete(status);
        }

        private void send(String text) throws Exception {
            session.sendMessage(new TextMessage(text));
        }

        // 클라이언트가 먼저 연결을 끊는다 (앱/탭을 닫은 상황)
        private void closeByClient() throws Exception {
            session.close();
        }

        private List<Long> nextEventSequences(int count) throws InterruptedException {
            List<Long> sequences = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                JsonNode message = next();
                assertThat(message.path("type").asString()).isEqualTo("EVENT");
                sequences.add(message.path("event").path("sequence").asLong());
            }
            return sequences;
        }

        private JsonNode next() throws InterruptedException {
            JsonNode message = messages.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertThat(message).as("메시지를 받지 못했습니다").isNotNull();
            return message;
        }

        // 잠깐 기다려도 아무 메시지가 오지 않는지 확인한다
        private void assertNoMessage() throws InterruptedException {
            assertThat(messages.poll(NO_MESSAGE_WAIT_MILLIS, TimeUnit.MILLISECONDS)).isNull();
        }

        private CloseStatus awaitClose() throws Exception {
            return closed.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        private boolean isOpen() {
            return session.isOpen();
        }

    }

}

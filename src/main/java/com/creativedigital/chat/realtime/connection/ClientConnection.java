package com.creativedigital.chat.realtime.connection;

import com.creativedigital.chat.realtime.dto.EventMessage;
import com.creativedigital.chat.realtime.dto.ReplayCompleteMessage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.SessionLimitExceededException;
import tools.jackson.databind.json.JsonMapper;

/**
 * 참여자 한 명의 WS 연결.
 * 요청 스레드(커밋 후 push)와 WS 스레드(ACK/ERROR, 재전송)가 동시에 보낼 수 있어서 ConcurrentWebSocketSessionDecorator로 감싼다.
 * 전송이 밀리는 느린 클라이언트는 제한(시간/버퍼)을 넘으면 연결을 끊는다. 끊긴 클라이언트는 재연결해서 놓친 이벤트를 받는다.
 *
 * 놓친 이벤트 재전송 중에는 실시간 EVENT를 바로 보내지 않고 모아뒀다가, 재전송이 끝나면 보낸다.
 * - 모아두는 이유: 재전송(DB 조회) 도중 실시간 EVENT가 먼저 나가면 클라이언트가 받는 순서가 뒤바뀐다
 * - 겹치는 이벤트: 재전송 조회에도 잡히고 실시간으로도 온 이벤트는 이미 보낸 번호보다 큰 것만 보내서 걸러낸다
 * - 상자 크기 제한: 재전송이 오래 걸리는 동안 새 이벤트가 계속 오면 상자가 끝없이 커질 수 있다.
 *   MAX_BUFFERED_EVENTS를 넘으면 상자를 비우고 연결을 끊는다. 클라이언트는 마지막으로 받은 번호로 재연결해서 이어 받으므로 이벤트를 잃지 않는다
 * 상자(buffer)와 상태를 커밋 후 push 스레드와 WS 스레드가 같이 건드리므로 synchronized로 한 번에 하나만 실행한다.
 */
@Slf4j
public class ClientConnection {

    private static final int SEND_TIME_LIMIT_MILLIS = 5_000;
    private static final int BUFFER_SIZE_LIMIT_BYTES = 512 * 1024;
    private static final int MAX_BUFFERED_EVENTS = 1_000;

    @Getter
    private final Long sessionId;

    @Getter
    private final Long participantId;

    private final WebSocketSession session;
    private final JsonMapper jsonMapper;
    private final List<EventMessage> buffer = new ArrayList<>();
    private boolean replaying = true;
    private boolean bufferOverflowed;
    private CloseStatus pendingClose;
    private long lastSentSequence;

    public ClientConnection(
        WebSocketSession session,
        Long sessionId,
        Long participantId,
        long lastAppliedSequence,
        JsonMapper jsonMapper
    ) {
        this.session = new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MILLIS, BUFFER_SIZE_LIMIT_BYTES);
        this.sessionId = sessionId;
        this.participantId = participantId;
        this.lastSentSequence = lastAppliedSequence;
        this.jsonMapper = jsonMapper;
    }

    // 커밋 후 실시간 EVENT (EventBroadcaster). 재전송 중이면 상자에 모아두고, 상자가 가득 차면 연결을 끊는다
    public synchronized void push(EventMessage message) {
        if (bufferOverflowed) return;
        if (!replaying) {
            sendEvent(message);
            return;
        }

        if (buffer.size() >= MAX_BUFFERED_EVENTS) {
            closeForBufferOverflow();
            return;
        }
        buffer.add(message);
    }

    // DB에서 꺼낸 놓친 이벤트 (ChatWebSocketHandler)
    public synchronized void sendReplay(EventMessage message) {
        sendEvent(message);
    }

    /**
     * REPLAY_COMPLETE를 보내고, 상자에 모아둔 EVENT 중 재전송으로 이미 보낸 번호보다 큰 것만 순서대로 보낸 뒤 실시간 모드로 바꾼다.
     * 한 메서드(synchronized) 안에서 처리하므로 그 사이에 실시간 EVENT가 끼어들지 않는다.
     */
    public synchronized void finishReplay() {
        if (bufferOverflowed) return;
        send(ReplayCompleteMessage.of(lastSentSequence));
        buffer.stream()
            .filter(message -> message.sequence() > lastSentSequence)
            .sorted(Comparator.comparingLong(EventMessage::sequence))
            .forEach(this::sendEvent);
        buffer.clear();
        replaying = false;
        if (pendingClose != null) close(pendingClose);
    }

    /**
     * 메시지를 JSON으로 보낸다. 실패해도 예외를 던지지 않는다.
     * push는 best-effort이고, 놓친 이벤트는 재연결할 때 재전송으로 받는다.
     * EVENT가 아닌 메시지(CONNECTED, ACK, ERROR)는 순서 보장이 필요 없어서 상자를 거치지 않는다.
     */
    public void send(Object message) {
        if (!session.isOpen()) return;

        try {
            session.sendMessage(new TextMessage(jsonMapper.writeValueAsString(message)));
        } catch (IOException | SessionLimitExceededException e) {
            log.warn("ws_send_failed sessionId={} participantId={} reason={}", sessionId, participantId, e.getMessage());
        }
    }

    public void close(CloseStatus status) {
        try {
            session.close(status);
        } catch (IOException e) {
            log.warn("ws_close_failed sessionId={} participantId={} reason={}", sessionId, participantId, e.getMessage());
        }
    }

    // 실시간 EVENT는 서로 다른 스레드에서 와서 번호가 뒤바뀔 수 있으므로 걸러내지 않고 보낸다. 마지막 번호만 큰 쪽으로 기록한다
    private void sendEvent(EventMessage message) {
        send(message);
        lastSentSequence = Math.max(lastSentSequence, message.sequence());
    }

    // 재전송 루프가 끊긴 연결에 DB 조회를 계속하지 않도록 확인한다 (ChatWebSocketHandler)
    public synchronized boolean isBufferOverflowed() {
        return bufferOverflowed;
    }

    private void closeForBufferOverflow() {
        bufferOverflowed = true;
        buffer.clear();
        log.warn(
            "ws_replay_buffer_overflow sessionId={} participantId={} lastSentSequence={}",
            sessionId,
            participantId,
            lastSentSequence
        );
        close(CloseStatus.SERVICE_OVERLOAD);
    }

    /**
     * 보낼 EVENT를 다 보낸 뒤 연결을 닫는다 (LEAVE, SESSION_ENDED 이후).
     * 재전송 중이면 방금 push한 종료 EVENT가 아직 상자에 있으므로 바로 닫지 않고, finishReplay에서 상자를 비운 뒤 닫는다.
     * 바로 닫으면 종료 EVENT가 클라이언트에 가지 않고, 종료 뒤에는 재연결도 거부돼 다시 받을 방법이 없다.
     */
    public synchronized void closeAfterPendingEvents(CloseStatus status) {
        if (replaying && !bufferOverflowed) {
            pendingClose = status;
            return;
        }
        close(status);
    }

}

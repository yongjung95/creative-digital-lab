package com.creativedigital.chat.realtime.handler;

import com.creativedigital.chat.common.error.BusinessException;
import com.creativedigital.chat.common.error.ErrorCode;
import com.creativedigital.chat.event.dto.AppendResult;
import com.creativedigital.chat.event.dto.EventResult;
import com.creativedigital.chat.event.service.EventService;
import com.creativedigital.chat.realtime.connection.ClientConnection;
import com.creativedigital.chat.realtime.connection.ConnectionRegistry;
import com.creativedigital.chat.realtime.connection.RealtimeCloseStatus;
import com.creativedigital.chat.realtime.dto.AckMessage;
import com.creativedigital.chat.realtime.dto.ConnectedMessage;
import com.creativedigital.chat.realtime.dto.ConnectionRequest;
import com.creativedigital.chat.realtime.dto.ErrorMessage;
import com.creativedigital.chat.realtime.dto.EventMessage;
import com.creativedigital.chat.realtime.dto.SendEventMessage;
import com.creativedigital.chat.session.service.SessionService;
import jakarta.validation.Validator;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * WS 입구. REST Controller처럼 요청을 Command로 바꿔 SessionService에 넘기는 얇은 역할만 한다.
 * 검증, Lock, 저장, 중복 처리는 SessionService가 하므로 REST로 보내든 WS로 보내든 결과가 같다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private static final String CONNECTION_ATTRIBUTE = "connection";
    private static final int REPLAY_BATCH_SIZE = 500;

    private final SessionService sessionService;
    private final EventService eventService;
    private final ConnectionRegistry connectionRegistry;
    private final JsonMapper jsonMapper;
    private final Validator validator;

    /**
     * 검증 → 명단 등록 → 상태 확인(RECONNECT) → CONNECTED → 놓친 이벤트 재전송 → REPLAY_COMPLETE 순서로 처리한다.
     * - 검증을 등록보다 먼저: 등록하면 같은 참여자의 기존 연결이 끊기므로, 남의 연결을 끊지 못하게 한다
     * - 등록을 상태 확인보다 먼저: 옛 연결의 끊김 처리가 언제 끼어들어도 마지막에 ACTIVE로 끝나게 한다 (SessionService.connect)
     * - 등록을 재전송 조회보다 먼저: 조회와 등록 사이에 커밋된 이벤트를 놓치지 않게 한다. 등록 이후 이벤트는 상자에 모인다
     * 검증에 실패해도 연결을 받은 뒤 ERROR를 보내고 close 코드로 이유를 알린다 (브라우저는 handshake 거절 사유를 읽을 수 없음).
     */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        ConnectionRequest request;
        try {
            request = ConnectionRequest.from(session.getUri());
            sessionService.validateConnection(request.sessionId(), request.participantId());
        } catch (Exception e) {
            reject(session, e);
            return;
        }

        ClientConnection connection = new ClientConnection(
            session,
            request.sessionId(),
            request.participantId(),
            request.lastAppliedSequence(),
            jsonMapper
        );
        session.getAttributes().put(CONNECTION_ATTRIBUTE, connection);
        connectionRegistry.register(connection);

        // 검증과 등록 사이에 상태가 바뀐 경우(그 사이 LEAVE 등) 명단에서 빼고 거절한다
        try {
            sessionService.connect(request.sessionId(), request.participantId());
        } catch (Exception e) {
            connectionRegistry.unregister(connection);
            reject(session, e);
            return;
        }

        connection.send(ConnectedMessage.of(request));
        replayMissedEvents(connection, request.lastAppliedSequence());
        log.info(
            "ws_connected sessionId={} participantId={} lastAppliedSequence={}",
            request.sessionId(),
            request.participantId(),
            request.lastAppliedSequence()
        );
    }

    /**
     * SEND_EVENT를 처리하고 보낸 사람에게만 ACK 또는 ERROR를 보낸다.
     * 화면에 반영할 EVENT는 커밋 후 EventBroadcaster가 보낸 사람을 포함한 세션 전체에 push한다.
     */
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage textMessage) {
        ClientConnection connection = connectionOf(session);
        if (connection == null) return;

        SendEventMessage message = null;
        try {
            message = parse(textMessage.getPayload());
            AppendResult result = sessionService.appendClientEvent(
                connection.getSessionId(),
                message.toCommand(connection.getParticipantId())
            );
            connection.send(AckMessage.from(result));
        } catch (Exception e) {
            String eventId = message != null ? message.eventId() : null;
            ErrorCode errorCode = resolveErrorCode(e, connection, eventId);
            connection.send(ErrorMessage.of(eventId, errorCode));
        }
    }

    /**
     * 명단에 있던 연결이 끊긴 경우에만 DISCONNECT를 만든다.
     * 이미 새 연결로 바뀐 옛 연결의 종료는 명단에서 제거되지 않으므로(ConnectionRegistry.unregister) DISCONNECT도 만들지 않는다.
     * DISCONNECT 저장이 실패하면 로그만 남긴다. 참여자는 ACTIVE로 남지만 재연결하면 정상으로 돌아온다 (운영에서는 heartbeat + 타임아웃, 설계 문서).
     */
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        ClientConnection connection = connectionOf(session);
        if (connection == null) return;

        log.info(
            "ws_closed sessionId={} participantId={} code={}",
            connection.getSessionId(),
            connection.getParticipantId(),
            status.getCode()
        );
        if (!connectionRegistry.unregister(connection)) return;

        try {
            sessionService.disconnect(
                connection.getSessionId(),
                connection.getParticipantId(),
                () -> connectionRegistry.isConnected(connection.getSessionId(), connection.getParticipantId())
            );
        } catch (Exception e) {
            log.error(
                "ws_disconnect_failed sessionId={} participantId={}",
                connection.getSessionId(),
                connection.getParticipantId(),
                e
            );
        }
    }

    /**
     * lastAppliedSequence 이후 이벤트를 DB에서 500개씩 꺼내 순서대로 보낸 뒤, REPLAY_COMPLETE와 상자에 모아둔 실시간 EVENT를 보낸다.
     * 조회 중 오류가 나면 상자가 계속 쌓이지 않게 연결을 끊는다. 클라이언트는 재연결해서 다시 받는다.
     */
    private void replayMissedEvents(
        ClientConnection connection,
        long lastAppliedSequence
    ) {
        try {
            long fromSequence = lastAppliedSequence;
            while (true) {
                List<EventResult> events = eventService.findEventsForReplay(
                    connection.getSessionId(),
                    fromSequence,
                    Long.MAX_VALUE,
                    REPLAY_BATCH_SIZE
                );
                events.forEach(event -> connection.sendReplay(EventMessage.from(event)));
                // 재전송 중 실시간 이벤트가 몰려 상자가 넘치면 연결이 이미 끊겼으므로 더 조회하지 않는다
                if (connection.isBufferOverflowed()) return;
                if (events.size() < REPLAY_BATCH_SIZE) break;
                fromSequence = events.getLast().sequence();
            }
            connection.finishReplay();
        } catch (Exception e) {
            log.error(
                "ws_replay_failed sessionId={} participantId={}",
                connection.getSessionId(),
                connection.getParticipantId(),
                e
            );
            connection.close(CloseStatus.SERVER_ERROR);
        }
    }

    // JSON을 읽지 못하거나, SEND_EVENT가 아니거나, 필드 검증에 실패하면 INVALID_REQUEST
    private SendEventMessage parse(String payload) {
        SendEventMessage message;
        try {
            message = jsonMapper.readValue(payload, SendEventMessage.class);
        } catch (JacksonException e) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        if (!message.isSendEvent() || !validator.validate(message).isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        return message;
    }

    // 예상하지 못한 에러만 스택까지 남긴다. 비즈니스 에러(409 등)는 정상적인 거절이다
    private ErrorCode resolveErrorCode(
        Exception e,
        ClientConnection connection,
        String eventId
    ) {
        ErrorCode errorCode = ErrorCode.from(e);
        if (errorCode == ErrorCode.INTERNAL_ERROR) {
            log.error(
                "ws_event_failed sessionId={} participantId={} eventId={}",
                connection.getSessionId(),
                connection.getParticipantId(),
                eventId,
                e
            );
        } else {
            log.info(
                "ws_event_rejected sessionId={} participantId={} eventId={} code={}",
                connection.getSessionId(),
                connection.getParticipantId(),
                eventId,
                errorCode
            );
        }
        return errorCode;
    }

    private void reject(WebSocketSession session, Exception e) {
        ErrorCode errorCode = ErrorCode.from(e);
        log.info("ws_rejected uri={} code={}", session.getUri(), errorCode);
        if (errorCode == ErrorCode.INTERNAL_ERROR) {
            log.error("ws_connect_failed uri={}", session.getUri(), e);
        }

        try {
            session.sendMessage(new TextMessage(jsonMapper.writeValueAsString(ErrorMessage.of(null, errorCode))));
            session.close(RealtimeCloseStatus.from(errorCode));
        } catch (IOException ioException) {
            log.warn("ws_reject_failed uri={} reason={}", session.getUri(), ioException.getMessage());
        }
    }

    private ClientConnection connectionOf(WebSocketSession session) {
        return (ClientConnection) session.getAttributes().get(CONNECTION_ATTRIBUTE);
    }

}

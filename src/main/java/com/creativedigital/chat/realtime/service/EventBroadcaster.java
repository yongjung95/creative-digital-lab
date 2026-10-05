package com.creativedigital.chat.realtime.service;

import com.creativedigital.chat.event.dto.EventAppended;
import com.creativedigital.chat.event.dto.EventResult;
import com.creativedigital.chat.realtime.connection.ClientConnection;
import com.creativedigital.chat.realtime.connection.ConnectionRegistry;
import com.creativedigital.chat.realtime.dto.EventMessage;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.socket.CloseStatus;

/**
 * 커밋된 이벤트를 같은 세션의 연결 전체(보낸 사람 포함)에 push한다.
 * 커밋 전에 보내면 롤백됐을 때 "상대방은 봤는데 DB엔 없는 메시지"가 생기므로 커밋 이후에만 보낸다.
 * push는 best-effort다. 실패해도 이벤트는 이미 저장돼 있고, 재연결 replay로 받는다.
 * 서로 다른 요청의 커밋 후 처리는 각자의 스레드에서 돌아서 push 순서가 sequence와 뒤바뀔 수 있다. 클라이언트는 sequence로 정렬/빈틈을 확인한다.
 * 재전송 중인 연결은 push를 바로 보내지 않고 모아뒀다가 재전송이 끝나면 보낸다 (ClientConnection).
 */
@Component
@RequiredArgsConstructor
public class EventBroadcaster {

    private final ConnectionRegistry connectionRegistry;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onEventAppended(EventAppended appended) {
        EventResult event = appended.event();
        List<ClientConnection> connections = connectionRegistry.connectionsOf(event.sessionId());
        if (connections.isEmpty()) return;

        EventMessage message = EventMessage.from(event);
        connections.forEach(connection -> connection.push(message));
        closeFinishedConnections(event, connections);
    }

    /**
     * 더 이상 아무것도 보낼 수 없는 연결은 EVENT를 보낸 뒤 1000(정상 종료)으로 닫는다.
     * 세션 종료면 전원, LEAVE면 나간 사람만.
     * 재전송 중인 연결은 종료 EVENT가 상자에 있으므로, 재전송이 끝나 상자를 비운 뒤 닫는다 (ClientConnection).
     */
    private void closeFinishedConnections(
        EventResult event,
        List<ClientConnection> connections
    ) {
        switch (event.eventType()) {
            case SESSION_ENDED -> connections.forEach(connection -> connection.closeAfterPendingEvents(CloseStatus.NORMAL));
            case LEAVE -> connections.stream()
                .filter(connection -> connection.getParticipantId().equals(event.participantId()))
                .forEach(connection -> connection.closeAfterPendingEvents(CloseStatus.NORMAL));
            default -> {
                // 연결을 유지한다
            }
        }
    }

}

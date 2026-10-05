package com.creativedigital.chat.realtime.connection;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/**
 * 지금 접속 중인 연결 명단: 세션 → (참여자 → 연결). 참여자당 연결은 하나만 둔다.
 * 등록/제거는 바깥 맵의 compute 안에서 해서, 같은 세션에 대한 변경이 동시에 일어나도 엉키지 않는다 (compute는 키 단위로 원자적).
 * 서버 메모리라 단일 인스턴스 기준이다. 다중 인스턴스는 공유 저장소 + 서버 간 이벤트 전파가 필요하다 (설계 문서).
 */
@Component
public class ConnectionRegistry {

    private final Map<Long, Map<Long, ClientConnection>> connections = new ConcurrentHashMap<>();

    /**
     * 연결을 등록한다. 같은 참여자의 기존 연결이 있으면 새 연결로 바꾸고 기존 연결은 4000으로 끊는다.
     * 끊는 작업(네트워크 I/O)은 compute 밖에서 한다.
     * compute 안에서 오래 걸리는 일을 하면 같은 세션의 다른 등록/제거가 기다린다.
     */
    public void register(ClientConnection connection) {
        AtomicReference<ClientConnection> replaced = new AtomicReference<>();
        connections.compute(connection.getSessionId(), (sessionId, participants) -> {
            Map<Long, ClientConnection> current = participants != null ? participants : new ConcurrentHashMap<>();
            replaced.set(current.put(connection.getParticipantId(), connection));
            return current;
        });

        ClientConnection previous = replaced.get();
        if (previous != null) previous.close(RealtimeCloseStatus.REPLACED);
    }

    /**
     * 명단의 현재 연결이 이 연결일 때만 제거한다.
     * 이미 새 연결로 바뀐 옛 연결이 늦게 끊긴 경우는 무시한다.
     * 세션에 남은 연결이 없으면 세션 항목도 지운다.
     *
     * @return 제거했으면 true (8-2에서 DISCONNECT 이벤트 생성 여부 판단에 사용)
     */
    public boolean unregister(ClientConnection connection) {
        AtomicBoolean removed = new AtomicBoolean();
        connections.computeIfPresent(connection.getSessionId(), (sessionId, participants) -> {
            removed.set(participants.remove(connection.getParticipantId(), connection));
            return participants.isEmpty() ? null : participants;
        });
        return removed.get();
    }

    public List<ClientConnection> connectionsOf(Long sessionId) {
        Map<Long, ClientConnection> participants = connections.get(sessionId);
        if (participants == null) return List.of();
        return List.copyOf(participants.values());
    }

    // 이 참여자의 연결이 명단에 있는지 (끊김 처리 도중 새 연결이 들어왔는지 확인할 때 사용)
    public boolean isConnected(
        Long sessionId,
        Long participantId
    ) {
        Map<Long, ClientConnection> participants = connections.get(sessionId);
        return participants != null && participants.containsKey(participantId);
    }

}

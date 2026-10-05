package com.creativedigital.chat.realtime.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.creativedigital.chat.event.dto.EventResult;
import com.creativedigital.chat.event.entity.EventType;
import com.creativedigital.chat.realtime.dto.EventMessage;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 놓친 이벤트 재전송 중에 실시간 EVENT가 끼어들어도 순서가 지켜지는지 확인한다.
 * 순서를 지키는 로직은 전부 ClientConnection 안에 있어서, 서버 없이 이 클래스를 직접 호출해 타이밍을 재현한다.
 * 실제 전송 대신 가짜 WebSocketSession이 보낸 메시지를 기록한다.
 */
class ClientConnectionTest {

    private static final Long SESSION_ID = 1L;
    private static final Long PARTICIPANT_ID = 7L;

    private final JsonMapper jsonMapper = JsonMapper.builder().findAndAddModules().build();
    private final List<JsonNode> sent = new ArrayList<>();
    private WebSocketSession session;

    @BeforeEach
    void setUp() throws Exception {
        session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        doAnswer(invocation -> {
            TextMessage message = invocation.getArgument(0);
            sent.add(jsonMapper.readTree(message.getPayload()));
            return null;
        }).when(session).sendMessage(any());
    }

    @Test
    void 재전송_중에_온_실시간_EVENT는_바로_보내지_않고_재전송이_끝난_뒤_보낸다() {
        ClientConnection connection = connection(100);

        connection.sendReplay(event(101));
        connection.push(event(103));     // 재전송 도중 실시간 EVENT 도착
        connection.sendReplay(event(102));
        connection.finishReplay();

        assertThat(sentTypesAndSequences()).containsExactly(
            "EVENT:101",
            "EVENT:102",
            "REPLAY_COMPLETE:102",
            "EVENT:103"
        );
    }

    @Test
    void 재전송으로_이미_보낸_이벤트가_상자에도_있으면_한_번만_보낸다() {
        ClientConnection connection = connection(100);

        connection.push(event(101));     // 등록 직후 커밋된 이벤트: 상자에도 들어가고
        connection.sendReplay(event(101)); // DB 조회에도 잡힌다
        connection.sendReplay(event(102));
        connection.finishReplay();

        assertThat(sentTypesAndSequences()).containsExactly(
            "EVENT:101",
            "EVENT:102",
            "REPLAY_COMPLETE:102"
        );
    }

    @Test
    void 상자에_모인_EVENT는_도착_순서와_관계없이_sequence_순서로_보낸다() {
        ClientConnection connection = connection(100);

        connection.push(event(103));
        connection.push(event(102));
        connection.finishReplay();

        assertThat(sentTypesAndSequences()).containsExactly(
            "REPLAY_COMPLETE:100",
            "EVENT:102",
            "EVENT:103"
        );
    }

    @Test
    void lastAppliedSequence_이하의_EVENT는_상자에서_버린다() {
        ClientConnection connection = connection(100);

        connection.push(event(100));
        connection.push(event(101));
        connection.finishReplay();

        assertThat(sentTypesAndSequences()).containsExactly(
            "REPLAY_COMPLETE:100",
            "EVENT:101"
        );
    }

    @Test
    void 재전송이_끝난_뒤의_실시간_EVENT는_바로_보낸다() {
        ClientConnection connection = connection(100);
        connection.finishReplay();

        connection.push(event(101));

        assertThat(sentTypesAndSequences()).containsExactly(
            "REPLAY_COMPLETE:100",
            "EVENT:101"
        );
    }

    @Test
    void 재전송_중_상자가_가득_차면_연결을_끊고_더_보내지_않는다() throws Exception {
        ClientConnection connection = connection(100);

        for (long sequence = 101; sequence <= 1_101; sequence++) {
            connection.push(event(sequence));    // 1,000개까지 모으고 1,001번째에서 넘침
        }
        connection.finishReplay();

        verify(session).close(CloseStatus.SERVICE_OVERLOAD);
        assertThat(connection.isBufferOverflowed()).isTrue();
        assertThat(sent).isEmpty();
    }

    @Test
    void 재전송_중에_닫기를_요청하면_상자에_모인_EVENT를_보낸_뒤_닫는다() throws Exception {
        ClientConnection connection = connection(100);

        connection.sendReplay(event(101));
        connection.push(event(102));     // 재전송 도중 커밋된 종료 이벤트(LEAVE, SESSION_ENDED)
        connection.closeAfterPendingEvents(CloseStatus.NORMAL);
        verify(session, never()).close(any());

        connection.finishReplay();

        assertThat(sentTypesAndSequences()).containsExactly(
            "EVENT:101",
            "REPLAY_COMPLETE:101",
            "EVENT:102"
        );
        verify(session).close(CloseStatus.NORMAL);
    }

    @Test
    void 실시간_모드에서_닫기를_요청하면_바로_닫는다() throws Exception {
        ClientConnection connection = connection(100);
        connection.finishReplay();

        connection.closeAfterPendingEvents(CloseStatus.NORMAL);

        verify(session).close(CloseStatus.NORMAL);
    }

    private ClientConnection connection(long lastAppliedSequence) {
        return new ClientConnection(
            session,
            SESSION_ID,
            PARTICIPANT_ID,
            lastAppliedSequence,
            jsonMapper
        );
    }

    private EventMessage event(long sequence) {
        return EventMessage.from(new EventResult(
            "event-" + sequence,
            SESSION_ID,
            sequence,
            EventType.MESSAGE,
            PARTICIPANT_ID,
            null,
            jsonMapper.createObjectNode().put("content", "메시지" + sequence),
            LocalDateTime.of(2026, 10, 4, 12, 0),
            LocalDateTime.of(2026, 10, 4, 12, 0)
        ));
    }

    // "EVENT:101", "REPLAY_COMPLETE:102" 형태로 보낸 순서를 확인한다
    private List<String> sentTypesAndSequences() {
        return sent.stream()
            .map(message -> {
                String type = message.path("type").asString();
                long sequence = type.equals("EVENT")
                    ? message.path("event").path("sequence").asLong()
                    : message.path("lastSequence").asLong();
                return type + ":" + sequence;
            })
            .toList();
    }

}

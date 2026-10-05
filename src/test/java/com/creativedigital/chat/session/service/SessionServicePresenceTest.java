package com.creativedigital.chat.session.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.creativedigital.chat.TestcontainersConfiguration;
import com.creativedigital.chat.common.error.BusinessException;
import com.creativedigital.chat.common.error.ErrorCode;
import com.creativedigital.chat.event.dto.EventResponse;
import com.creativedigital.chat.event.entity.EventType;
import com.creativedigital.chat.session.dto.EventRangeRequest;
import com.creativedigital.chat.session.dto.JoinCommand;
import com.creativedigital.chat.session.dto.SessionDetailResponse;
import com.creativedigital.chat.session.entity.ParticipantStatus;
import com.creativedigital.chat.session.entity.SessionStatus;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * WS 연결/끊김이 만드는 서버 이벤트(DISCONNECT/RECONNECT)와 상태 계산.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
    "app.snapshot.scheduler.enabled=false",
    "app.message-projection.scheduler.enabled=false"
})
class SessionServicePresenceTest {

    // 끊김 처리 도중 같은 참여자의 새 연결이 들어오지 않은 경우
    private static final BooleanSupplier NO_NEW_CONNECTION = () -> false;

    @Autowired
    private SessionService sessionService;

    @Test
    void 끊기면_DISCONNECT가_생기고_혼자면_세션은_SUSPENDED가_된다() {
        Long sessionId = createSession();
        Long participantId = join(sessionId, "철수");

        sessionService.disconnect(sessionId, participantId, NO_NEW_CONNECTION);

        SessionDetailResponse session = sessionService.getSession(sessionId);
        assertThat(session.status()).isEqualTo(SessionStatus.SUSPENDED);
        assertThat(session.participants().getFirst().status()).isEqualTo(ParticipantStatus.DISCONNECTED);
        assertThat(eventTypes(sessionId)).containsExactly(EventType.SESSION_CREATED, EventType.JOIN, EventType.DISCONNECT);
    }

    @Test
    void 같은_끊김을_두_번_처리해도_DISCONNECT는_하나만_생긴다() {
        Long sessionId = createSession();
        Long participantId = join(sessionId, "철수");

        sessionService.disconnect(sessionId, participantId, NO_NEW_CONNECTION);
        sessionService.disconnect(sessionId, participantId, NO_NEW_CONNECTION);

        assertThat(eventTypes(sessionId)).containsExactly(EventType.SESSION_CREATED, EventType.JOIN, EventType.DISCONNECT);
    }

    @Test
    void 끊긴_참여자가_다시_연결하면_RECONNECT가_생기고_IN_PROGRESS로_돌아온다() {
        Long sessionId = createSession();
        Long participantId = join(sessionId, "철수");
        sessionService.disconnect(sessionId, participantId, NO_NEW_CONNECTION);

        sessionService.connect(sessionId, participantId);

        SessionDetailResponse session = sessionService.getSession(sessionId);
        assertThat(session.status()).isEqualTo(SessionStatus.IN_PROGRESS);
        assertThat(session.participants().getFirst().status()).isEqualTo(ParticipantStatus.ACTIVE);
        assertThat(eventTypes(sessionId)).endsWith(EventType.DISCONNECT, EventType.RECONNECT);
    }

    @Test
    void 이미_접속중인_참여자가_연결하면_RECONNECT가_생기지_않는다() {
        Long sessionId = createSession();
        Long participantId = join(sessionId, "철수");

        sessionService.connect(sessionId, participantId);

        assertThat(eventTypes(sessionId)).containsExactly(EventType.SESSION_CREATED, EventType.JOIN);
    }

    @Test
    void 한_명만_끊기면_세션은_IN_PROGRESS로_유지된다() {
        Long sessionId = createSession();
        Long first = join(sessionId, "철수");
        join(sessionId, "영희");

        sessionService.disconnect(sessionId, first, NO_NEW_CONNECTION);

        assertThat(sessionService.getSession(sessionId).status()).isEqualTo(SessionStatus.IN_PROGRESS);
    }

    @Test
    void 종료된_세션에서는_끊겨도_DISCONNECT가_생기지_않는다() {
        Long sessionId = createSession();
        Long participantId = join(sessionId, "철수");
        sessionService.endSession(sessionId, newEventId());

        sessionService.disconnect(sessionId, participantId, NO_NEW_CONNECTION);

        assertThat(eventTypes(sessionId)).endsWith(EventType.SESSION_ENDED);
    }

    /**
     * 옛 연결이 명단에서 빠진 직후 새 연결이 등록되고 connect까지 먼저 끝난 뒤, 옛 연결의 끊김 처리가 늦게 Lock을 잡는 순서.
     * connect는 아직 ACTIVE라 아무것도 하지 않으므로, 끊김 처리가 새 연결을 확인하지 않으면 살아 있는 연결이 DISCONNECTED로 남는다.
     */
    @Test
    void 새_연결이_먼저_처리된_뒤_옛_연결의_끊김_처리가_오면_DISCONNECT가_생기지_않는다() {
        Long sessionId = createSession();
        Long participantId = join(sessionId, "철수");

        sessionService.connect(sessionId, participantId);
        sessionService.disconnect(sessionId, participantId, () -> true);

        SessionDetailResponse session = sessionService.getSession(sessionId);
        assertThat(session.participants().getFirst().status()).isEqualTo(ParticipantStatus.ACTIVE);
        assertThat(eventTypes(sessionId)).containsExactly(EventType.SESSION_CREATED, EventType.JOIN);
    }

    @Test
    void 나간_참여자는_연결할_수_없다() {
        Long sessionId = createSession();
        Long first = join(sessionId, "철수");
        join(sessionId, "영희");
        sessionService.endSession(sessionId, newEventId());

        assertThatThrownBy(() -> sessionService.connect(sessionId, first))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.SESSION_COMPLETED);
    }

    private Long createSession() {
        return sessionService.createSession(newEventId()).event().sessionId();
    }

    private Long join(Long sessionId, String displayName) {
        return sessionService.join(sessionId, new JoinCommand(newEventId(), displayName, null))
            .event()
            .participantId();
    }

    private List<EventType> eventTypes(Long sessionId) {
        return sessionService.getEvents(sessionId, new EventRangeRequest(null, null, null)).events().stream()
            .map(EventResponse::type)
            .toList();
    }

    private String newEventId() {
        return UUID.randomUUID().toString();
    }

}

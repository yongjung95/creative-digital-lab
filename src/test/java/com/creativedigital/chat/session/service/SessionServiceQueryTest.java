package com.creativedigital.chat.session.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.creativedigital.chat.TestcontainersConfiguration;
import com.creativedigital.chat.common.error.BusinessException;
import com.creativedigital.chat.common.error.ErrorCode;
import com.creativedigital.chat.event.dto.EventListResponse;
import com.creativedigital.chat.event.dto.EventResponse;
import com.creativedigital.chat.event.entity.EventType;
import com.creativedigital.chat.session.dto.ClientEventCommand;
import com.creativedigital.chat.session.dto.EventRangeRequest;
import com.creativedigital.chat.session.dto.JoinCommand;
import com.creativedigital.chat.session.dto.SessionDetailResponse;
import com.creativedigital.chat.session.dto.SessionDetailResponse.ParticipantSummary;
import com.creativedigital.chat.session.dto.SessionListResponse;
import com.creativedigital.chat.session.dto.SessionListResponse.SessionSummary;
import com.creativedigital.chat.session.dto.SessionSearchCondition;
import com.creativedigital.chat.session.entity.ParticipantStatus;
import com.creativedigital.chat.session.entity.SessionStatus;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.json.JsonMapper;

/**
 * 테스트끼리 같은 DB를 쓰므로, 목록 조회 테스트는 참여자 이름을 테스트마다 고유하게 만들어 다른 데이터와 섞이지 않게 한다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class SessionServiceQueryTest {

    @Autowired
    private SessionService sessionService;

    @Autowired
    private JsonMapper jsonMapper;

    // ===== 세션 상세 =====

    @Test
    void 세션_상세에_참여자_목록과_상태가_담긴다() {
        Long sessionId = createSession();
        Long chulsoo = join(sessionId, "철수");
        join(sessionId, "영희");
        sessionService.appendClientEvent(sessionId, command(chulsoo, EventType.LEAVE, null));

        SessionDetailResponse detail = sessionService.getSession(sessionId);

        assertThat(detail.status()).isEqualTo(SessionStatus.IN_PROGRESS);
        assertThat(detail.lastSequence()).isEqualTo(4);
        assertThat(detail.participants())
            .extracting(ParticipantSummary::displayName, ParticipantSummary::status)
            .containsExactlyInAnyOrder(
                tuple("철수", ParticipantStatus.LEFT),
                tuple("영희", ParticipantStatus.ACTIVE)
            );
    }

    @Test
    void 없는_세션을_조회하면_SESSION_NOT_FOUND() {
        assertThatThrownBy(() -> sessionService.getSession(Long.MAX_VALUE))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.SESSION_NOT_FOUND);
    }

    // ===== 이벤트 조회 =====

    @Test
    void 이벤트를_sequence_범위로_조회하고_다음_페이지가_있는지_알려준다() {
        // sequence 1: SESSION_CREATED, 2: JOIN, 3~5: MESSAGE
        Long sessionId = createSession();
        Long participantId = join(sessionId, "철수");
        for (int i = 0; i < 3; i++) {
            sessionService.appendClientEvent(sessionId, command(participantId, EventType.MESSAGE, "메시지" + i));
        }

        EventListResponse firstPage = sessionService.getEvents(sessionId, new EventRangeRequest(1L, null, 2));
        EventListResponse lastPage = sessionService.getEvents(sessionId, new EventRangeRequest(3L, null, 2));

        assertThat(firstPage.events()).extracting(EventResponse::sequence).containsExactly(2L, 3L);
        assertThat(firstPage.hasMore()).isTrue();
        assertThat(lastPage.events()).extracting(EventResponse::sequence).containsExactly(4L, 5L);
        assertThat(lastPage.hasMore()).isFalse();
    }

    @Test
    void 없는_세션의_이벤트를_조회하면_SESSION_NOT_FOUND() {
        assertThatThrownBy(() -> sessionService.getEvents(Long.MAX_VALUE, new EventRangeRequest(null, null, null)))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.SESSION_NOT_FOUND);
    }

    // ===== 세션 목록 =====

    @Test
    void 참여자_이름으로_세션을_필터링하고_참여자_이름_목록을_함께_반환한다() {
        String uniqueName = uniqueName();
        Long target = createSession();
        join(target, uniqueName);
        join(target, "영희");
        Long other = createSession();
        join(other, "다른사람");

        SessionListResponse result = sessionService.searchSessions(condition(null, uniqueName, null, 20));

        assertThat(result.sessions()).hasSize(1);
        assertThat(result.sessions().getFirst().sessionId()).isEqualTo(target);
        assertThat(result.sessions().getFirst().participantNames()).containsExactlyInAnyOrder(uniqueName, "영희");
        assertThat(result.nextCursor()).isNull();
    }

    @Test
    void 상태로_세션을_필터링한다() {
        String uniqueName = uniqueName();
        Long inProgress = createSession();
        join(inProgress, uniqueName);
        Long completed = createSession();
        join(completed, uniqueName);
        sessionService.endSession(completed, newEventId());

        SessionListResponse result = sessionService.searchSessions(condition(SessionStatus.COMPLETED, uniqueName, null, 20));

        assertThat(result.sessions()).extracting(SessionSummary::sessionId).containsExactly(completed);
    }

    @Test
    void 커서로_다음_페이지를_이어서_조회한다() {
        String uniqueName = uniqueName();
        Long first = createSession();
        Long second = createSession();
        Long third = createSession();
        join(first, uniqueName);
        join(second, uniqueName);
        join(third, uniqueName);

        SessionListResponse page1 = sessionService.searchSessions(condition(null, uniqueName, null, 2));
        SessionListResponse page2 = sessionService.searchSessions(condition(null, uniqueName, page1.nextCursor(), 2));

        // 최신 세션부터 (id 내림차순)
        assertThat(page1.sessions()).extracting(SessionSummary::sessionId).containsExactly(third, second);
        assertThat(page1.nextCursor()).isEqualTo(second);
        assertThat(page2.sessions()).extracting(SessionSummary::sessionId).containsExactly(first);
        assertThat(page2.nextCursor()).isNull();
    }

    private SessionSearchCondition condition(
        SessionStatus status,
        String participantName,
        Long cursor,
        int size
    ) {
        return new SessionSearchCondition(
            status,
            null,
            null,
            participantName,
            cursor,
            size
        );
    }

    private Long createSession() {
        return sessionService.createSession(newEventId()).event().sessionId();
    }

    private Long join(Long sessionId, String displayName) {
        return sessionService.join(sessionId, new JoinCommand(newEventId(), displayName, null))
            .event()
            .participantId();
    }

    private ClientEventCommand command(
        Long participantId,
        EventType eventType,
        String content
    ) {
        return new ClientEventCommand(
            newEventId(),
            participantId,
            eventType,
            null,
            content == null ? null : jsonMapper.createObjectNode().put("content", content),
            null
        );
    }

    private String uniqueName() {
        return "user-" + UUID.randomUUID();
    }

    private String newEventId() {
        return UUID.randomUUID().toString();
    }

}

package com.creativedigital.chat.session.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.creativedigital.chat.TestcontainersConfiguration;
import com.creativedigital.chat.common.error.BusinessException;
import com.creativedigital.chat.common.error.ErrorCode;
import com.creativedigital.chat.event.dto.AppendResult;
import com.creativedigital.chat.event.entity.Event;
import com.creativedigital.chat.event.repository.EventRepository;
import com.creativedigital.chat.event.entity.EventType;
import com.creativedigital.chat.session.dto.ClientEventCommand;
import com.creativedigital.chat.session.entity.Participant;
import com.creativedigital.chat.session.entity.Session;
import com.creativedigital.chat.session.repository.ParticipantRepository;
import com.creativedigital.chat.session.repository.SessionRepository;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Limit;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class SessionServiceSendMessageTest {

    @Autowired
    private SessionService sessionService;

    @Autowired
    private SessionRepository sessionRepository;

    @Autowired
    private ParticipantRepository participantRepository;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private JsonMapper jsonMapper;

    @Test
    void 같은_세션에_동시에_50건을_보내도_sequence가_빈틈_중복_없이_발급된다() throws Exception {
        Participant participant = createSessionWithParticipant();
        Long sessionId = participant.getSessionId();
        int requestCount = 50;
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<AppendResult>> futures = new ArrayList<>();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < requestCount; i++) {
                futures.add(executor.submit(() -> {
                    startLatch.await();
                    return sessionService.appendClientEvent(sessionId, messageCommand(participant, "동시 전송"));
                }));
            }
            startLatch.countDown();
            for (Future<AppendResult> future : futures) {
                future.get();
            }
        }

        List<Event> events = findAllEvents(sessionId);
        assertThat(events).extracting(Event::getSequence)
            .containsExactlyElementsOf(LongStream.rangeClosed(1, requestCount).boxed().toList());
        assertThat(events).extracting(Event::getCreatedAt).isSorted();
        assertThat(sessionRepository.findById(sessionId).orElseThrow().getLastSequence()).isEqualTo(requestCount);
    }

    @Test
    void 같은_eventId로_재전송하면_한번만_저장되고_기존_결과를_반환한다() {
        Participant participant = createSessionWithParticipant();
        ClientEventCommand command = messageCommand(participant, "안녕");

        AppendResult first = sessionService.appendClientEvent(participant.getSessionId(), command);
        AppendResult retry = sessionService.appendClientEvent(participant.getSessionId(), command);

        assertThat(first.duplicate()).isFalse();
        assertThat(retry.duplicate()).isTrue();
        assertThat(retry.event().sequence()).isEqualTo(first.event().sequence());
        assertThat(findAllEvents(participant.getSessionId())).hasSize(1);
    }

    @Test
    void 같은_eventId로_내용이_다르면_EVENT_ID_CONFLICT() {
        Participant participant = createSessionWithParticipant();
        ClientEventCommand original = messageCommand(participant, "안녕");
        ClientEventCommand changed = new ClientEventCommand(
            original.eventId(),
            participant.getId(),
            EventType.MESSAGE,
            null,
            payload("잘가"),
            null
        );
        sessionService.appendClientEvent(participant.getSessionId(), original);

        assertThatThrownBy(() -> sessionService.appendClientEvent(participant.getSessionId(), changed))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.EVENT_ID_CONFLICT);
    }

    @Test
    void 같은_eventId를_다른_세션에_보내면_EVENT_ID_CONFLICT() {
        Participant first = createSessionWithParticipant();
        Participant second = createSessionWithParticipant();
        ClientEventCommand original = messageCommand(first, "안녕");
        sessionService.appendClientEvent(first.getSessionId(), original);

        // 세션만 다르게 보내야 세션 비교가 동작하는지 검증된다 (eventId는 전체 세션에서 유일)
        assertThatThrownBy(() -> sessionService.appendClientEvent(second.getSessionId(), original))
            .isInstanceOf(BusinessException.class)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.EVENT_ID_CONFLICT);
    }

    @Test
    void 이벤트_시각은_DB의_KST_시각으로_기록된다() {
        Participant participant = createSessionWithParticipant();

        AppendResult result = sessionService.appendClientEvent(participant.getSessionId(), messageCommand(participant, "안녕"));

        // DB가 UTC였다면 9시간 차이가 나서 실패한다
        assertThat(result.event().createdAt()).isCloseTo(LocalDateTime.now(), within(1, ChronoUnit.MINUTES));
    }

    private Participant createSessionWithParticipant() {
        LocalDateTime now = LocalDateTime.now();
        Session session = sessionRepository.save(Session.create(now));
        return participantRepository.save(Participant.join(session.getId(), "철수", now));
    }

    private ClientEventCommand messageCommand(Participant participant, String content) {
        return new ClientEventCommand(
            UUID.randomUUID().toString(),
            participant.getId(),
            EventType.MESSAGE,
            null,
            payload(content),
            null
        );
    }

    private JsonNode payload(String content) {
        return jsonMapper.createObjectNode().put("content", content);
    }

    private List<Event> findAllEvents(Long sessionId) {
        return eventRepository.findRange(sessionId, 0, Long.MAX_VALUE, Limit.unlimited());
    }

}

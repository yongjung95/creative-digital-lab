package com.creativedigital.chat.session.service;

import com.creativedigital.chat.common.error.BusinessException;
import com.creativedigital.chat.common.error.ErrorCode;
import com.creativedigital.chat.common.time.DbClock;
import com.creativedigital.chat.event.dto.AppendResult;
import com.creativedigital.chat.event.dto.EventListResponse;
import com.creativedigital.chat.event.dto.EventResult;
import com.creativedigital.chat.event.dto.NewEvent;
import com.creativedigital.chat.event.entity.EventType;
import com.creativedigital.chat.event.service.EventService;
import com.creativedigital.chat.session.dto.ClientEventCommand;
import com.creativedigital.chat.session.dto.EventRangeRequest;
import com.creativedigital.chat.session.dto.JoinCommand;
import com.creativedigital.chat.session.dto.SessionDetailResponse;
import com.creativedigital.chat.session.dto.SessionListResponse;
import com.creativedigital.chat.session.dto.SessionSearchCondition;
import com.creativedigital.chat.session.entity.Participant;
import com.creativedigital.chat.session.entity.ParticipantStatus;
import com.creativedigital.chat.session.entity.Session;
import com.creativedigital.chat.session.repository.ParticipantRepository;
import com.creativedigital.chat.session.repository.SessionRepository;
import com.creativedigital.chat.session.repository.SessionSpecs;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 모든 Command의 단일 진입점.
 * 트랜잭션과 session 행 Lock을 소유하고, 이벤트 저장은 EventService에 위임한다.
 * 처리 순서: Lock → 중복 확인(상태 검증보다 먼저) → 상태 검증 → DB 시각 → sequence 발급 → 동기 Projection 변경 → 이벤트 저장.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionService {

    private static final int MAX_PARTICIPANTS = 2;
    private static final int MAX_CONTENT_LENGTH = 2000;
    private static final String LOCK_TIMEOUT_METRIC_NAME = "session.lock.timeout";

    private final SessionRepository sessionRepository;
    private final ParticipantRepository participantRepository;
    private final EventService eventService;
    private final DbClock dbClock;
    private final JsonMapper jsonMapper;
    private final TransactionTemplate transactionTemplate;
    private final MeterRegistry meterRegistry;

    /**
     * 세션 생성은 아직 Lock을 잡을 행이 없어서, 같은 요청이 동시에 오면 두 번째는 UNIQUE(event_id) 위반이 난다.
     * 이때 롤백 후 새 트랜잭션에서 먼저 저장된 이벤트를 찾아 재시도 결과로 반환한다.
     */
    public AppendResult createSession(String eventId) {
        NewEvent newEvent = new NewEvent(
            eventId,
            null,
            EventType.SESSION_CREATED,
            null,
            null,
            null,
            null
        );
        try {
            return transactionTemplate.execute(status -> createSessionInTransaction(newEvent));
        } catch (DataIntegrityViolationException e) {
            return transactionTemplate.execute(status -> eventService.findDuplicate(newEvent).orElseThrow(() -> e));
        }
    }

    @Transactional
    public AppendResult join(Long sessionId, JoinCommand command) {
        Session session = lockSession(sessionId);
        NewEvent newEvent = new NewEvent(
            command.eventId(),
            sessionId,
            EventType.JOIN,
            null,
            null,
            joinPayload(command.displayName()),
            command.occurredAt()
        );

        Optional<AppendResult> duplicate = eventService.findDuplicate(newEvent);
        if (duplicate.isPresent()) return duplicate.get();

        validateNotCompleted(session);
        validateSeatAvailable(sessionId);

        LocalDateTime now = dbClock.now();
        Participant participant = participantRepository.save(
            Participant.join(
                sessionId,
                command.displayName(),
                now
            ));
        long sequence = session.issueNextSequence(now);
        recalculateStatus(session);
        return eventService.append(newEvent.withParticipantId(participant.getId()), sequence, now);
    }

    @Transactional
    public AppendResult appendClientEvent(Long sessionId, ClientEventCommand command) {
        validateRequestShape(command);
        Session session = lockSession(sessionId);
        NewEvent newEvent = new NewEvent(
            command.eventId(),
            sessionId,
            command.eventType(),
            command.participantId(),
            command.targetEventId(),
            command.payload(),
            command.occurredAt()
        );

        // 재시도는 이미 성공한 요청이므로 상태 검증보다 먼저 확인한다
        Optional<AppendResult> duplicate = eventService.findDuplicate(newEvent);
        if (duplicate.isPresent()) return duplicate.get();

        validateNotCompleted(session);
        Participant participant = findParticipant(sessionId, command.participantId());
        validateParticipantState(sessionId, participant, command);

        LocalDateTime now = dbClock.now();
        long sequence = session.issueNextSequence(now);
        if (command.eventType() == EventType.LEAVE) {
            participant.leave(now);
            recalculateStatus(session);
        }
        return eventService.append(newEvent, sequence, now);
    }

    /**
     * 남은 참여자를 LEFT로 바꾸고 세션을 종료한다.
     * 참여자마다 LEAVE 이벤트를 만들지 않고 SESSION_ENDED 하나가 남은 참여자의 퇴장까지 의미한다.
     */
    @Transactional
    public AppendResult endSession(Long sessionId, String eventId) {
        Session session = lockSession(sessionId);
        NewEvent newEvent = new NewEvent(
            eventId,
            sessionId,
            EventType.SESSION_ENDED,
            null,
            null,
            null,
            null
        );

        Optional<AppendResult> duplicate = eventService.findDuplicate(newEvent);
        if (duplicate.isPresent()) return duplicate.get();

        validateNotCompleted(session);

        LocalDateTime now = dbClock.now();
        participantRepository.findBySessionId(sessionId).stream()
            .filter(participant -> participant.getStatus().occupiesSeat())
            .forEach(participant -> participant.leave(now));
        long sequence = session.issueNextSequence(now);
        session.end(now);
        return eventService.append(newEvent, sequence, now);
    }

    private AppendResult createSessionInTransaction(NewEvent newEvent) {
        Optional<AppendResult> duplicate = eventService.findDuplicate(newEvent);
        if (duplicate.isPresent()) return duplicate.get();

        LocalDateTime now = dbClock.now();
        Session session = sessionRepository.save(Session.create(now));
        long sequence = session.issueNextSequence(now);
        return eventService.append(newEvent.withSessionId(session.getId()), sequence, now);
    }

    // Lock 대기 타임아웃은 여기서 sessionId와 함께 남긴다. REST/WS 어느 입구로 와도 어느 세션이 막혔는지 알 수 있게 한다
    private Session lockSession(Long sessionId) {
        try {
            return sessionRepository.findByIdForUpdate(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SESSION_NOT_FOUND));
        } catch (PessimisticLockingFailureException e) {
            log.warn("session_lock_timeout sessionId={}", sessionId);
            meterRegistry.counter(LOCK_TIMEOUT_METRIC_NAME).increment();
            throw e;
        }
    }

    private Participant findParticipant(Long sessionId, Long participantId) {
        return participantRepository.findById(participantId)
            .filter(participant -> participant.getSessionId().equals(sessionId))
            .orElseThrow(() -> new BusinessException(ErrorCode.PARTICIPANT_NOT_FOUND));
    }

    // 참여자 상태가 바뀌면 세션 상태를 다시 계산한다 (IN_PROGRESS ↔ SUSPENDED)
    private void recalculateStatus(Session session) {
        List<ParticipantStatus> statuses = participantRepository.findBySessionId(session.getId()).stream()
            .map(Participant::getStatus)
            .toList();
        session.recalculateStatus(statuses);
    }

    private JsonNode joinPayload(String displayName) {
        return jsonMapper.createObjectNode().put("displayName", displayName);
    }

    // Lock 없이 판단할 수 있는 요청 형식 검증
    private void validateRequestShape(ClientEventCommand command) {
        EventType eventType = command.eventType();
        if (!eventType.isClientSubmittable()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }

        boolean targetRequired = eventType == EventType.MESSAGE_EDITED || eventType == EventType.MESSAGE_DELETED;
        if (targetRequired != (command.targetEventId() != null)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }

        if (eventType == EventType.MESSAGE || eventType == EventType.MESSAGE_EDITED) {
            validateContent(command.payload());
        } else {
            validateEmptyPayload(command.payload());
        }
    }

    // payload는 그대로 저장되므로 content 외의 키는 받지 않는다 (큰 값을 끼워 넣어 저장하는 것 방지)
    private void validateContent(JsonNode payload) {
        if (payload == null || payload.size() != 1 || !payload.path("content").isString()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }

        String content = payload.path("content").asString();
        if (content.isBlank() || content.length() > MAX_CONTENT_LENGTH) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
    }

    // LEAVE, MESSAGE_DELETED는 담을 내용이 없으므로 payload가 없거나 빈 객체여야 한다
    private void validateEmptyPayload(JsonNode payload) {
        if (payload == null || payload.isNull()) return;
        if (!payload.isObject() || !payload.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
    }

    private void validateParticipantState(
        Long sessionId,
        Participant participant,
        ClientEventCommand command
    ) {
        switch (command.eventType()) {
            case MESSAGE -> validateActive(participant);
            case MESSAGE_EDITED, MESSAGE_DELETED -> {
                validateActive(participant);
                validateOwnMessage(sessionId, participant, command.targetEventId());
            }
            case LEAVE -> validateNotLeft(participant);
            default -> throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
    }

    private void validateOwnMessage(
        Long sessionId,
        Participant participant,
        String messageId
    ) {
        EventResult message = eventService.findMessage(sessionId, messageId)
            .orElseThrow(() -> new BusinessException(ErrorCode.MESSAGE_NOT_FOUND));
        if (!message.participantId().equals(participant.getId())) {
            throw new BusinessException(ErrorCode.NOT_MESSAGE_OWNER);
        }
        if (eventService.isMessageDeleted(messageId)) {
            throw new BusinessException(ErrorCode.MESSAGE_ALREADY_DELETED);
        }
    }

    private void validateNotCompleted(Session session) {
        if (!session.isCompleted()) return;
        throw new BusinessException(ErrorCode.SESSION_COMPLETED);
    }

    // ACTIVE + DISCONNECTED 참여자가 정원을 차지한다
    private void validateSeatAvailable(Long sessionId) {
        long occupied = participantRepository.findBySessionId(sessionId).stream()
            .filter(participant -> participant.getStatus().occupiesSeat())
            .count();
        if (occupied < MAX_PARTICIPANTS) return;
        throw new BusinessException(ErrorCode.SESSION_FULL);
    }

    private void validateActive(Participant participant) {
        if (participant.isActive()) return;
        throw new BusinessException(ErrorCode.PARTICIPANT_NOT_ACTIVE);
    }

    // 끊긴(DISCONNECTED) 상태에서도 나가기는 허용한다
    private void validateNotLeft(Participant participant) {
        if (participant.getStatus().occupiesSeat()) return;
        throw new BusinessException(ErrorCode.PARTICIPANT_NOT_ACTIVE);
    }

    // 현재 세션 상태 + 참여자 (동기 Projection이라 항상 최신)
    @Transactional(readOnly = true)
    public SessionDetailResponse getSession(Long sessionId) {
        Session session = findSession(sessionId);
        return SessionDetailResponse.of(session, participantRepository.findBySessionId(sessionId));
    }

    // 세션이 없으면 404. event 패키지는 session을 모르므로 존재 확인은 여기서 한다
    @Transactional(readOnly = true)
    public EventListResponse getEvents(Long sessionId, EventRangeRequest range) {
        findSession(sessionId);
        return eventService.findEvents(
            sessionId,
            range.fromSequenceOrDefault(),
            range.toSequenceOrDefault(),
            range.limitOrDefault()
        );
    }

    /**
     * 세션 목록 (필터 + 커서 페이징).
     * size + 1개를 조회해 다음 페이지 여부를 판단하고, 참여자 이름은 페이지의 세션 id들로 한 번에 조회해 N+1을 피한다.
     */
    @Transactional(readOnly = true)
    public SessionListResponse searchSessions(SessionSearchCondition condition) {
        int size = condition.size();
        List<Session> fetched = sessionRepository.findBy(
            SessionSpecs.matches(condition),
            query -> query.sortBy(Sort.by(Sort.Direction.DESC, "id")).limit(size + 1).all()
        );
        List<Session> page = fetched.stream().limit(size).toList();
        Long nextCursor = fetched.size() > size ? page.getLast().getId() : null;
        return SessionListResponse.of(page, findParticipantNames(page), nextCursor);
    }

    private Session findSession(Long sessionId) {
        return sessionRepository.findById(sessionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.SESSION_NOT_FOUND));
    }

    private Map<Long, List<String>> findParticipantNames(List<Session> sessions) {
        if (sessions.isEmpty()) return Map.of();

        List<Long> sessionIds = sessions.stream().map(Session::getId).toList();
        return participantRepository.findBySessionIdIn(sessionIds).stream()
            .collect(Collectors.groupingBy(
                Participant::getSessionId,
                Collectors.mapping(Participant::getDisplayName, Collectors.toList())
            ));
    }

    /**
     * WS 연결 전 검증. 연결을 등록하면 같은 참여자의 기존 연결이 끊기므로, 남의 연결을 끊지 못하게 등록 전에 확인한다.
     * 세션 없음/다른 세션 소속 → 404, 세션 종료/이미 나간 참여자 → 409.
     */
    @Transactional(readOnly = true)
    public void validateConnection(
        Long sessionId,
        Long participantId
    ) {
        Session session = findSession(sessionId);
        validateNotCompleted(session);
        validateNotLeft(findParticipant(sessionId, participantId));
    }

    /**
     * WS 연결 등록 직후 호출한다. 끊겼던 참여자(DISCONNECTED)면 ACTIVE로 되돌리고 RECONNECT 이벤트를 만든다.
     * 연결 교체처럼 이미 ACTIVE면 아무것도 하지 않는다.
     * 등록보다 먼저 하면 그 사이에 옛 연결의 끊김이 처리돼 멀쩡히 접속한 참여자가 DISCONNECTED로 남을 수 있다.
     * 반대로 등록과 connect가 옛 연결의 끊김 처리보다 먼저 끝나는 경우는 disconnect가 새 연결을 확인해서 막는다.
     * validateConnection 이후 상태가 바뀌었을 수 있어 Lock 안에서 다시 검증한다 (그 사이 LEAVE 등).
     */
    @Transactional
    public void connect(
        Long sessionId,
        Long participantId
    ) {
        Session session = lockSession(sessionId);
        validateNotCompleted(session);
        Participant participant = findParticipant(sessionId, participantId);
        validateNotLeft(participant);
        if (participant.getStatus() != ParticipantStatus.DISCONNECTED) return;

        LocalDateTime now = dbClock.now();
        participant.reconnect(now);
        recalculateStatus(session);
        long sequence = session.issueNextSequence(now);
        eventService.append(serverEvent(sessionId, EventType.RECONNECT, participantId), sequence, now);
    }

    /**
     * 명단에 있던 연결이 끊겼을 때 호출한다. ACTIVE일 때만 DISCONNECTED로 바꾸고 DISCONNECT 이벤트를 만든다.
     * 이미 LEFT(LEAVE, 세션 종료로 닫힌 경우)거나 DISCONNECTED면 아무것도 하지 않는다.
     * 상태 확인과 이벤트 생성을 Lock 안에서 하므로 같은 끊김이 두 번 처리돼도 이벤트는 하나만 생긴다.
     *
     * 옛 연결이 명단에서 빠진 뒤 이 Lock을 잡기 전에 새 연결이 등록되고 connect까지 끝났을 수 있다.
     * 그때 connect는 아직 ACTIVE라서 아무것도 하지 않았으므로, 여기서 DISCONNECTED로 바꾸면 살아 있는 연결이 오프라인으로 남는다.
     * 그래서 Lock 안에서 새 연결이 있는지(hasNewConnection) 다시 확인하고, 있으면 건너뛴다.
     * 새 연결은 connect보다 등록을 먼저 하므로, 이 확인 뒤에 등록된 연결은 connect에서 RECONNECT로 복구된다.
     * session이 realtime을 알지 않도록 연결 명단 확인은 호출하는 쪽(WS 핸들러)이 함수로 넘긴다.
     */
    @Transactional
    public void disconnect(
        Long sessionId,
        Long participantId,
        BooleanSupplier hasNewConnection
    ) {
        Session session = lockSession(sessionId);
        if (session.isCompleted()) return;
        Participant participant = findParticipant(sessionId, participantId);
        if (!participant.isActive()) return;
        if (hasNewConnection.getAsBoolean()) return;

        LocalDateTime now = dbClock.now();
        participant.disconnect(now);
        recalculateStatus(session);
        long sequence = session.issueNextSequence(now);
        eventService.append(serverEvent(sessionId, EventType.DISCONNECT, participantId), sequence, now);
    }

    // 서버가 만드는 이벤트라 eventId도 서버가 만든다. 클라이언트 재시도가 없으므로 중복 확인 대상이 아니다
    private NewEvent serverEvent(
        Long sessionId,
        EventType eventType,
        Long participantId
    ) {
        return new NewEvent(
            UUID.randomUUID().toString(),
            sessionId,
            eventType,
            participantId,
            null,
            null,
            null
        );
    }

}

package com.creativedigital.chat.event.service;

import com.creativedigital.chat.common.error.BusinessException;
import com.creativedigital.chat.common.error.ErrorCode;
import com.creativedigital.chat.event.dto.AppendResult;
import com.creativedigital.chat.event.dto.EventAppended;
import com.creativedigital.chat.event.dto.EventListResponse;
import com.creativedigital.chat.event.dto.EventResponse;
import com.creativedigital.chat.event.dto.EventResult;
import com.creativedigital.chat.event.dto.NewEvent;
import com.creativedigital.chat.event.entity.Event;
import com.creativedigital.chat.event.entity.EventType;
import com.creativedigital.chat.event.repository.EventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 이벤트 저장소 담당.
 * 트랜잭션과 session 행 Lock은 호출하는 SessionService가 소유하고, 이 서비스는 그 트랜잭션 안에서 실행된다.
 * 엔티티는 밖으로 내보내지 않고 EventResult로 변환해 반환한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EventService {

    private static final String METRIC_NAME = "event.append";

    private final EventRepository eventRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final JsonMapper jsonMapper;
    private final MeterRegistry meterRegistry;

    /**
     * 같은 eventId가 이미 저장돼 있으면 재시도로 보고 기존 결과를 반환한다.
     * 내용이 다르면 정상적인 재시도가 아니므로 409.
     * session 행 Lock 안에서 호출해야 동시에 들어온 같은 요청도 순서대로 판별된다.
     */
    public Optional<AppendResult> findDuplicate(NewEvent newEvent) {
        return eventRepository.findByEventId(newEvent.eventId())
            .map(existing -> toDuplicateResult(existing, newEvent));
    }

    public AppendResult append(
        NewEvent newEvent,
        long sequence,
        LocalDateTime createdAt
    ) {
        Event event = eventRepository.save(
            Event.create(
                newEvent.eventId(),
                newEvent.sessionId(),
                sequence,
                newEvent.eventType(),
                newEvent.participantId(),
                newEvent.targetEventId(),
                serialize(newEvent.payload()),
                resolveOccurredAt(newEvent.occurredAt(), createdAt),
                createdAt
            ));
        EventResult result = EventResult.of(event, newEvent.payload());
        eventPublisher.publishEvent(new EventAppended(result));
        // 트랜잭션 안이라 커밋 전에 찍힌다. append가 모든 경로의 마지막 단계라 이후 실패는 커밋 실패뿐이고, 그때는 에러 로그가 따로 남는다
        log.info(
            "event_appended sessionId={} eventId={} sequence={} eventType={}",
            result.sessionId(),
            result.eventId(),
            result.sequence(),
            result.eventType()
        );
        recordAppend(result.eventType(), "created");
        return AppendResult.created(result);
    }

    private AppendResult toDuplicateResult(Event existing, NewEvent newEvent) {
        if (!isSameRequest(existing, newEvent)) {
            log.warn(
                "event_id_conflict sessionId={} eventId={} eventType={}",
                newEvent.sessionId(),
                newEvent.eventId(),
                newEvent.eventType()
            );
            recordAppend(newEvent.eventType(), "conflict");
            throw new BusinessException(ErrorCode.EVENT_ID_CONFLICT);
        }
        EventResult result = EventResult.of(existing, deserialize(existing.getPayload()));
        log.info(
            "event_duplicate sessionId={} eventId={} sequence={} eventType={}",
            result.sessionId(),
            result.eventId(),
            result.sequence(),
            result.eventType()
        );
        recordAppend(result.eventType(), "duplicate");
        return AppendResult.duplicate(result);
    }

    /**
     * 클라이언트가 보낸 값을 비교한다.
     * 서버가 발급하는 값(세션 생성의 세션 번호, JOIN의 참여자 번호)은 재시도 요청에 있을 수 없으므로 타입별로 비교에서 제외한다.
     * null이면 생략하는 방식은 쓰지 않는다. 값이 빠진 요청이 남의 이벤트를 재시도 결과로 받아가지 않도록, 비교 대상인데 빠져 있으면 다른 요청으로 본다.
     */
    private boolean isSameRequest(Event existing, NewEvent newEvent) {
        EventType eventType = newEvent.eventType();
        return existing.getEventType() == eventType
            && (eventType.isSessionIdAssignedByServer() || Objects.equals(existing.getSessionId(), newEvent.sessionId()))
            && (eventType.isParticipantIdAssignedByServer() || Objects.equals(existing.getParticipantId(), newEvent.participantId()))
            && Objects.equals(existing.getTargetEventId(), newEvent.targetEventId())
            && Objects.equals(deserialize(existing.getPayload()), newEvent.payload());
    }

    private String serialize(JsonNode payload) {
        if (payload == null) return null;
        return jsonMapper.writeValueAsString(payload);
    }

    // JsonNode.equals는 키 순서와 무관하게 내용으로 비교한다
    private JsonNode deserialize(String payload) {
        if (payload == null) return null;
        return jsonMapper.readTree(payload);
    }

    private LocalDateTime resolveOccurredAt(LocalDateTime occurredAt, LocalDateTime createdAt) {
        return occurredAt != null ? occurredAt : createdAt;
    }

    // 수정/삭제 대상 메시지 조회. 같은 세션의 MESSAGE 이벤트만 대상이다
    public Optional<EventResult> findMessage(Long sessionId, String messageId) {
        return eventRepository.findByEventId(messageId)
            .filter(event -> event.getEventType() == EventType.MESSAGE)
            .filter(event -> event.getSessionId().equals(sessionId))
            .map(event -> EventResult.of(event, deserialize(event.getPayload())));
    }

    public boolean isMessageDeleted(String messageId) {
        return eventRepository.existsByTargetEventIdAndEventType(messageId, EventType.MESSAGE_DELETED);
    }

    /**
     * sequence 범위 조회 (fromSequence 초과 ~ toSequence 이하).
     * limit + 1개를 조회해서 다음 페이지가 있는지 COUNT 쿼리 없이 판단한다.
     */
    @Transactional(readOnly = true)
    public EventListResponse findEvents(
        Long sessionId,
        long fromSequence,
        long toSequence,
        int limit
    ) {
        List<Event> events = eventRepository.findRange(
            sessionId,
            fromSequence,
            toSequence,
            Limit.of(limit + 1)
        );
        boolean hasMore = events.size() > limit;
        List<EventResponse> page = events.stream()
            .limit(limit)
            .map(event -> EventResponse.from(EventResult.of(event, deserialize(event.getPayload()))))
            .toList();
        return new EventListResponse(page, hasMore);
    }

    // timeline?at= → 그 시각까지 서버가 저장한 마지막 sequence. 세션 생성 이전 시각이면 비어 있다
    @Transactional(readOnly = true)
    public Optional<Long> findLastSequenceAt(
        Long sessionId,
        LocalDateTime at
    ) {
        return eventRepository.findLastSequenceAtOrBefore(sessionId, at, Limit.of(1));
    }

    // Replay용 범위 조회 (fromSequence 초과 ~ toSequence 이하). 응답 DTO가 아니라 EventResult로 반환해 Reducer가 바로 적용한다
    @Transactional(readOnly = true)
    public List<EventResult> findEventsForReplay(
        Long sessionId,
        long fromSequence,
        long toSequence,
        int limit
    ) {
        return eventRepository.findRange(
                sessionId,
                fromSequence,
                toSequence,
                Limit.of(limit)
            ).stream()
            .map(event -> EventResult.of(event, deserialize(event.getPayload())))
            .toList();
    }

    // event.append{type, result}: 처리량과 재시도(duplicate) 비율, 0이어야 정상인 conflict를 본다
    private void recordAppend(EventType eventType, String result) {
        meterRegistry.counter(
            METRIC_NAME,
            "type", eventType.name(),
            "result", result
        ).increment();
    }

}

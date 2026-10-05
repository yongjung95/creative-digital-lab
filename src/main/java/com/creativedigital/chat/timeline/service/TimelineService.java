package com.creativedigital.chat.timeline.service;

import com.creativedigital.chat.common.error.BusinessException;
import com.creativedigital.chat.common.error.ErrorCode;
import com.creativedigital.chat.event.dto.EventResult;
import com.creativedigital.chat.event.service.EventService;
import com.creativedigital.chat.session.entity.Session;
import com.creativedigital.chat.session.repository.SessionRepository;
import com.creativedigital.chat.timeline.config.SnapshotProperties;
import com.creativedigital.chat.timeline.dto.TimelineResponse;
import com.creativedigital.chat.timeline.entity.Snapshot;
import com.creativedigital.chat.timeline.repository.SnapshotRepository;
import com.creativedigital.chat.timeline.state.SessionState;
import com.creativedigital.chat.timeline.state.SessionStateReducer;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * 특정 시점의 세션 상태를 복원한다.
 * 외부 요청이 시점(at)으로 오면 입구에서 sequence로 한 번 변환하고, 이후는 sequence 기준 하나의 로직을 탄다.
 * 목표 sequence 이하의 가장 가까운 Snapshot에서 시작해 이후 이벤트만 Replay한다. Snapshot이 없으면 처음부터 Replay한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TimelineService {

    private static final int REPLAY_CHUNK_SIZE = 500;

    private final SessionRepository sessionRepository;
    private final SnapshotRepository snapshotRepository;
    private final EventService eventService;
    private final SnapshotProperties snapshotProperties;
    private final JsonMapper jsonMapper;
    private final EntityManager entityManager;

    /**
     * at과 sequence는 nullable이며 둘 중 정확히 하나만 있어야 한다.
     */
    @Transactional(readOnly = true)
    public TimelineResponse restore(
        Long sessionId,
        LocalDateTime at,
        Long sequence
    ) {
        if ((at == null) == (sequence == null)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }

        Session session = sessionRepository.findById(sessionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.SESSION_NOT_FOUND));

        long targetSequence = sequence != null
            ? validateSequence(session, sequence)
            : resolveSequenceAt(sessionId, at);

        RestoredState restored = restoreState(sessionId, targetSequence);
        return TimelineResponse.of(
            sessionId,
            restored.state(),
            restored.snapshotSequence(),
            restored.replayedEventCount()
        );
    }

    private long validateSequence(Session session, long sequence) {
        if (sequence >= 1 && sequence <= session.getLastSequence()) return sequence;
        throw new BusinessException(ErrorCode.INVALID_REQUEST);
    }

    // 세션 생성 이전 시각이면 그 시점엔 세션이 없었으므로 400. 미래 시각은 현재 상태(마지막 sequence)가 된다
    private long resolveSequenceAt(Long sessionId, LocalDateTime at) {
        return eventService.findLastSequenceAt(sessionId, at)
            .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REQUEST));
    }

    /**
     * 가장 가까운 Snapshot을 읽는다. JSON을 읽을 수 없으면(데이터 손상 등) 쓰지 않고 처음부터 Replay한다.
     * Snapshot은 복원 최적화용이라 못 써도 결과는 같고 느려질 뿐이다.
     */
    private Optional<SessionState> findSnapshotState(Long sessionId, long targetSequence) {
        return snapshotRepository.findFirstBySessionIdAndSequenceLessThanEqualOrderBySequenceDesc(
                sessionId,
                targetSequence
            )
            .flatMap(this::readSnapshot);
    }

    private Optional<SessionState> readSnapshot(Snapshot snapshot) {
        try {
            return Optional.of(jsonMapper.readValue(snapshot.getState(), SessionState.class));
        } catch (JacksonException e) {
            log.warn(
                "snapshot_read_failed sessionId={} sequence={}",
                snapshot.getSessionId(),
                snapshot.getSequence(),
                e
            );
            return Optional.empty();
        }
    }

    /**
     * fromSequence 초과 ~ toSequence 이하 이벤트를 나눠서 적용한다.
     * Snapshot이 없는 긴 세션도 이벤트를 한 번에 메모리에 올리지 않도록, 묶음마다 영속성 컨텍스트를 비운다.
     */
    private int replay(
        SessionStateReducer reducer,
        Long sessionId,
        long fromSequence,
        long toSequence
    ) {
        int replayedEventCount = 0;
        long cursor = fromSequence;
        while (cursor < toSequence) {
            List<EventResult> events = eventService.findEventsForReplay(
                sessionId,
                cursor,
                toSequence,
                REPLAY_CHUNK_SIZE
            );
            if (events.isEmpty()) {
                throw new IllegalStateException(
                    "Replay할 이벤트가 없습니다. sessionId=" + sessionId + " fromSequence=" + cursor);
            }

            events.forEach(reducer::apply);
            replayedEventCount += events.size();
            cursor = events.getLast().sequence();
            entityManager.clear();
        }
        return replayedEventCount;
    }

    /**
     * 목표 sequence 시점의 상태를 가장 가까운 Snapshot + 이후 Replay로 계산한다.
     * timeline API와 Snapshot 생성(SnapshotService)이 공유한다. targetSequence는 호출한 쪽이 검증한 값이다.
     * 호출한 쪽의 트랜잭션 안에서 실행되며, Replay 중 영속성 컨텍스트를 비우므로 이전에 읽은 엔티티는 준영속 상태가 된다.
     */
    RestoredState restoreState(Long sessionId, long targetSequence) {
        Optional<SessionState> snapshotState = findSnapshotState(sessionId, targetSequence);
        SessionStateReducer reducer = snapshotState
            .map(state -> SessionStateReducer.from(state, snapshotProperties.recentMessages()))
            .orElseGet(() -> SessionStateReducer.empty(snapshotProperties.recentMessages()));
        Long snapshotSequence = snapshotState.map(SessionState::sequence).orElse(null);

        int replayedEventCount = replay(
            reducer,
            sessionId,
            snapshotSequence != null ? snapshotSequence : 0,
            targetSequence
        );
        log.debug(
            "state_restored sessionId={} targetSequence={} snapshotSequence={} replayedEventCount={}",
            sessionId,
            targetSequence,
            snapshotSequence,
            replayedEventCount
        );
        return new RestoredState(
            reducer.toState(),
            snapshotSequence,
            replayedEventCount
        );
    }

    // Snapshot 없이 처음부터 Replay했으면 snapshotSequence는 null
    record RestoredState(
        SessionState state,
        Long snapshotSequence,
        int replayedEventCount
    ) {

    }

}

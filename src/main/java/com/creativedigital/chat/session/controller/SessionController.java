package com.creativedigital.chat.session.controller;

import com.creativedigital.chat.common.error.ApiErrorCodes;
import com.creativedigital.chat.common.error.ErrorCode;
import com.creativedigital.chat.event.dto.AppendResult;
import com.creativedigital.chat.event.dto.EventListResponse;
import com.creativedigital.chat.event.dto.EventResponse;
import com.creativedigital.chat.session.dto.ClientEventRequest;
import com.creativedigital.chat.session.dto.CreateSessionRequest;
import com.creativedigital.chat.session.dto.CreateSessionResponse;
import com.creativedigital.chat.session.dto.EndSessionRequest;
import com.creativedigital.chat.session.dto.EndSessionResponse;
import com.creativedigital.chat.session.dto.EventRangeRequest;
import com.creativedigital.chat.session.dto.JoinSessionRequest;
import com.creativedigital.chat.session.dto.JoinSessionResponse;
import com.creativedigital.chat.session.dto.SessionDetailResponse;
import com.creativedigital.chat.session.dto.SessionListResponse;
import com.creativedigital.chat.session.dto.SessionSearchRequest;
import com.creativedigital.chat.session.service.SessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 세션 Command REST 입구. 요청을 커맨드로 바꿔 SessionService에 넘기기만 한다.
 * 처음 처리한 요청은 201, 같은 eventId의 재시도는 200 + 같은 body.
 */
@Tag(name = "세션")
@RestController
@RequestMapping("/sessions")
@RequiredArgsConstructor
public class SessionController {

    private final SessionService sessionService;

    @Operation(summary = "세션 생성", description = "같은 eventId로 다시 보내면 처음 만든 세션을 돌려준다.")
    @ApiResponse(responseCode = "201", description = "생성")
    @ApiResponse(responseCode = "200", description = "같은 eventId 재시도 (처음 결과)")
    @ApiErrorCodes({ErrorCode.EVENT_ID_CONFLICT})
    @PostMapping
    public ResponseEntity<CreateSessionResponse> createSession(@Valid @RequestBody CreateSessionRequest request) {
        AppendResult result = sessionService.createSession(request.eventId());
        return ResponseEntity.status(createdOrOk(result))
            .body(CreateSessionResponse.from(result.event()));
    }

    @Operation(
        summary = "세션 참여",
        description = "참여자 번호(participantId)를 발급한다. 이 번호로 WebSocket에 연결한다. "
            + "정원은 2명이며 연결이 끊긴 참여자도 자리를 차지한다."
    )
    @ApiResponse(responseCode = "201", description = "참여")
    @ApiResponse(responseCode = "200", description = "같은 eventId 재시도 (처음 결과)")
    @ApiErrorCodes({
        ErrorCode.SESSION_NOT_FOUND,
        ErrorCode.SESSION_FULL,
        ErrorCode.SESSION_COMPLETED,
        ErrorCode.EVENT_ID_CONFLICT,
        ErrorCode.SESSION_BUSY
    })
    @PostMapping("/{sessionId}/join")
    public ResponseEntity<JoinSessionResponse> join(
        @PathVariable Long sessionId,
        @Valid @RequestBody JoinSessionRequest request
    ) {
        AppendResult result = sessionService.join(sessionId, request.toCommand());
        return ResponseEntity.status(createdOrOk(result))
            .body(JoinSessionResponse.from(result.event()));
    }

    @Operation(
        summary = "이벤트 수집 (메시지 전송/수정/삭제, 나가기)",
        description = "type은 MESSAGE, MESSAGE_EDITED, MESSAGE_DELETED, LEAVE. "
            + "DISCONNECT/RECONNECT는 서버가 WebSocket 연결 상태를 보고 만들기 때문에 보낼 수 없다(400). "
            + "WebSocket의 SEND_EVENT로 보내도 같은 처리를 거친다."
    )
    @ApiResponse(responseCode = "201", description = "저장")
    @ApiResponse(responseCode = "200", description = "같은 eventId 재시도 (처음 결과)")
    @ApiErrorCodes({
        ErrorCode.NOT_MESSAGE_OWNER,
        ErrorCode.SESSION_NOT_FOUND,
        ErrorCode.PARTICIPANT_NOT_FOUND,
        ErrorCode.MESSAGE_NOT_FOUND,
        ErrorCode.SESSION_COMPLETED,
        ErrorCode.PARTICIPANT_NOT_ACTIVE,
        ErrorCode.MESSAGE_ALREADY_DELETED,
        ErrorCode.EVENT_ID_CONFLICT,
        ErrorCode.SESSION_BUSY
    })
    @PostMapping("/{sessionId}/events")
    public ResponseEntity<EventResponse> appendEvent(
        @PathVariable Long sessionId,
        @Valid @RequestBody ClientEventRequest request
    ) {
        AppendResult result = sessionService.appendClientEvent(sessionId, request.toCommand());
        return ResponseEntity.status(createdOrOk(result))
            .body(EventResponse.from(result.event()));
    }

    @Operation(
        summary = "세션 종료",
        description = "남은 참여자는 모두 나간 것으로 처리한다. 되돌릴 수 없고, 이후 상태를 바꾸는 요청은 409."
    )
    @ApiResponse(responseCode = "200", description = "종료 (같은 eventId 재시도도 처음 결과로 200)")
    @ApiErrorCodes({
        ErrorCode.SESSION_NOT_FOUND,
        ErrorCode.SESSION_COMPLETED,
        ErrorCode.EVENT_ID_CONFLICT,
        ErrorCode.SESSION_BUSY
    })
    @PostMapping("/{sessionId}/end")
    public ResponseEntity<EndSessionResponse> endSession(
        @PathVariable Long sessionId,
        @Valid @RequestBody EndSessionRequest request
    ) {
        AppendResult result = sessionService.endSession(sessionId, request.eventId());
        return ResponseEntity.ok(EndSessionResponse.from(result.event()));
    }

    private HttpStatus createdOrOk(AppendResult result) {
        return result.duplicate() ? HttpStatus.OK : HttpStatus.CREATED;
    }

    @Operation(
        summary = "세션 목록",
        description = "최신 세션부터. 다음 페이지는 응답의 nextCursor를 cursor로 보낸다."
    )
    @GetMapping
    public SessionListResponse searchSessions(@Valid @ParameterObject @ModelAttribute SessionSearchRequest request) {
        return sessionService.searchSessions(request.toCondition());
    }

    @Operation(
        summary = "현재 세션 상태와 참여자",
        description = "세션/참여자 상태는 이벤트 저장과 같은 트랜잭션에서 갱신되므로 항상 최신이다."
    )
    @ApiErrorCodes({ErrorCode.SESSION_NOT_FOUND})
    @GetMapping("/{sessionId}")
    public SessionDetailResponse getSession(@PathVariable Long sessionId) {
        return sessionService.getSession(sessionId);
    }

    @Operation(
        summary = "이벤트 범위 조회",
        description = "fromSequence 초과 ~ toSequence 이하를 sequence 순서로 돌려준다. "
            + "재연결 때 빠진 구간을 채우거나 디버깅할 때 쓴다. 시각 기준 조회는 timeline API가 담당한다."
    )
    @ApiErrorCodes({ErrorCode.SESSION_NOT_FOUND})
    @GetMapping("/{sessionId}/events")
    public EventListResponse getEvents(
        @PathVariable Long sessionId,
        @Valid @ParameterObject @ModelAttribute EventRangeRequest request
    ) {
        return sessionService.getEvents(sessionId, request);
    }

}

package com.creativedigital.chat.timeline.controller;

import com.creativedigital.chat.common.error.ApiErrorCodes;
import com.creativedigital.chat.common.error.ErrorCode;
import com.creativedigital.chat.common.time.KstTime;
import com.creativedigital.chat.timeline.dto.SnapshotResponse;
import com.creativedigital.chat.timeline.dto.SnapshotResult;
import com.creativedigital.chat.timeline.dto.TimelineRequest;
import com.creativedigital.chat.timeline.dto.TimelineResponse;
import com.creativedigital.chat.timeline.service.SnapshotService;
import com.creativedigital.chat.timeline.service.TimelineService;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 과거 상태 복원 입구. 현재 상태는 GET /sessions/{id}(동기 Projection)가 담당한다.
 */
@Tag(name = "복원")
@RestController
@RequestMapping("/sessions")
@RequiredArgsConstructor
public class TimelineController {

    private final TimelineService timelineService;
    private final SnapshotService snapshotService;

    @Operation(
        summary = "특정 시점 상태 복원",
        description = "at(시각)과 sequence 중 하나만 보낸다. at은 그 시각까지 서버가 저장한 마지막 이벤트를 기준으로 하며, "
            + "미래 시각이면 현재 상태를 돌려준다. restoredFrom으로 어떤 Snapshot에서 몇 개를 Replay했는지 알 수 있다."
    )
    @ApiErrorCodes({ErrorCode.SESSION_NOT_FOUND})
    @GetMapping("/{sessionId}/timeline")
    public TimelineResponse getTimeline(
        @PathVariable Long sessionId,
        @Valid @ParameterObject @ModelAttribute TimelineRequest request
    ) {
        return timelineService.restore(
            sessionId,
            KstTime.toKst(request.at()),
            request.sequence()
        );
    }

    // 현재 last_sequence 기준 수동 생성. 처음 만들면 201, 이미 있으면 200
    @Operation(
        summary = "Snapshot 수동 생성",
        description = "현재 마지막 sequence 기준으로 만든다. Snapshot은 일정 간격마다 자동으로 만들어지며, 이 API는 확인/디버깅용이다."
    )
    @ApiResponse(responseCode = "201", description = "생성")
    @ApiResponse(responseCode = "200", description = "같은 sequence의 Snapshot이 이미 있음")
    @ApiErrorCodes({ErrorCode.SESSION_NOT_FOUND})
    @PostMapping("/{sessionId}/snapshots")
    public ResponseEntity<SnapshotResponse> createSnapshot(@PathVariable Long sessionId) {
        SnapshotResult result = snapshotService.createLatest(sessionId);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
            .body(SnapshotResponse.from(result));
    }

}

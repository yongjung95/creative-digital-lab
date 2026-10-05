package com.creativedigital.chat.message.controller;

import com.creativedigital.chat.common.error.ApiErrorCodes;
import com.creativedigital.chat.common.error.ErrorCode;
import com.creativedigital.chat.message.dto.MessageListRequest;
import com.creativedigital.chat.message.dto.MessageListResponse;
import com.creativedigital.chat.message.service.MessageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 현재 메시지 목록 입구. 과거 시점 메시지는 timeline API가 담당한다.
 */
@Tag(name = "메시지")
@RestController
@RequestMapping("/sessions")
@RequiredArgsConstructor
public class MessageController {

    private final MessageService messageService;

    @Operation(
        summary = "현재 메시지 목록",
        description = "최신순. 비동기로 반영되므로 방금 저장된 이벤트가 잠깐 늦게 보일 수 있고, projectedSequence가 반영된 위치다. "
            + "이전 페이지는 마지막 메시지의 sequence를 beforeSequence로 보낸다."
    )
    @ApiErrorCodes({ErrorCode.SESSION_NOT_FOUND})
    @GetMapping("/{sessionId}/messages")
    public MessageListResponse getMessages(
        @PathVariable Long sessionId,
        @Valid @ParameterObject @ModelAttribute MessageListRequest request
    ) {
        return messageService.getMessages(
            sessionId,
            request.beforeSequenceOrDefault(),
            request.sizeOrDefault()
        );
    }

}

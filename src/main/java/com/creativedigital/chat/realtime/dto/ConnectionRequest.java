package com.creativedigital.chat.realtime.dto;

import com.creativedigital.chat.common.error.BusinessException;
import com.creativedigital.chat.common.error.ErrorCode;
import java.net.URI;
import java.util.List;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 연결 주소에서 꺼낸 값: ws://{host}/ws/sessions/{sessionId}?participantId={participantId}&lastAppliedSequence={n}.
 * lastAppliedSequence는 클라이언트가 마지막으로 반영한 이벤트 번호이고, 없으면 0(처음부터)이다.
 * 숫자가 아니거나 범위를 벗어나면 INVALID_REQUEST (close 4400).
 */
public record ConnectionRequest(
    Long sessionId,
    Long participantId,
    long lastAppliedSequence
) {

    public static ConnectionRequest from(URI uri) {
        if (uri == null) throw new BusinessException(ErrorCode.INVALID_REQUEST);

        UriComponents components = UriComponentsBuilder.fromUri(uri).build();
        List<String> pathSegments = components.getPathSegments();
        MultiValueMap<String, String> queryParams = components.getQueryParams();
        String lastAppliedSequence = queryParams.getFirst("lastAppliedSequence");
        return new ConnectionRequest(
            parseId(pathSegments.isEmpty() ? null : pathSegments.getLast()),
            parseId(queryParams.getFirst("participantId")),
            lastAppliedSequence != null ? parseNumber(lastAppliedSequence, 0) : 0
        );
    }

    private static Long parseId(String value) {
        if (value == null) throw new BusinessException(ErrorCode.INVALID_REQUEST);
        return parseNumber(value, 1);
    }

    private static long parseNumber(
        String value,
        long min
    ) {
        try {
            long number = Long.parseLong(value);
            if (number < min) throw new BusinessException(ErrorCode.INVALID_REQUEST);
            return number;
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
    }

}

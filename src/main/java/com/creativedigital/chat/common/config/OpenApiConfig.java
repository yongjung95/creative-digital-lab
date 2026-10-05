package com.creativedigital.chat.common.config;

import com.creativedigital.chat.common.error.ApiErrorCodes;
import com.creativedigital.chat.common.error.ErrorCode;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.models.examples.Example;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.method.HandlerMethod;
import tools.jackson.databind.JsonNode;

@OpenAPIDefinition(
    // Swagger 화면에 이 순서대로 보인다
    tags = {
        @Tag(name = "세션", description = "세션 생성/참여/종료, 이벤트 수집과 조회"),
        @Tag(name = "복원", description = "특정 시점 상태 복원 (Snapshot + Replay)"),
        @Tag(name = "메시지", description = "현재 메시지 목록 (수정/삭제 반영)")
    },
    info = @Info(
        title = "실시간 1:1 채팅 + 이벤트 기반 상태 복원 API",
        version = "v1",
        description = """
            세션 생성/참여, 이벤트 수집과 조회, 특정 시점 상태 복원 API.
            실시간 송수신은 WebSocket(`/ws/sessions/{sessionId}`)으로 하며 프로토콜은 `docs/websocket.md`에 있다.

            **멱등성**: 상태를 바꾸는 요청은 클라이언트가 만든 `eventId`(소문자 UUID)를 받는다.
            처음 저장하면 201, 같은 eventId와 같은 내용으로 다시 보내면 200과 처음 결과,
            내용이 다르면 409 `EVENT_ID_CONFLICT`.

            **에러 형식**: RFC 7807 ProblemDetail(`application/problem+json`)에 확장 속성 `code`를 더한다.
            클라이언트는 `code`로 분기한다.
            """
    )
)
@Configuration
public class OpenApiConfig {

    private static final String PROBLEM_MEDIA_TYPE = "application/problem+json";

    // payload(JsonNode)를 Jackson 내부 필드 대신 임의 JSON 객체로 표시한다
    static {
        SpringDocUtils.getConfig().replaceWithSchema(JsonNode.class, new ObjectSchema());
    }

    /**
     * @ApiErrorCodes에 적힌 ErrorCode로 에러 응답을 만든다.
     * 상태 코드와 메시지를 enum에서 읽으므로 코드와 문서가 어긋나지 않고, 같은 상태 코드도 code별 예시로 구분된다.
     */
    @Bean
    public OperationCustomizer errorCodeCustomizer() {
        return (operation, handlerMethod) -> {
            Map<HttpStatus, List<ErrorCode>> codesByStatus = resolveErrorCodes(handlerMethod).stream()
                .collect(Collectors.groupingBy(ErrorCode::getStatus, TreeMap::new, Collectors.toList()));
            codesByStatus.forEach((status, codes) -> operation.getResponses()
                .addApiResponse(String.valueOf(status.value()), errorResponse(codes)));
            return operation;
        };
    }

    // 모든 API가 파라미터를 받고(형식 오류) DB를 쓰므로(커넥션 획득 실패) INVALID_REQUEST, SERVER_BUSY는 항상 포함한다
    private List<ErrorCode> resolveErrorCodes(HandlerMethod handlerMethod) {
        ApiErrorCodes annotation = handlerMethod.getMethodAnnotation(ApiErrorCodes.class);
        Stream<ErrorCode> declared = annotation != null ? Arrays.stream(annotation.value()) : Stream.empty();
        return Stream.concat(Stream.of(ErrorCode.INVALID_REQUEST, ErrorCode.SERVER_BUSY), declared)
            .distinct()
            .toList();
    }

    private ApiResponse errorResponse(List<ErrorCode> codes) {
        MediaType mediaType = new MediaType().schema(problemSchema());
        codes.forEach(code -> mediaType.addExamples(
            code.name(),
            new Example()
                .summary(code.getMessage())
                .value(problemExample(code))
        ));
        return new ApiResponse()
            .description(codes.stream().map(ErrorCode::name).collect(Collectors.joining(", ")))
            .content(new Content().addMediaType(PROBLEM_MEDIA_TYPE, mediaType));
    }

    private Schema<?> problemSchema() {
        return new ObjectSchema()
            .addProperty("title", new StringSchema())
            .addProperty("status", new IntegerSchema())
            .addProperty("detail", new StringSchema())
            .addProperty("instance", new StringSchema())
            .addProperty("code", new StringSchema().description("에러 코드. 클라이언트는 이 값으로 분기한다"));
    }

    private Map<String, Object> problemExample(ErrorCode code) {
        Map<String, Object> example = new LinkedHashMap<>();
        example.put("title", code.getStatus().getReasonPhrase());
        example.put("status", code.getStatus().value());
        example.put("detail", code.getMessage());
        example.put("code", code.name());
        return example;
    }

}

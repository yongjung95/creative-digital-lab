package com.creativedigital.chat.common.error;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * API가 응답할 수 있는 에러 코드. OpenApiConfig가 읽어서 Swagger 문서의 에러 응답을 만든다.
 * 400 INVALID_REQUEST는 모든 API에 자동으로 추가되므로 적지 않는다.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ApiErrorCodes {

    ErrorCode[] value();

}

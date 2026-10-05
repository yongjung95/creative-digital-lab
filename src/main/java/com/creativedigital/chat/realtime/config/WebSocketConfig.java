package com.creativedigital.chat.realtime.config;

import com.creativedigital.chat.realtime.handler.ChatWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * raw WebSocket + JSON (STOMP 미사용).
 * 허용 Origin은 기본값(같은 출처)을 유지한다. 다른 사이트의 페이지가 사용자 브라우저로 몰래 연결하는 것을 막는다.
 * 메시지 크기는 컨테이너 기본 제한(Tomcat 8192자)을 따른다. 메시지 내용 최대 2000자보다 충분히 크고, 넘으면 1009로 끊긴다.
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final ChatWebSocketHandler chatWebSocketHandler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(chatWebSocketHandler, "/ws/sessions/*");
    }

}

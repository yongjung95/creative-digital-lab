package com.creativedigital.chat;

import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ChatApplication {

    public static void main(String[] args) {
        // 시각은 KST 기준으로 저장한다. 실행 환경(Docker 컨테이너 등)의 기본 타임존에 의존하지 않도록 고정
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
        SpringApplication.run(ChatApplication.class, args);
    }

}

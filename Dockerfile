# 1단계: 빌드. 테스트는 Testcontainers(Docker 안의 Docker)가 필요해서 이미지 빌드에서는 건너뛴다
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace

# 의존성만 먼저 받아서 소스가 바뀌어도 이 레이어는 캐시를 쓴다
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN ./gradlew dependencies --no-daemon > /dev/null

COPY src src
RUN ./gradlew bootJar -x test --no-daemon

# 2단계: 실행. JRE만 담아 이미지를 작게 유지한다
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /workspace/build/libs/chat-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]

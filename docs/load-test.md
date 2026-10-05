# 부하 테스트

메시지 전송(`POST /sessions/{id}/events`)에 부하를 걸어 두 가지를 확인한 결과.

1. 한 세션에 요청이 몰리면 session 행 Lock 때문에 처리량에 상한이 생기는가 (Hot Session)
2. 세션이 나뉘어 있으면 처리량이 늘어나는가

관련 문서: [설계 문서](design.md) · [쿼리 최적화](query.md)

## 측정 환경

| 항목 | 값 |
|---|---|
| 머신 | MacBook Pro, Apple M2 Max (12코어), 메모리 32GB |
| Docker | Docker Desktop 28.3, VM에 CPU 12개 / 메모리 8GB 할당 |
| 배치 | k6, 앱, MariaDB를 같은 머신의 Docker 컨테이너로 함께 실행. k6는 앱과 같은 Docker 네트워크에서 직접 접속 |
| 도구 | k6 v2.3.0 (`grafana/k6` 이미지) |
| 앱 | Java 25, Spring Boot 4.1.1, Virtual Thread, HikariCP 풀 20개, Lock 대기 3초, Snapshot 간격 1000 |
| DB | MariaDB 11.4 |
| 측정 중 끈 것 | Hibernate SQL 로그 (Lock을 잡은 채 콘솔에 쓰면 측정값이 출력 속도에 묶인다) |

- 요청마다 새 eventId(UUID)로 메시지를 보낸다. 각 VU(가상 사용자)는 응답을 받자마자 다음 요청을 보낸다
- 워밍업 10초(결과 제외) 후 VU 수를 단계별로 늘리며 단계마다 30초씩 측정했다
- k6, 앱, DB가 CPU를 나눠 쓰는 환경이라 절대값보다 **조건에 따른 변화**를 보는 용도다
- 측정 뒤에 바뀐 코드(payload 검증, 에러 분류, 재연결·종료 경계 처리, 시작 시 메시지 목록 복구)는 메시지 저장의 Lock 구간에 영향이 없어 다시 측정하지 않았다

## 실행 방법

**1. 앱 띄우기** (local 프로필 = Snapshot 간격 1000, SQL 로그 끔)

```bash
docker compose -f docker-compose.yml -f load-test/docker-compose.load.yml up -d --build app
```

**2. 시나리오 1: 한 세션 집중** (약 3분 15초)

```bash
docker run --rm --network creative-digital-lab_default -e BASE_URL=http://app:8080 \
  -v "$PWD/load-test:/scripts" grafana/k6 run /scripts/hot-session.js
```

**3. 시나리오 2: 여러 세션 분산** (약 2분 40초, 시작할 때 세션 100개를 만든다)

```bash
docker run --rm --network creative-digital-lab_default -e BASE_URL=http://app:8080 \
  -v "$PWD/load-test:/scripts" grafana/k6 run /scripts/multi-session.js
```

**4. 정합성 확인** (테스트가 끝나고 1분 뒤)

```bash
# load-test/verify.sql 맨 위의 세션 번호를 k6 시작 시 출력된 번호로 바꾼 뒤
docker exec -i chat-mariadb mariadb -uchat -pchat chat < load-test/verify.sql
```

- k6가 끝나면 터미널에 단계별 표가 출력된다
- k6가 UUID 생성과 요약 출력에 쓰는 라이브러리를 `jslib.k6.io`에서 받아 오므로 인터넷 연결이 필요하다
- 실시간 그래프를 보려면 `-p 5665:5665 -e K6_WEB_DASHBOARD=true`를 붙이고 http://localhost:5665 를 연다. `-e K6_WEB_DASHBOARD_EXPORT=/scripts/results/report.html`을 더하면 끝난 뒤 HTML 리포트로 저장된다
- 각 시나리오의 그래프는 `load-test/results/report-*.html`에 있다. GitHub에서는 소스로 보이므로 내려받아 브라우저로 연다
- 스크립트: [`hot-session.js`](../load-test/hot-session.js), [`multi-session.js`](../load-test/multi-session.js), 공통 [`lib/common.js`](../load-test/lib/common.js), 정합성 확인 [`verify.sql`](../load-test/verify.sql)

## 요약

| 질문 | 결과 |
|---|---|
| 한 세션의 처리량 상한 | VU 5부터 **약 1,200~1,300건/s**에서 멈춘다. VU를 늘리면 처리량은 그대로이고 대기 시간만 VU 수에 비례해 늘어난다 |
| 세션을 나누면 | 세션 100개에 나누면 **약 5,700~5,900건/s**로 약 5배 |
| 부하 중 정합성 | 성공 응답 수 = 저장된 메시지 수, 모든 세션의 sequence에 빈틈·중복 없음, 실패 0건 |
| 비동기 작업 | 반영 작업 큐가 넘쳐 작업이 버려졌지만 catch-up과 안전망 스케줄러로 전부 반영됨 |

---

## 시나리오 1: 한 세션 집중

세션 1개, 참여자 2명. 모든 VU가 이 세션에 두 참여자 중 하나로 보낸다. 그래프: [report-hot-session.html](../load-test/results/report-hot-session.html)

| VU | 요청 수 | req/s | med | p95 | p99 | max | 실패 |
|---|---|---|---|---|---|---|---|
| 1 | 23,372 | 779 | 1.14ms | 1.49ms | 3.05ms | 13.05ms | 0 |
| 5 | 36,182 | 1,206 | 3.93ms | 4.81ms | 7.46ms | 18.74ms | 0 |
| 10 | 36,508 | 1,217 | 7.89ms | 9.51ms | 13.84ms | 29.83ms | 0 |
| 20 | 35,354 | 1,178 | 16.41ms | 20.07ms | 26.78ms | 41.10ms | 0 |
| 50 | 38,842 | 1,295 | 33.37ms | 66.73ms | 85.79ms | 213.59ms | 0 |

- **처리량이 VU 5부터 멈춘다.** 같은 세션의 요청은 session 행 Lock 때문에 한 번에 하나씩 처리되므로, 요청 1건이 Lock을 잡는 시간(약 0.8ms)이 처리량의 상한을 정한다
- **중간값 ≈ VU 수 × 약 0.8ms.** 처리 시간은 그대로이고 앞에 줄 선 요청을 기다리는 시간이 늘어난다
- VU 50에서도 대기는 수십 ms라 Lock 대기 제한(3초)에 닿지 않아 503 `SESSION_BUSY`는 나오지 않았다
- 1:1 채팅에서 두 사람이 초당 1,000건 넘게 보낼 일은 없으므로 이 상한은 실사용에서 문제가 되지 않는다. 서버를 늘려도 같은 행을 두고 경쟁하므로 이 상한은 그대로다 ([설계 문서 10장](design.md#10-수평-확장))

## 시나리오 2: 여러 세션 분산

세션 100개, 세션마다 참여자 2명. VU마다 자기 세션에만 보내서 세션끼리 Lock 경쟁이 없다. 그래프: [report-multi-session.html](../load-test/results/report-multi-session.html)

| VU | 요청 수 | req/s | med | p95 | p99 | max | 실패 | (시나리오 1 req/s) |
|---|---|---|---|---|---|---|---|---|
| 10 | 128,871 | 4,296 | 2.14ms | 3.11ms | 5.14ms | 24.99ms | 0 | 1,217 |
| 20 | 171,396 | 5,713 | 3.10ms | 5.81ms | 8.87ms | 28.59ms | 0 | 1,178 |
| 50 | 178,001 | 5,933 | 7.31ms | 15.17ms | 20.96ms | 103.25ms | 0 | 1,295 |
| 100 | 158,182 | 5,273 | 16.33ms | 35.69ms | 50.80ms | 143.37ms | 0 | - |

- 세션끼리는 서로 기다리지 않으므로 같은 VU 수에서 처리량이 시나리오 1의 약 3.5~5배다
- VU 20 부근부터는 약 5,800건/s에서 더 늘지 않았다. 이때 CPU 사용량은 앱 약 4.2코어, DB 약 3.2코어였다. k6·앱·DB가 한 머신의 CPU를 함께 쓰는 환경이라 서버 전체의 한계와 원인(커넥션 풀 대기, CPU)은 이 테스트에서 단정하지 않는다
- 운영에서는 커넥션 풀 대기(`hikaricp.connections.pending`)와 CPU 지표로 병목을 판단하고 풀 크기를 조정한다

## 정합성 확인

테스트가 끝난 뒤 [verify.sql](../load-test/verify.sql)로 DB를 직접 조회했다. 빈틈·중복 확인과 반영 위치 확인은 문제 있는 세션만 출력하는 쿼리이고, 두 시나리오 모두 결과가 비어 있었다.

| 확인 | 시나리오 1 (세션 1개) | 시나리오 2 (세션 100개) |
|---|---|---|
| 저장된 메시지 수 (= k6 성공 수) | 177,990 | 644,984 |
| sequence 1부터 빈틈·중복 없음 | 1 ~ 177,993 | 100개 세션 모두 (세션별 마지막 1,545 ~ 14,503) |
| 메시지 목록 반영 위치 = 마지막 sequence | 일치 | 일치 (아래) |
| Snapshot (간격 1000) | 177개 | 619개 (세션별 "마지막 sequence ÷ 1000" 합계와 일치) |

저장된 메시지 수에는 결과 표에서 뺀 워밍업 구간도 포함된다. 시나리오 1은 단계별 합계 170,258건 + 워밍업 7,732건 = 177,990건이다.

- 시나리오 2는 테스트 직후 메시지 목록 반영이 100개 세션 중 61개만 끝나 있었다. 초당 약 6천 건이 들어오면서 반영 작업 큐(100개)가 넘쳐 작업이 버려졌고, 테스트가 끝나 새 이벤트가 들어오지 않은 세션은 따라잡을 기회가 없었다
- 1분 안에 안전망 스케줄러가 밀린 39개 세션을 찾아 반영했다(`message_projection_caught_up`). 큐가 넘쳐 작업을 버려도 결과가 맞는다는 설계([설계 문서 8장](design.md#8-비동기-처리))가 부하 상황에서 그대로 동작했다
- 시나리오 1은 이벤트가 계속 들어와서 버려진 작업의 몫을 다음 반영 작업이 함께 처리했다. 반영 작업은 "이 이벤트 하나"가 아니라 "마지막 반영 위치 이후 전부"를 처리한다

## 발견한 개선점

- **큐 포화 경고 로그 폭주**: 반영 작업이 버려질 때마다 `message_projection_task_rejected` 경고가 1줄씩 남아 시나리오 1 한 번에 약 2만 5천 줄이 쌓였다. 결과에는 영향이 없지만 다른 로그를 가린다. 같은 세션의 반영 작업이 이미 대기 중이면 새로 등록하지 않거나, 경고를 일정 간격으로 묶어 남기는 방식으로 줄일 수 있다. 버려진 수는 메트릭 `message.projection{result=rejected}`로 따로 센다

## 측정 경로

처음에는 k6가 `host.docker.internal`로 앱에 접속했는데, 시나리오 1의 VU 50 단계에서 새 연결 일부가 실패했다(20건). 서버 로그에는 에러가 없었고 k6 성공 수와 DB 저장 수가 일치했으며, 서버 처리 시간은 최대 0.1초인데 연결 수립 시간만 최대 19.7초였다. 그래서 서버가 아니라 Docker Desktop 포트 포워딩 단계의 문제로 판단했다. k6를 앱과 같은 Docker 네트워크에 붙여 다시 측정했고 실패는 0건이었다. 위 결과는 모두 이 방식으로 측정한 값이다.

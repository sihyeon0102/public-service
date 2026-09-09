# Reservation Optimistic Lock + Bounded Retry

## 목적과 결론

단일 인기 Program(capacity=100)에 200명이 한 번씩 예약하는 조건에서, Optimistic Lock에 제한된 retry를
추가했을 때의 정합성·성공률·비용을 측정했다. 기준 commit은 `454f002523c602546de793bca59483185dec4775`,
측정일은 2026-09-09다.

HTTP 성공은 retry 없는 21~25건에서 88~100건으로 증가했다. 그러나 최종 5xx가 83~112건 남았고,
평균 정상 201 처리량 39.20 RPS, 평균 p95 2.244초였다. 모든 실행에서 count와 실제 예약 행은 일치했다.
이 고경합 조건에서는 **Pessimistic Lock을 최종 권장한다.** 기존 측정의 100 성공/100 정원 거절/5xx 0,
58.53 정상 RPS와 p95 1.454초가 더 좋은 결과였다.

이번 commit과 실행 앱은 실험한 Optimistic+Retry 구현을 보존한다. 권장 전략을 제시하는 것과 별개로,
이번 단계에서 금지된 Pessimistic Lock으로 코드를 다시 전환하지 않았다.

## Retry 정책

추가 의존성 없이 `ReservationService`의 작은 반복문과 Spring `TransactionTemplate`을 사용한다.
별도 Spring Retry annotation/proxy 순서나 자기 호출에 의존하지 않아 transaction 경계를 직접 읽을 수 있다.

| 항목 | 실제 설정 |
|---|---|
| 최대 attempts | 최초 요청 포함 3회 |
| 최대 retries | 2회 |
| 첫 실패 후 대기 | 균등 정수 jitter 10~30ms |
| 두 번째 실패 후 대기 | 균등 정수 jitter 20~60ms |
| 대기 합계 상한 | 90ms (스케줄링 지연 제외) |
| 낙관적 retry 대상 | Spring `OptimisticLockingFailureException` 계열, 실제로는 `ObjectOptimisticLockingFailureException` |
| deadlock retry 대상 | cause chain의 SQLException code=1213 **AND** SQLState=40001 |
| 정원/중복 409, 400/404 | 즉시 전파, retry 없음 |
| 그 밖의 오류 | 즉시 전파, retry 없음 |
| 3회 소진 | 마지막 원래 예외 전파, 현재 HTTP 500 |

무한 retry, pessimistic/JVM/Redis lock, 정원 조건 atomic SQL, isolation/Hikari 변경은 없다.
Jitter는 의도적으로 매번 달라지므로 결과가 완전히 같을 수 없다. 재현 조건은 같은 대기 범위·attempt 제한이다.

## Transaction 경계와 검증

```text
Controller → ReservationService.reserve (outer transaction 없음)
  attempt 1: TransactionTemplate(REQUIRES_NEW)
    Program 일반 조회 → 최신 capacity 확인 → count 증가 → Reservation INSERT/flush → commit
  실패: rollback 완료 → 결과 counter → jitter sleep (transaction 밖)
  attempt 2: REQUIRES_NEW → Program 재조회 → capacity 재검증 → ...
  실패: rollback 완료 → jitter sleep
  attempt 3: REQUIRES_NEW → 성공/409 또는 최종 예외 전파
```

기존 `@Transactional` 대신 template가 정확히 한 attempt의 transaction을 담당한다.
`execute()`가 commit까지 끝나야 성공 counter가 증가하고, 예외 catch는 rollback 이후 실행된다.
동일 connection을 pool에서 다시 받을 수 있으나 transaction은 새로 시작한다. 현재 HTTP 경로에는 outer
transaction이 없으며, backoff 동안 이 attempt의 connection을 보유하지 않는다.
[Spring 공식 문서](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-propagation.html)의
`REQUIRES_NEW` 물리 transaction 분리 의미를 따른다. 향후 외부 transaction에서 호출하면 그 외부 자원이
suspend된 채 남을 수 있으므로 현재의 비트랜잭션 진입 경계를 유지해야 한다.

실제 MySQL 통합 테스트 4개로 정책을 확인했다.

1. 실제 JPA INSERT/flush 후 첫 두 attempt에만 예외를 주입한다. 다음 attempt 진입 전에 이전
   `afterCompletion`이 rollback으로 끝났고 DB rows/count가 0인지 검증한다. 세 번째 commit 후 rows/count/version=1.
2. 계속 발생하는 optimistic 충돌이 정확히 3 attempts에서 종료되고 DB 쓰기가 남지 않는지 확인한다.
3. 첫 rollback 후 다른 transaction이 정원을 채운 fixture에서 다음 attempt가 최신 상태를 읽어 409로 끝나는지 확인한다.
4. retry 대상이 아닌 MySQL 1205/HY000은 1회만 수행하는지 확인한다.

이들은 실패 경계를 검증하는 명시적 fault injection 테스트다. 아래 Service/HTTP 경합 수치는 예외를 주입하지
않은 실제 MySQL 동시 요청의 결과다. API의 성공·정원·중복·404 테스트도 유지했다.

## 계측과 실험 통제

- Java 21.0.12.1, Spring Boot 4.1.1, MySQL 8.4.11, REPEATABLE-READ, Hikari max=10
- Spring Boot는 Windows host :8080, k6는 `grafana/k6:2.1.0`에서 `host.docker.internal:8080` 호출
- 기존 [reservation-concurrency-baseline.js](../loadtest/reservation-concurrency-baseline.js) **변경 없음**
- script SHA256 `640FE0639866B2B49A86528FBEFA00AB1C28E3D1769D569C3AB01D6D4EE1201A`
- HTTP 200 VU × VU당 1회, participant 중복 없음, capacity=100, 3회 반복
- run마다 새 Program/Reservation fixture, 측정 후 해당 ID만 삭제
- 이전과 같은 짧은 smoke: 별도 capacity=1 Program에 순차 요청 → 201/409, count/version/rows=1
- DB/OS cache 강제 초기화 없음, tests와 HTTP 측정은 시간상 겹치지 않음
- repository, `@Version`, schema, flush 순서, Hikari, Redis와 Facility 설정 변경 없음

새 Micrometer counter의 실제 Prometheus 이름:

```promql
reservation_attempts_total{attempt="1",outcome="success"}
reservation_attempts_total{attempt="2",outcome="optimistic"}
reservation_attempts_total{attempt="3",outcome="deadlock"}
reservation_retries_total{reason="optimistic"}
reservation_retries_total{reason="deadlock"}
```

outcome은 success/optimistic/deadlock/business_rejection/error, attempt는 1~3으로 제한된다.
Program/participant ID 같은 높은 cardinality label은 없다. business_rejection은 400/404도 포함할 수 있지만
이번 정상 fixture workload에서는 409만 해당한다. run 전후 `/actuator/prometheus` 직접 snapshot의 차이로 집계해
15초 scrape 간격보다 짧은 실험도 정확한 counter 증가량을 얻었다.

최종 실패는 실행 구간의 servlet ERROR 헤더를 요청당 한 번씩 집계했다. 중간에 복구한 오류는 servlet까지
전파되지 않으므로 attempt counter에서 확인한다. 전체 deadlock counter는 MySQL Performance Schema
ERROR_NUMBER=1213의 전후 증가량과 모든 run에서 일치했다.

Hikari/CPU는 기존과 동일한 순차 Actuator 요청 후 200ms sleep 방식이다. 실제 간격은 응답시간 때문에 더 길고,
짧은 peak를 놓칠 수 있다. CPU는 요청당 비용이 아닌 표본 사용률이며 system CPU에는 Windows/IDE/Docker가 포함된다.
추가 counter 계측 비용이 소량 포함되므로 미세한 latency 차이를 retry만의 효과로 단정하지 않는다.

## Service 동시성 결과

기존 ExecutorService 200 threads + CountDownLatch 동시 시작을 3회 반복한 최종 성공 build의 결과다.

| run | 성공 | 409 | 최종 실패 | 전체 optimistic | 전체 1213 | retry | 최종 optimistic / 1213 | count / rows |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 | 56 | 0 | 144 | 63 | 420 | 339 | 19 / 125 | 56 / 56 |
| 2 | 55 | 0 | 145 | 55 | 434 | 344 | 17 / 128 | 55 / 55 |
| 3 | 55 | 0 | 145 | 58 | 428 | 341 | 19 / 126 | 55 / 55 |

| run | attempt | 성공 | optimistic | 1213 | 총 attempt 요청 |
|---:|---:|---:|---:|---:|---:|
| 1 | 1 | 21 | 23 | 156 | 200 |
| 1 | 2 | 19 | 21 | 139 | 179 |
| 1 | 3 | 16 | 19 | 125 | 160 |
| 2 | 1 | 18 | 19 | 163 | 200 |
| 2 | 2 | 20 | 19 | 143 | 182 |
| 2 | 3 | 17 | 17 | 128 | 162 |
| 3 | 1 | 20 | 20 | 160 | 200 |
| 3 | 2 | 19 | 19 | 142 | 180 |
| 3 | 3 | 16 | 19 | 126 | 161 |

모든 run에서 `성공 == rows == reservedCount == version <= capacity`였다. count=100 목표는 달성하지 못했다.
테스트는 목표를 강제로 통과시키지 않고 안전 불변식·요청 합계·허용된 예외 종류를 검증한다.

## HTTP 결과

| run | 201 | 409 | 5xx | retry | 전체 attempts | count / rows | HTTP 실패율 |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 | 88 | 0 | 112 | 294 | 494 | 88 / 88 | 56.0% |
| 2 | 99 | 0 | 101 | 281 | 481 | 99 / 99 | 50.5% |
| 3 | 100 | 17 | 83 | 273 | 473 | 100 / 100 | 41.5% |

| run | 전체 optimistic | 전체 1213 | 최종 optimistic | 최종 1213 | 중간 optimistic / 1213 (retry 실행) |
|---:|---:|---:|---:|---:|---:|
| 1 | 97 | 309 | 26 | 86 | 71 / 223 |
| 2 | 94 | 288 | 22 | 79 | 72 / 209 |
| 3 | 100 | 256 | 19 | 64 | 81 / 192 |

전체 발생 수에는 마지막 attempt의 실패도 포함한다. 중간 오류 수는 retry 횟수와 같지만, 그 요청이 최종적으로
성공했다는 뜻은 아니다. 같은 요청이 두 번 재시도할 수 있다. 미분류/network failure는 0이다.
`5xx = 최종 optimistic + 최종 1213`, `전체 attempts = 200 + retry`를 모두 대조했다.

| run | attempt | 201 | 409 | optimistic | 1213 | 합계 |
|---:|---:|---:|---:|---:|---:|---:|
| 1 | 1 | 45 | 0 | 40 | 115 | 200 |
| 1 | 2 | 16 | 0 | 31 | 108 | 155 |
| 1 | 3 | 27 | 0 | 26 | 86 | 139 |
| 2 | 1 | 47 | 0 | 37 | 116 | 200 |
| 2 | 2 | 25 | 0 | 35 | 93 | 153 |
| 2 | 3 | 27 | 0 | 22 | 79 | 128 |
| 3 | 1 | 38 | 8 | 45 | 109 | 200 |
| 3 | 2 | 31 | 4 | 36 | 83 | 154 |
| 3 | 3 | 31 | 5 | 19 | 64 | 119 |

Service 장벽은 200 worker를 함께 풀지만 k6의 HTTP 도착 시점은 연결/서버 스케줄링 때문에 분산된다.
retry jitter와 JVM warm 상태도 결과에 영향을 준다. Service 55~56 성공과 HTTP 88~100 성공은 같은 범위가
아니므로 숨기지 않았다. 요청 조건은 유지했으나 정확한 경합 순서는 통제할 수 없다. HTTP에서 run 3만 정원이 찼고,
그 전에 3 attempts를 소진한 83명은 409가 아니라 500으로 종료했다.

| run | 전체 RPS | 정상 201 RPS | avg ms | p95 ms | p99 ms | k6 완료 ms |
|---:|---:|---:|---:|---:|---:|---:|
| 1 | 63.98 | 28.15 | 1,626.07 | 2,860.88 | 2,943.01 | 3,125.95 |
| 2 | 83.31 | 41.24 | 1,184.81 | 2,051.93 | 2,111.17 | 2,400.54 |
| 3 | 96.40 | 48.20 | 1,056.05 | 1,820.18 | 1,842.94 | 2,074.71 |

| run | active max | pending max | idle min | process CPU max | system CPU max | row-lock waits | 누적 lock wait ms |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 | 10 | 166 | 0 | 42.61% | 99.72% | 726 | 3,362 |
| 2 | 10 | 146 | 0 | 43.15% | 98.16% | 700 | 1,845 |
| 3 | 10 | 146 | 0 | 24.74% | 100% | 632 | 1,542 |

## 네 전략 비교 및 비용 해석

| 지표 | No-Lock | Pessimistic | Optimistic | Optimistic+Retry |
|---|---:|---:|---:|---:|
| 201 / run | 36~41 | 100 | 21~25 | 88~100 |
| 409 / run | 0 | 100 | 0 | 0~17 |
| 5xx / run | 159~164 | 0 | 175~179 | 83~112 |
| count와 rows 일치 | 아니오 | 예 | 예 | 예 |
| 전체 attempts / run | 200 | 200 | 200 | 473~494 |
| 전체 1213 / run | 159~164 | 0 | 147~159 | 256~309 |
| 평균 전체 RPS | 126.02 | 117.06 | 115.21 | 81.23 |
| 평균 정상 201 RPS | 24.39 | 58.53 | 13.26 | 39.20 |
| 평균 p95 ms | 1,222.88 | 1,453.69 | 1,372.31 | 2,244.33 |
| Hikari pending max | 73~172 | 135~170 | 61~141 | 146~166 |

평균은 각 run 지표의 산술평균이다. p95 세 개의 평균은 600개 요청을 합친 p95가 아니다.
로컬 짧은 burst 세 번의 진단 결과이며 범용 처리량이나 최종 운영 SLO로 해석하지 않는다.

retry는 Optimistic 정상 처리량을 약 2.96배로 높였지만 Pessimistic 대비 약 33.0% 낮고 p95는 약 54.4% 높았다.
요청당 transaction attempts가 2.37~2.47배가 됐다. 각 attempt가 Program SELECT와 INSERT/UPDATE를 다시 수행하므로
DB 작업이 증폭된다. 실패 지점이 다르므로 SQL statement 수도 정확히 같은 배수라고 단정하지 않는다.

Pessimistic은 row를 먼저 잠가 직렬화하고 기다린 요청을 최신 정원으로 처리했다. Optimistic+Retry는 기존
Reservation INSERT의 FK S lock → version UPDATE X lock 경쟁을 반복하며, 충돌/rollback 후 추가 작업을 한다.
Jitter가 있어도 active=10과 pending 146~166, 수백 회의 deadlock이 남았다. 무한 retry storm은 제한했지만
**제한된 retry 안에서도 부하 증폭과 tail latency 비용이 관찰됐다.** attempts를 임의로 늘려 목표를 맞추지 않았다.

이 서비스의 단일 인기 Program/200 동시 요청에는 Pessimistic을 권장한다. 이유는 정합성뿐 아니라 5xx 제거,
정원 100개 활용, 더 높은 정상 처리량, 더 낮은 p95, 그리고 retry 분류·backoff·소진 정책보다 단순한 구현이다.
다른 Program에 요청이 분산되거나 경합이 낮은 환경에서 Optimistic이 유리할 가능성까지 부정하는 결론은 아니다.
다만 그 조건은 별도 측정이 필요하다.

## 최종 검증 및 재실행

- `JAVA_TOOL_OPTIONS=-Dspring.jpa.hibernate.ddl-auto=validate`로 `gradlew.bat build --console=plain` 성공(41초)
- 전체 34 tests, 실패/오류/skip 0. transaction 경계 검증 4개 포함
- Facility 1,000,000건, 고유 이름 1,000,000개, checksum 합계 `2148598155341640` 유지
- PRIMARY와 기존 검색 인덱스 `idx_facility_region`, `idx_facility_region_type`, `idx_facility_region_district_type` 유지
- schema 변경 없음, 테스트 후 Program/Reservation 각각 0건
- Redis PONG 및 Facility A 두 번 요청 content=20/total=50000, cache hit 증가 1 확인
- Spring health UP, Prometheus target UP(15초), Docker MySQL/Redis/Prometheus/Grafana running
- Hikari max=10, isolation REPEATABLE-READ 유지

재실행은 [Optimistic 실험의 Docker k6/fixture 명령](reservation-optimistic-lock.md#http-실험-방법과-분류)을
같이 사용하고 run ID/WORKLOAD만 별도로 부여한다. run 전후 prometheus snapshot과 MySQL 1213 counter를
저장해야 중간 retry와 최종 500을 구분할 수 있다. fixture 초기화와 cleanup은 생성한 Program ID에만 수행한다.

관련 변경은 ReservationService, Service 경합 테스트, Retry 통합 테스트,
[측정 요약 JSON](../loadtest/results/reservation-optimistic-retry-summary.json), 이 문서다.
raw k6/Prometheus snapshot/실행 로그는 `build/`에만 보관하며 commit하지 않는다.
기존 성능 결과와 k6 script, Facility 코드, compose/application 설정에는 변경이 없다.

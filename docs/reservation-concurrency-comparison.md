# Reservation Concurrency — Final Strategy

## 최종 선택

**단일 인기 Program에 200명이 동시에 예약하는 고경합 workload의 최종 실행 전략은 Pessimistic Lock이다.**
정합성을 유지하면서 100건을 예약하고 나머지 100건을 정상 409로 처리했으며, 기존 비교 실험에서 deadlock과
5xx가 없었다. 정상 예약 처리량, tail latency와 구현 복잡도까지 고려한 선택이다.

현재 main의 ReservationService는 Optimistic+Retry 실험 코드를 제거하고 `PESSIMISTIC_WRITE`와 일반
`@Transactional`을 사용한다. 과거 commit을 reset하거나 rewrite하지 않고 새로운 commit으로 전환했다.
기존 문서와 결과 JSON은 당시 실험 기록으로 그대로 보존한다. 과거 문서의 '현재 실행 앱' 표현은 해당 실험
시점의 상태이며, 최종 실행 상태는 이 문서를 기준으로 한다.

## 동일 조건의 네 전략 비교

기존 실험 조건: capacity=100, 서로 다른 participant 200명, k6 200 VU × 각 1회, HTTP 3회 반복,
Windows host Spring Boot :8080, Docker MySQL 8.4, Hikari max=10, REPEATABLE-READ.
표의 평균은 각 run 지표의 산술평균이다. p95 평균은 모든 요청을 합쳐 계산한 p95가 아니다.

| 지표 | No Lock | Pessimistic | Optimistic (no retry) | Optimistic + Retry |
|---|---:|---:|---:|---:|
| reservedCount == Reservation rows | 실패 | 유지 | 유지 | 유지 |
| 201 / run | 36~41 | **100** | 21~25 | 88~100 |
| 409 / run | 0 | **100** | 0 | 0~17 |
| 최종 5xx / run | 159~164 | **0** | 175~179 | 83~112 |
| reservedCount / rows | 18~20 / 36~41 | **100 / 100** | 21~25 / 동일 | 88~100 / 동일 |
| 정상 201 평균 RPS | 24.39 | **58.53** | 13.26 | 39.20 |
| 전체 평균 RPS | 126.02 | 117.06 | 115.21 | 81.23 |
| 평균 p95 | 1.223초 | **1.454초** | 1.372초 | 2.244초 |
| 전체 MySQL 1213 / run | 159~164 | **0** | 147~159 | 256~309 |
| 전체 optimistic conflict / run | 0 | 0 | 20~28 | 94~100 |
| 최종 optimistic 실패 / run | 0 | 0 | 20~28 | 19~26 |
| 최종 deadlock 실패 / run | 159~164 | **0** | 147~159 | 64~86 |
| transaction attempts / run | 200 | 200 | 200 | 473~494 |
| Hikari active max | 10 | 10 | 10 | 10 |
| Hikari pending max 범위 | 73~172 | 135~170 | 61~141 | 146~166 |
| 구현 복잡도 | 단순하지만 정합성 결함 | lock 조회 + transaction | version 관리 + 충돌 처리 필요 | version + 새 transaction + 오류 분류 + backoff + 소진 정책 |

Retry는 최대 3 attempts, 10~30ms/20~60ms jitter를 적용했던 결과다. 전체 충돌/deadlock은 중간 retry에서
복구한 오류와 최종 실패를 함께 포함한다. 최종 5xx와 같은 수치가 아니다.

No Lock과 Optimistic의 전체 RPS에는 많은 rollback/500이 포함되므로 그 수치만으로 효율을 판단할 수 없다.
Pessimistic은 실패 없이 정원을 채웠고, Optimistic+Retry보다 정상 201 RPS가 높고 p95가 낮았다.
Retry는 요청 수의 약 2.4배 transaction attempts를 발생시키면서도 83~112명에게 최종 500을 반환했다.

## 원인과 선택 근거

- No Lock: 여러 transaction이 같은 count를 읽고 같은 증가 값을 써 lost update가 발생했다.
  Reservation FK 검사로 획득한 Program S lock과 Program UPDATE X lock 사이의 경쟁에서 deadlock도 발생했다.
- Optimistic: version 조건 UPDATE와 rollback으로 lost update를 막았다. 그러나 고경합에서 version 충돌과
  기존 FK INSERT → UPDATE의 deadlock이 남아 정상 성공률이 낮았다.
- Optimistic+Retry: rollback 후 새 transaction으로 재조회하여 성공률을 높였지만, 반복된 읽기·쓰기·rollback과
  connection 재획득으로 DB 작업량과 latency가 증가했다. 제한 횟수를 소진한 요청도 많았다.
- Pessimistic: Program을 변경하기 전에 쓰기 락을 획득하여 같은 row의 예약을 순서대로 처리했다.
  기다린 요청은 앞선 commit의 count를 읽고, 정원이 차면 정상 409로 종료했다.

Pessimistic에도 비용은 있다. 기존 실험에서는 run마다 약 199회의 row-lock wait, active=10과 pending 증가가
관찰됐다. connection이 row lock을 기다리는 동안 점유되며 같은 Program의 처리량이 직렬화된다.
그럼에도 이번 workload에서는 올바른 결과, 5xx 제거, 높은 정상 처리량을 얻는 비용으로 수용할 수 있었다.

**Pessimistic이 항상 우월하다는 결론은 아니다.** 여러 Program으로 요청이 분산되거나 경합이 낮은 환경에서는
Optimistic의 선행 배타 락 없는 조회가 유리할 수 있다. 서로 다른 workload, transaction 길이와 장시간 부하에
대한 추가 측정 없이는 이번 결과를 일반화하지 않는다. Hikari peak는 짧은 burst의 표본이며 pool 크기가
근본 원인이라는 의미도 아니다.

## 최종 코드와 transaction 경계

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select p from Program p where p.id = :id")
Optional<Program> findByIdForUpdate(@Param("id") Long id);
```

`ReservationService.reserve`의 동일한 `@Transactional` 안에서 다음 순서로 실행한다.

```text
transaction 시작
  → Program PESSIMISTIC_WRITE 조회
  → capacity 확인 (가득 차면 409)
  → reservedCount 증가
  → Reservation 저장/flush
  → commit 및 lock 해제
```

최종 앱의 Hibernate 로그에서 확인한 실제 SELECT:

```sql
select p1_0.id,p1_0.capacity,p1_0.facility_id,p1_0.name,p1_0.reserved_count
from program p1_0 where p1_0.id=? for update of p1_0;
```

Reservation `(program_id, participant_id)` unique constraint는 유지한다.
Retry 반복문, TransactionTemplate, jitter/backoff, retry 예외 분류와
`reservation.attempts`/`reservation.retries` Micrometer counter는 운영 코드에서 제거했다.
최종 실행 앱의 Actuator metric 목록에서도 두 retry meter가 없는 것을 확인했다.

## Version 컬럼과 schema 호환성

Program의 `@Version` annotation과 Java version 필드는 제거했다. DB의 기존
`program.version BIGINT NOT NULL DEFAULT 0` 컬럼은 그대로 둔다. 이번 전환에서 schema 변경은 수행하지 않았다.

이 컬럼은 현재 JPA 매핑에 사용되지 않는다. 기존 DB에서는 default 0으로 새 INSERT가 가능하며,
추가 미매핑 컬럼이 있어도 현재 Hibernate `ddl-auto=validate`가 통과함을 실제 build와 앱 기동으로 확인했다.
새 DB를 최종 Entity로 구성하면 version 컬럼이 없어도 최종 구현에는 필요하지 않다.
기존 DB의 version 값을 앞으로 유효한 낙관적 락 버전이라고 해석하면 안 된다. 과거 Optimistic 실험을
다시 실행하려면 당시 commit과 fixture 초기화 절차를 사용해야 한다.

## 최종 상태 검증

코드 전환 직후 2026-09-09에 실행한
`JAVA_TOOL_OPTIONS=-Dspring.jpa.hibernate.ddl-auto=validate` 조건의
`gradlew.bat build --rerun-tasks --console=plain`이 41초에 성공했다.
usage limit 이후 동일 코드의 성공 로그와 XML을 확인하여 불필요하게 다시 실행하지 않았다.

- 전체 **30개 테스트**, 실패/오류/skip 0
- 예약 단일 API의 성공·정원 409·중복 409·없는 Program 404 테스트 유지
- Retry 전용 테스트 4개는 제거하여 34→30개가 됐다. 해당 실험 테스트 소스는 Git history에 보존된다.
- Service 200 threads/CountDownLatch, capacity=100 동시성 검증 3회:

| run | 성공 | 정상 409 | 예상 밖 실패 | reservedCount | Reservation rows |
|---:|---:|---:|---:|---:|---:|
| 1 | 100 | 100 | 0 | 100 | 100 |
| 2 | 100 | 100 | 0 | 100 | 100 |
| 3 | 100 | 100 | 0 | 100 | 100 |

2026-09-10 재개 후 아직 완료되지 않았던 최종 HTTP 상태 확인을 **한 번** 수행했다.
기존 k6 script 그대로 200 VU × 1회, 서로 다른 participant, 별도 capacity=100 fixture를 사용했다.

| HTTP 요청 | 201 | 409 | 5xx | MySQL 1213 증가 | reservedCount | Reservation rows |
|---:|---:|---:|---:|---:|---:|---:|
| 200 | 100 | 100 | 0 | 0 | 100 | 100 |

모든 요청이 분류된 HTTP 응답으로 끝났고 오류율은 0이었다. 정원 초과, lost update 없이
`성공 == count == rows == capacity`를 만족했다. 해당 fixture는 검증 후 정리했다.

이번 단일 확인의 RPS=67.98, p95=2.720초, Hikari active max=10/pending max=177도 기록한다.
장시간 중단 후 다른 시점에서 수행한 상태 확인이며, 별도의 동일 warm-up/반복 성능 비교 실험이 아니다.
상단의 과거 Pessimistic 3회 성능 결과를 이 숫자로 덮어쓰거나 새로운 개선/회귀율을 계산하지 않는다.

최종 기존 시스템 확인:

- Facility 1,000,000건, 고유 이름 1,000,000개, checksum 합계 `2148598155341640` 유지
- `PRIMARY(id)`, `idx_facility_region`, `idx_facility_region_type`, `idx_facility_region_district_type` 유지
- Reservation unique constraint 유지; 정리 후 Program/Reservation 각각 0건
- Redis PONG; Facility A 두 번 조회 content=20/total=50000, cache hit 증가 1
- Spring health UP, Prometheus target UP(scrape 15초), Docker MySQL/Redis/Prometheus/Grafana running
- Hikari max=10, REPEATABLE-READ, Redis/compose/application 설정 변경 없음

## 보존한 실험 기록

| 전략 | 문서 | HTTP 결과 JSON | 당시 코드 commit |
|---|---|---|---|
| No Lock | [Service](reservation-concurrency-baseline.md), [HTTP](reservation-http-concurrency-baseline.md) | [결과](../loadtest/results/reservation-http-concurrency-before-summary.json) | `87cc6cf` |
| Pessimistic | [실험](reservation-pessimistic-lock.md) | [결과](../loadtest/results/reservation-pessimistic-lock-summary.json) | `85621ce` |
| Optimistic | [실험](reservation-optimistic-lock.md) | [결과](../loadtest/results/reservation-optimistic-lock-summary.json) | `454f002` |
| Optimistic+Retry | [실험](reservation-optimistic-retry.md) | [결과](../loadtest/results/reservation-optimistic-retry-summary.json) | `b20f4e2` |

기존 실험 SQL과 k6 script도 수정하지 않았다. 최종 단일 HTTP 확인의 raw JSON/관측/로그는
`build/k6/reservation-http-final-pessimistic-run-1*`에 보관하고 Git에는 넣지 않는다.
최종 구현 변경과 이 비교 문서만 새 commit에 포함한다.

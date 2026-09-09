# Reservation Pessimistic Lock Experiment

## 목적

No-Lock 예약에서 발생한 lost update와 MySQL 1213 deadlock을 Program row의 비관적 쓰기 락으로
해결할 수 있는지 검증한다. 정합성을 첫 번째 기준으로 두고, 동일 HTTP workload에서 5xx 제거와
latency·처리량·connection/row-lock 대기 비용을 측정한다.

이번 실험에서 바꾼 서비스 변수는 Program 조회의 `PESSIMISTIC_WRITE` 하나다. capacity 100,
서로 다른 participant 200명, Hikari max 10, MySQL isolation, Reservation unique constraint,
API, Entity, fixture와 k6의 200 VU/각 1회 workload는 그대로 유지했다. optimistic lock, `@Version`,
retry, atomic update, JVM/Redis lock은 적용하지 않았다.

## 선택 이유와 구현

No-Lock 구현은 여러 트랜잭션이 같은 `reservedCount`를 읽고 각각 같은 증가 값을 썼다. JPA dirty
checking의 `reserved_count = ?` UPDATE에서 늦은 commit이 앞선 증가를 덮어써 Reservation 행과
count가 달라졌다. Reservation FK가 parent Program에 잡은 S lock과 Program UPDATE의 X lock 승격도
경쟁해 MySQL 1213이 요청의 약 80%에서 발생했다.

Spring Data JPA의 Repository lock metadata 방식을 사용했다.
[공식 locking 문서](https://docs.spring.io/spring-data/jpa/reference/jpa/locking.html)

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select p from Program p where p.id = :id")
Optional<Program> findByIdForUpdate(@Param("id") Long id);
```

`ReservationService.reserve`의 기존 `@Transactional` 경계에서 `findById`만 이 메서드로 바꿨다.

```text
transaction 시작
  → Program SELECT + PESSIMISTIC_WRITE 획득
  → capacity 확인
  → reservedCount 증가
  → Reservation INSERT
  → Program UPDATE
transaction commit과 함께 row lock 해제
```

최초 트랜잭션이 lock을 보유하는 동안 같은 Program 예약은 SELECT 단계에서 기다린다. 앞 요청의 commit
후 다음 요청은 갱신된 count를 읽기 때문에 stale capacity 판정과 lost update를 막는다. 정원이 100에
도달한 뒤에도 대기하던 요청은 순서대로 lock을 얻어 최신 값을 읽고 409를 반환한다.

## 실제 SQL

새 JAR을 `ddl-auto=validate`로 실행하고 예약 요청을 발생시킨 Hibernate 로그다.

```sql
select p1_0.id, p1_0.capacity, p1_0.facility_id, p1_0.name,
       p1_0.reserved_count
from program p1_0
where p1_0.id = ?
for update of p1_0;

insert into reservation (created_at, participant_id, program_id)
values (?, ?, ?);

update program
set capacity=?, facility_id=?, name=?, reserved_count=?
where id=?;
```

Performance Schema statement digest에서도 `SELECT ... FROM program ... FOR UPDATE OF p1_0`가 확인됐다.
lock 조회, capacity 확인, count 변경과 Reservation 저장은 동일한 Service transaction 안에 있다.

## Service 동시성 통합 테스트

기존 `ExecutorService`/`CountDownLatch` fixture를 유지했다. capacity 100 Program에 서로 다른
participant 200개를 동시에 보내는 테스트를 세 번 반복했다.

| run | 요청 | 성공 | 409 conflict | 예상 밖 실패 | reservedCount | Reservation rows |
|---:|---:|---:|---:|---:|---:|---:|
| 1 | 200 | 100 | 100 | 0 | 100 | 100 |
| 2 | 200 | 100 | 100 | 0 | 100 | 100 |
| 3 | 200 | 100 | 100 | 0 | 100 | 100 |

세 실행 모두 다음 불변식을 지켰다.

- 성공 예약 100 <= capacity 100
- Reservation rows 100 <= capacity 100
- reservedCount 100 == Reservation rows 100
- overbooking 및 lost update 없음
- 예상하지 않은 deadlock/DB 예외 없음

기존 단일 API 테스트의 예약 성공, full 409, participant 중복 409, Program 404도 유지했다.

## HTTP 실험 조건

[reservation-concurrency-baseline.js](../loadtest/reservation-concurrency-baseline.js)의 요청 조건을
그대로 사용했다.

- `POST /api/programs/{programId}/reservations`
- k6 `per-vu-iterations`, 200 VU, VU당 1회
- participant ID `k6-<run>-<VU id>`, 중복 없음
- 매 run마다 capacity 100인 새 Program 생성, 결과 측정 후 해당 ID만 삭제
- 같은 Spring Boot/MySQL, Hikari max 10, Redis/Prometheus 환경

After에서는 응답 분류만 명확히 했다. `http.expectedStatuses(201, 409)`로 정원 초과 409를 정상 처리된
business rejection으로 표시하고, 5xx/network failure만 `http_req_failed`에 포함한다. VU, request 수,
타이밍과 participant 생성은 Before와 같다.

Smoke는 capacity 1 / 2 VU에서 `201=1`, `409=1`, `5xx=0`, DB 1/1과 failed rate 0을 확인했다.

## HTTP 결과

| run | 요청 | 201 | 409 | 5xx | MySQL 1213 | reservedCount | Reservation rows |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 | 200 | 100 | 100 | 0 | 0 | 100 | 100 |
| 2 | 200 | 100 | 100 | 0 | 0 | 100 | 100 |
| 3 | 200 | 100 | 100 | 0 | 0 | 100 | 100 |

| run | 전체 RPS | 정상 예약 201 RPS | avg | p95 | p99 | k6 완료 시간 | unexpected error rate |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 | 114.69 | 57.35 | 1,127.94ms | 1,494.85ms | 1,508.22ms | 1,743.79ms | 0% |
| 2 | 105.48 | 52.74 | 1,184.49ms | 1,513.96ms | 1,521.77ms | 1,896.12ms | 0% |
| 3 | 131.00 | 65.50 | 941.12ms | 1,352.25ms | 1,367.40ms | 1,526.73ms | 0% |

전체 RPS에는 201과 정상 business rejection인 409가 각각 100건씩 포함된다. 모든 요청이 201 또는
409로 명확히 처리됐고 5xx/network failure는 없다.

## No-Lock Before와 비교

| 지표 | No-Lock Before | Pessimistic After | 변화 |
|---|---:|---:|---:|
| 201 / run | 36~41 | **100** | 정확히 capacity까지 성공 |
| 409 / run | 0 | **100** | 초과 요청을 business rejection으로 처리 |
| 5xx / run | 159~164 | **0** | 제거 |
| MySQL 1213 / run | 159~164 | **0** | 제거 |
| reservedCount | 18~20 | **100** | 실제 예약과 일치 |
| Reservation rows | 36~41 | **100** | capacity와 일치 |
| 평균 전체 RPS | 126.02 | 117.06 | -7.11% |
| 평균 정상 201 RPS | 24.39 | **58.53** | +139.98% |
| 평균 p95 | 1,222.88ms | 1,453.69ms | +18.87% |
| test 완료 시간 범위 | 1.47~1.74s | 1.53~1.90s | 증가 가능성 관찰 |
| Hikari active max | 10 | 10 | 동일 |
| Hikari pending max | 73~172 | 135~170 | 높은 대기 유지 |

No-Lock 전체 RPS에는 대량의 빠른 rollback 500이 포함됐으므로 정상 처리량 비교에는 부적합했다.
비관적 락은 평균 전체 RPS가 약 7% 낮고 p95가 약 19% 높았지만, 정상 commit된 201 처리량은 약 140%
늘었고 나머지 요청도 409로 정상 종료했다.

## Row-lock wait, Hikari와 CPU

MySQL `Innodb_row_lock_waits`와 `Innodb_row_lock_time`의 run 전후 차이다. 시간은 모든 wait event의
누적값이며 wall-clock 시간이 아니다.

| run | row-lock waits | 누적 lock wait | wait당 평균 | Hikari active max | pending max | process CPU max | system CPU max |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 | 199 | 13,441ms | 67.54ms | 10 | 143 | 30.52% | 89.83% |
| 2 | 199 | 13,785ms | 69.27ms | 10 | 170 | 23.47% | 94.07% |
| 3 | 199 | 11,191ms | 56.24ms | 10 | 135 | 24.88% | 87.61% |

매 run 첫 요청을 제외한 199개 요청에서 row-lock wait가 발생해 Program row 직렬화를 수치로 확인했다.
Hikari active는 여전히 max 10, idle은 0까지 내려갔고 pending은 135~170이었다. connection을 얻은
transaction이 row lock 앞에서 기다리는 동안 connection을 반환하지 않기 때문이다.

No-Lock pending 범위 73~172와 크게 겹치므로 한 번의 peak로 전략 우열을 단정할 수 없다. After의
pending 평균 peak는 149.3으로 Before 114.3보다 높았지만, Before pending은 deadlock rollback과 함께
측정된 값이고 After pending은 올바른 직렬화 대기다. CPU도 로컬 Windows 전체 상태 영향을 받으므로
보조 지표로 사용한다.

## Trade-off와 판단 범위

비관적 락은 이번 조건에서 정합성과 오류 처리를 완전히 회복했다. stale count read, lost update와 1213을
없애고 정확히 100건을 commit한 뒤 나머지 100건을 409로 거절했다.

대가는 같은 Program의 예약이 한 row에서 직렬화된다는 점이다. 199 lock wait, 증가한 평균/p95,
connection pool pending이 이를 보여준다. 인기 Program 하나에 동시 요청이 더 커지거나 transaction 안의
작업이 늘어나면 lock wait와 connection 점유가 더 길어질 수 있다. 서로 다른 Program은 서로 다른 row를
잠그므로 이 실험만으로 전체 서비스 처리량을 추정할 수도 없다.

이번 결과만으로 비관적 락을 최종 전략으로 결정하지 않는다. 동일 fixture에서 optimistic lock의 충돌률,
retry 여부에 따른 사용자 결과, DB/CPU 비용과 tail latency를 측정한 뒤 비교해야 한다.

## 결과 파일

- k6 script: [reservation-concurrency-baseline.js](../loadtest/reservation-concurrency-baseline.js)
- No-Lock Before: [reservation-http-concurrency-before-summary.json](../loadtest/results/reservation-http-concurrency-before-summary.json)
- Pessimistic After: [reservation-pessimistic-lock-summary.json](../loadtest/results/reservation-pessimistic-lock-summary.json)
- raw k6/Actuator/MySQL snapshot과 애플리케이션 로그는 기존 `.gitignore`의 `build/` 아래에 두며 commit하지 않는다.

## 최종 검증

- `JAVA_TOOL_OPTIONS=-Dspring.jpa.hibernate.ddl-auto=validate` 조건의 `gradlew.bat build --rerun-tasks` 성공
- 전체 30개 테스트 성공, 실패·오류·skip 0
- Facility 1,000,000건 및 고유 이름 1,000,000개 유지
- Facility 데이터 체크섬 합계 `2148598155341640` 유지
- 지역별 건수와 각 지역의 district 10개 유지
- Facility index는 `PRIMARY`, `idx_facility_region`, `idx_facility_region_type`,
  `idx_facility_region_district_type` 그대로 유지
- 실험 fixture 정리 후 Program 0건, Reservation 0건
- Spring Boot health `UP`, Redis `PONG`, Prometheus `public-service` target `UP`
- Hikari max, MySQL isolation, Redis/cache 설정 및 Facility 검색 코드는 변경하지 않음

# Reservation Optimistic Lock — No Retry

## 목적과 변경 범위

Program에 JPA `@Version`만 적용했을 때 고경합 예약의 정합성과 성공률을 측정했다.
Pessimistic 결과와 같은 capacity 100, 서로 다른 participant 200명, HTTP 200 VU × 1회,
3회 반복 조건이다. 측정일은 2026-09-09이며 기준 commit은 `85621cee915ba1ff03d0c5ba0f566c0855c86b2d`다.

- Program에 `@Version @Column(nullable = false) private long version` 추가
- `findByIdForUpdate`와 `PESSIMISTIC_WRITE` 제거, 일반 `findById` 사용
- 기존 `@Transactional`, capacity 확인 → count 증가 → Reservation `saveAndFlush` 유지
- `(program_id, participant_id)` unique constraint 유지
- retry, 충돌의 409 변환, JVM/Redis lock, 별도 정원 조건 UPDATE 없음
- Hikari max 10, MySQL REPEATABLE-READ, API, Redis, Facility 검색 및 k6 script 변경 없음

현재 실행 앱은 이 문서의 retry 없는 Optimistic 구현이다. Pessimistic 구현/결과는 이전 commit과
[Pessimistic 문서](reservation-pessimistic-lock.md)에 보존돼 있다.

## Schema 및 실제 SQL

[schema SQL](../experiments/sql/reservation-optimistic-lock.sql)을 local `public_service` DB에 한 번 적용했다.
유일한 schema 변경은 `program.version BIGINT NOT NULL DEFAULT 0`이다. 적용 전 Program은 0건이었다.
Facility와 Reservation schema에는 변경이 없다. 앱과 테스트 모두 `ddl-auto=validate`로 검증했다.

Hibernate 로그에서 다음 SQL 순서를 확인했다.

```sql
select p1_0.id, p1_0.capacity, p1_0.facility_id, p1_0.name,
       p1_0.reserved_count, p1_0.version
from program p1_0 where p1_0.id=?;

insert into reservation (created_at,participant_id,program_id) values (?,?,?);

update program
set capacity=?,facility_id=?,name=?,reserved_count=?,version=?
where id=? and version=?;
```

SELECT에는 `FOR UPDATE`가 없다. UPDATE의 이전 version과 DB version이 다르면 affected rows=0이 되어
Hibernate가 충돌을 검출하고 transaction 전체를 rollback한다. 먼저 수행된 Reservation INSERT도 rollback돼
예약 행과 count의 불일치를 막는다. 직접 작성한 capacity 조건 SQL은 없으며 version 조건은 JPA가 생성한다.
관련 원리는 [Hibernate 공식 locking 문서](https://github.com/hibernate/hibernate-orm/blob/main/documentation/src/main/asciidoc/userguide/chapters/locking/Locking.adoc)를 참고했다.

## Service 통합 테스트

기존 ExecutorService 200 threads, CountDownLatch 시작 장벽, capacity 100 fixture를 유지했다.
각 실행이 끝나면 해당 Program의 Reservation과 Program만 삭제한다.

| run | 요청 | 성공 | business 409 | optimistic conflict | MySQL 1213 | reservedCount | Reservation rows |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 | 200 | 20 | 0 | 24 | 156 | 20 | 20 |
| 2 | 200 | 20 | 0 | 22 | 158 | 20 | 20 |
| 3 | 200 | 21 | 0 | 21 | 158 | 21 | 21 |

실제 exception chain:

- 낙관적 충돌: `ObjectOptimisticLockingFailureException → StaleObjectStateException → StaleStateException`
- DB deadlock: `CannotAcquireLockException`, SQLException errorCode **1213**, SQLState **40001**

두 종류 외 예외는 없었다. 테스트는 성공 수를 임의로 100으로 고정하지 않고,
`0 < successes <= capacity`, `successes == Reservation rows == reservedCount == version`,
전체 결과 합계 200을 검증한다. 실패 원인을 기록하고 허용하지 않은 예외 유형이 나오면 테스트가 실패한다.
실패 수 자체를 정해 놓거나 동시성 결과를 조작하지 않는다.

## HTTP 실험 방법과 분류

[기존 k6 script](../loadtest/reservation-concurrency-baseline.js)를 수정 없이 사용했다.
SHA256: `640FE0639866B2B49A86528FBEFA00AB1C28E3D1769D569C3AB01D6D4EE1201A`.
`grafana/k6:2.1.0` container에서 Windows host `host.docker.internal:8080`을 호출했다.

각 실행 전 새 Program(capacity=100, count=0, version=0)을 만들고 서로 다른 participant로 요청했다.
실행 후 DB 상태와 오류 카운터를 읽은 다음 해당 fixture만 정리했다. retry와 재전송은 없다.
본 측정 전 별도 capacity=1 fixture에 순차 요청 두 번을 보내 201/409 및 DB count=version=rows=1을 확인했다.
짧은 smoke를 제외한 추가 warm-up이나 캐시 초기화는 하지 않았다.

재실행 예시(프로젝트 root PowerShell):

```powershell
# version schema SQL은 최초 한 번만 적용. 이미 있는 컬럼에 재실행하지 않는다.
# mysql client에서 local public_service DB에 연결한 상태로:
# SOURCE experiments/sql/reservation-optimistic-lock.sql;

# SQL fixture: 생성 후 반환된 ID만 아래에 사용한다.
# INSERT INTO program(capacity,name,reserved_count,facility_id,version)
# SELECT 100,'Optimistic HTTP fixture',0,MIN(id),0 FROM facility;
# SELECT LAST_INSERT_ID();

$programId = 123 # 위에서 생성한 실제 ID로 변경
$runId = 'optimistic-manual-1'
New-Item -ItemType Directory -Force build/k6 | Out-Null
docker run --rm -e "PROGRAM_ID=$programId" -e "RUN_ID=$runId" `
  -e WORKLOAD=reservation_optimistic_lock_after -e VUS=200 `
  -e BASE_URL=http://host.docker.internal:8080 `
  -e "SUMMARY_PATH=/results/$runId.json" `
  -v "${PWD}/loadtest:/scripts:ro" -v "${PWD}/build/k6:/results" `
  grafana/k6:2.1.0 run --quiet /scripts/reservation-concurrency-baseline.js

# 측정 후 해당 ID의 count/version/Reservation rows 확인.
# DELETE FROM reservation WHERE program_id=<생성한 ID>;
# DELETE FROM program WHERE id=<생성한 ID>;
```

201과 정원/중복 예약에 대한 409는 기존 script대로 정상 응답이다. 낙관적 충돌을 409로 바꾸지 않았으므로
이번 충돌은 모두 500이며 `http_req_failed`에 포함된다. k6의 check 통과는 응답 분류가 가능하다는 뜻이지
예약 성공률이 정상이라는 뜻이 아니다.

각 run의 앱 로그 범위를 잘라 `dispatcherServlet`의 **ERROR 헤더 한 줄당 한 요청**으로 집계했다.
stack frame의 중복 문자열은 세지 않았다. HTTP 낙관적 충돌은 위 Spring 예외와 root `StaleStateException`,
deadlock은 `CannotAcquireLockException`과 root `MySQLTransactionRollbackException`으로 확인했다.
Performance Schema `events_errors_summary_global_by_error`의 ERROR_NUMBER=1213 전후 증가량도 대조했다.
모든 run에서 **HTTP 5xx = optimistic conflict + 1213**, 로그 deadlock 수 = DB 1213 증가량이었다.
테스트 실행과 HTTP 측정은 겹치지 않았으며 다른 예약 부하는 없었다.

## HTTP 결과

| run | 201 | 409 | optimistic conflict | MySQL 1213 | 5xx | 실패율 | reservedCount / rows |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 | 25 | 0 | 28 | 147 | 175 | 87.5% | 25 / 25 |
| 2 | 23 | 0 | 24 | 153 | 177 | 88.5% | 23 / 23 |
| 3 | 21 | 0 | 20 | 159 | 179 | 89.5% | 21 / 21 |

매회 200건 모두 HTTP 응답을 받았다. 미분류/network failure는 0이다. 정원에 도달하지 못해 business 409는
0건이었다. `201 == rows == reservedCount <= 100`을 모두 만족하여 overbooking/lost update는 없었다.
하지만 성공률은 10.5~12.5%이며 정원 100 중 75~79자리가 남았다.

| run | 전체 RPS | 정상 201 RPS | avg (ms) | p95 (ms) | p99 (ms) | k6 완료 (ms) |
|---:|---:|---:|---:|---:|---:|---:|
| 1 | 116.52 | 14.56 | 846.04 | 1,347.87 | 1,387.07 | 1,716.49 |
| 2 | 116.82 | 13.43 | 867.41 | 1,393.96 | 1,415.78 | 1,712.10 |
| 3 | 112.30 | 11.79 | 893.88 | 1,375.09 | 1,408.50 | 1,781.01 |

## Hikari, CPU, DB lock

| run | active max | pending max | idle min | process CPU max | system CPU max | row-lock waits | 누적 lock wait (ms) |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 | 10 | 61 | 0 | 37.20% | 100% | 325 | 1,819 |
| 2 | 10 | 141 | 0 | 33.69% | 100% | 319 | 2,335 |
| 3 | 10 | 98 | 0 | 26.37% | 100% | 323 | 2,422 |

Before와 동일하게 Actuator metric을 순차 조회한 뒤 200ms sleep했다. 실제 표본 간격은 API 응답시간 때문에
200ms보다 길며, gauge 갱신 간격과 짧은 burst 때문에 실제 peak를 놓칠 수 있다. CPU는 요청당 CPU 시간이 아닌
사용률 표본이고 system CPU에는 Windows/IDE/Docker 전체 부하가 포함된다. Prometheus 15초 scrape는 유지했지만
약 1.7초 burst의 peak 측정에는 부족하여 위 값은 직접 Actuator 표본을 사용했다.

row-lock 시간은 MySQL GLOBAL STATUS 전후 차이인 누적 wait 시간이며 wall-clock 완료 시간과 다르다.
Optimistic이라고 쓰기 시점의 DB lock이 사라지지는 않는다. `SHOW ENGINE INNODB STATUS`의 마지막 deadlock은
HTTP run 3 Program id=53에 대한 다음 UPDATE였다.

```sql
update program set capacity=100, facility_id=1, name='HTTP concurrency baseline 3',
reserved_count=21, version=21 where id=53 and version=20;
```

두 transaction 모두 `program.PRIMARY`의 S record lock을 보유하고 X record lock을 기다렸으며,
MySQL은 그중 하나를 rollback했다. 기존 Reservation INSERT의 FK 검사 → Program UPDATE 순서를 유지했기 때문에
parent S lock에서 X lock으로의 순환 대기가 남는다. version 검사까지 진행한 요청은 stale version을 검출하지만,
그 전에 deadlock victim이 된 요청은 1213으로 끝난다. 두 실패는 같은 원인이 아니며 각각 집계해야 한다.

## 세 전략 비교

평균은 세 run의 RPS/p95 산술평균이다. p95를 원시 요청 전체로 합쳐 다시 계산한 값은 아니다.

| 지표 | No-Lock | Pessimistic | Optimistic, no retry |
|---|---:|---:|---:|
| 201 / run | 36~41 | 100 | 21~25 |
| 409 / run | 0 | 100 | 0 |
| optimistic conflict / run | 0 | 0 | 20~28 |
| 1213 / run | 159~164 | 0 | 147~159 |
| 5xx / run | 159~164 | 0 | 175~179 |
| reservedCount / rows | 18~20 / 36~41 (불일치) | 100 / 100 | 21~25 / 동일 |
| 평균 전체 RPS | 126.02 | 117.06 | 115.21 |
| 평균 정상 201 RPS | 24.39 | 58.53 | 13.26 |
| 평균 p95 (ms) | 1,222.88 | 1,453.69 | 1,372.31 |
| Hikari active max | 10 | 10 | 10 |
| Hikari pending max 범위 | 73~172 | 135~170 | 61~141 |
| process CPU max 범위 | 24.43~39.68% | 23.47~30.52% | 26.37~37.20% |
| 정합성 | lost update | 유지 | 유지 |

Optimistic은 Pessimistic보다 평균 p95가 약 5.6% 낮았지만 정상 201 처리량은 약 77.3% 낮았다.
실패가 많은 workload의 짧은 응답을 성공 처리 비용 감소로 해석하면 안 된다. 성공률과 사용자 결과를 우선하면
현재 고경합 단일 Program 조건에서는 Pessimistic의 100 성공/100 business rejection이 더 유용한 결과다.
낙관적 방식은 선행 조회에서 배타 락을 잡지 않고 stale write를 검출하는 장점이 있지만, 저경합·다중 Program의
성능 우위는 이번 실험에서 검증하지 않았다.

## Retry 필요성 판단

정합성 보호만을 위해 retry가 필요한 것은 아니다. 이미 rollback과 version 검증으로 불변식은 유지됐다.
그러나 이 구현으로 고경합 예약의 정상 처리율을 확보하려면 실패 요청의 재처리를 검토할 근거가 충분하다.
낙관적 충돌만 retry하면 147~159건의 deadlock 실패는 그대로 남으므로 실패 종류별 정책을 평가해야 한다.
다음 실험에서는 새로운 transaction에서 최신 Program을 다시 읽는 제한된 retry, backoff, 최대 횟수와
중복 예약 제약을 고려해 성공률·추가 DB 작업·tail latency를 함께 비교할 수 있다.
무제한 retry는 부하를 증폭할 수 있으며 retry가 Pessimistic보다 낫다고 아직 결론내릴 수 없다.
**이번 구현에는 retry를 추가하지 않았다.**

## 최종 검증 및 산출물

- `JAVA_TOOL_OPTIONS=-Dspring.jpa.hibernate.ddl-auto=validate`로 `gradlew.bat build --rerun-tasks --console=plain` 성공(50초)
- 전체 30개 테스트, 실패/오류/skip 0. 단일 예약 성공·정원·중복·404 테스트 유지
- Facility 1,000,000건, 고유 이름 1,000,000개, 기존 checksum 합계 `2148598155341640` 유지
- Facility PRIMARY와 `idx_facility_region`, `idx_facility_region_type`, `idx_facility_region_district_type` 유지
- Reservation unique constraint 유지, 실험 fixture 정리 후 Program/Reservation 각각 0건
- Redis PONG; Facility A 두 번 조회 content=20/total=50000, cache hit 증가 1 확인
- 앱 health UP, Prometheus target UP, MySQL/Redis/Prometheus/Grafana running
- Hikari max=10, isolation=REPEATABLE-READ 유지. 설정 파일 및 기존 성능 결과 파일 변경 없음
- [결과 JSON](../loadtest/results/reservation-optimistic-lock-summary.json)에 각 run 수치와 예외 집계 기록
- raw k6 JSON, 실행별 앱 로그와 Actuator 관측, InnoDB status는 `build/`에만 보관하며 commit 제외

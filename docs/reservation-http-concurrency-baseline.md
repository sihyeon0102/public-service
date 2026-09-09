# Reservation HTTP Concurrency Before Baseline

## 목적

일반 `@Transactional`만 적용된 No-Lock 예약 로직을 실제 HTTP 경로에서 동시에 호출해 정합성과
성능을 기록한다. 이 결과는 이후 pessimistic lock과 optimistic lock을 같은 조건으로 비교할 기준이다.

이번 측정에서는 Reservation Service, Controller, Entity, Repository, isolation level, Hikari 설정을
수정하지 않았다. `@Version`, 명시적 row lock, retry, JVM/Redis lock, atomic conditional update도 없다.

## 환경

- 기준 commit: `a54d0839c8357fb01b9095ca703f01f826b6bcba`
- Java 21.0.12.1 / Spring Boot 4.1.1
- Windows host의 Spring Boot `:8080`
- Docker MySQL 8.4.11, `REPEATABLE-READ`
- Hikari max=10
- Prometheus scrape interval=15초
- Facility 1,000,000건과 기존 검색 인덱스 세 개
- Redis cache와 기존 monitoring stack 유지
- k6 `grafana/k6:2.1.0`

## Fixture

운영 reset API는 추가하지 않았다. 매 실행 전에 기존 Facility의 가장 작은 ID를 참조하는 새 Program을
직접 생성한다. 새 Program은 `reserved_count=0`, capacity 100이므로 다른 실행의 상태와 완전히 분리된다.
결과를 읽은 뒤 해당 `program_id`의 Reservation과 Program만 삭제한다. 기존 Program이나 다른 테이블을
일괄 삭제하지 않는다.

개념적인 fixture SQL은 다음과 같다.

```sql
INSERT INTO program(capacity, name, reserved_count, facility_id)
SELECT 100, 'HTTP concurrency baseline', 0, MIN(id)
FROM facility;

-- k6 종료 후, 정리 전에 결과 측정
SELECT p.capacity, p.reserved_count, COUNT(r.id)
FROM program p
LEFT JOIN reservation r ON r.program_id = p.id
WHERE p.id = ?
GROUP BY p.id, p.capacity, p.reserved_count;

-- 이 실행에서 만든 ID만 정리
DELETE FROM reservation WHERE program_id = ?;
DELETE FROM program WHERE id = ?;
```

Smoke에서는 capacity 1인 별도 Program에 1 VU/1 request를 보내 HTTP 201,
`reservedCount=1`, Reservation 1행을 확인하고 정리했다.

## k6 동시 요청 설계

[reservation-concurrency-baseline.js](../loadtest/reservation-concurrency-baseline.js)는
`per-vu-iterations` executor를 사용한다.

- VU 200
- VU당 iteration 1
- 전체 POST 요청 200
- 시작 stage나 think time 없이 VU를 동시에 생성
- max duration 60초
- participant ID: `k6-<run-id>-<VU idInTest>`
- 각 participant는 한 번만 요청

각 VU는 다음 요청을 한 번 보낸다.

```http
POST /api/programs/{programId}/reservations
Content-Type: application/json

{"participantId":"k6-run-1-1"}
```

로컬 OS와 Docker scheduler 수준에서 가능한 한 동시에 시작하는 one-shot race다. 처리량 안정 구간을
측정하는 지속 부하테스트가 아니므로 별도 think time이나 ramp-up은 사용하지 않는다. k6의 RPS는 200개
HTTP 요청이 완료된 실제 request window를 기준으로 한다.

실행 예:

```powershell
New-Item -ItemType Directory -Force build/k6 | Out-Null

docker run --rm `
  -e PROGRAM_ID=<fixture-program-id> `
  -e RUN_ID=run-1 `
  -e VUS=200 `
  -e BASE_URL=http://host.docker.internal:8080 `
  -e SUMMARY_PATH=/results/reservation-http-run-1.json `
  -v "${PWD}/loadtest:/scripts:ro" `
  -v "${PWD}/build/k6:/results" `
  grafana/k6:2.1.0 run --quiet /scripts/reservation-concurrency-baseline.js
```

## 측정 방법

- HTTP 상태, RPS, avg, p95, p99, failed rate: k6 summary
- MySQL deadlock: 실행 직전과 직후
  `performance_schema.events_errors_summary_global_by_error`의 error 1213 누계 차이
- 최종 DB 상태: fixture를 정리하기 전 Program과 Reservation 직접 조회
- Hikari active/idle/pending과 process/system CPU: k6 process가 실행되는 동안 Actuator를 약 200ms
  간격으로 읽은 최대/최소값

기존 Prometheus scrape interval 15초는 바꾸지 않았다. 예약 burst의 실제 HTTP 구간은 약
1.47~1.74초여서 Prometheus 한 표본 사이에서 끝날 수 있다. 따라서 짧은 peak는 같은 Actuator meter를
직접 표본화했다. 이 보조 관측 요청은 DB를 사용하지 않으며 다음 lock 실험에서도 같은 방식으로 반복해야
한다.

Prometheus endpoint에서도 실제 meter가 다음 URI template과 예외 label로 누적되는 것을 확인했다.

```text
http_server_requests_seconds_count{
  method="POST",
  uri="/api/programs/{programId}/reservations",
  status="500",
  exception="CannotAcquireLockException"
}
```

## 정상 불변식

1. `Reservation rows <= capacity`
2. `reservedCount <= capacity`
3. `reservedCount == Reservation rows`
4. HTTP 201로 정상 처리된 예약 수와 실제 Reservation rows가 일치한다.

## 실제 HTTP 결과

| run | 요청 | 201 | 409 | 5xx | MySQL 1213 | reservedCount | Reservation rows |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 | 200 | 39 | 0 | 161 | 161 | 19 | 39 |
| 2 | 200 | 41 | 0 | 159 | 159 | 20 | 41 |
| 3 | 200 | 36 | 0 | 164 | 164 | 18 | 36 |

각 실행에서 HTTP 5xx 수와 MySQL error 1213 증가량이 정확히 일치했다. 따라서 5xx는 네트워크나 JSON
오류가 아니라 No-Lock 트랜잭션의 deadlock 실패다. 정원 409는 한 번도 발생하지 않았다.

201 성공 수는 실제 Reservation 행과 매번 일치했다. 성공 응답을 받은 개별 트랜잭션은 commit됐지만,
그 트랜잭션들이 갱신한 Program count는 서로 덮어써 절반가량만 남았다.

## HTTP 성능

| run | RPS | avg | p95 | p99 | max | HTTP failed rate | 성공 RPS |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 | 115.07 | 873.13ms | **1,449.40ms** | 1,471.32ms | 1,481.59ms | **80.5%** | 22.44 |
| 2 | 135.66 | 759.52ms | **1,130.38ms** | 1,162.31ms | 1,188.22ms | **79.5%** | 27.81 |
| 3 | 127.32 | 835.84ms | **1,088.86ms** | 1,114.19ms | 1,119.46ms | **82.0%** | 22.92 |

전체 RPS에는 빠르게 rollback된 5xx도 포함된다. 따라서 115~136 RPS를 정상 예약 처리량으로 볼 수
없다. 실제 정상 성공 RPS는 약 22.4~27.8이고, 요청의 약 80%가 실패했다. 이후 lock 방식 비교에서는
RPS뿐 아니라 201 수, 오류율과 최종 불변식을 먼저 비교해야 한다.

## Hikari와 CPU

| run | Hikari active max | idle min | pending max | pool max | process CPU max | system CPU max |
|---:|---:|---:|---:|---:|---:|---:|
| 1 | **10** | 0 | **98** | 10 | 39.68% | 100.00% |
| 2 | **10** | 0 | **73** | 10 | 33.59% | 99.75% |
| 3 | **10** | 0 | **172** | 10 | 24.43% | 100.00% |

세 실행 모두 active가 pool max 10에 도달하고 idle은 0까지 내려갔다. pending도 73~172까지 증가했다.
이는 pool 크기가 원인이라는 결론이 아니다. 같은 Program row의 갱신과 deadlock 처리로 connection 반환이
늦어지면서 200개 HTTP 요청이 10개 connection 앞에 대기한 결과다. Hikari 크기를 바꾸지 않아 다음
실험과 비교할 수 있다. system CPU는 Windows host 전체이므로 Docker, IDE와 다른 프로세스를 포함한다.

## 깨진 불변식과 원인

| 불변식 | run 1 | run 2 | run 3 |
|---|---|---|---|
| Reservation rows <= 100 | 유지 | 유지 | 유지 |
| reservedCount <= 100 | 유지 | 유지 | 유지 |
| reservedCount == Reservation rows | **위반: 19 != 39** | **위반: 20 != 41** | **위반: 18 != 36** |
| HTTP 201 == Reservation rows | 유지 | 유지 | 유지 |

이번 timing에서는 deadlock rollback이 많아 overbooking은 발생하지 않았다. 이것은 정원 보호가 정상이라는
뜻이 아니다. 여러 트랜잭션이 같은 이전 `reservedCount`를 읽은 뒤 JPA dirty checking의
`reserved_count = ?` UPDATE로 같은 증가 값을 쓰면서 commit된 증가가 유실됐다.

Reservation insert의 FK 검사가 Program parent row에 S lock을 잡은 상태에서 같은 flush의 Program
UPDATE가 X lock을 기다리는 S→X 경쟁도 반복됐다. MySQL은 transaction을 rollback해 1213을 반환했고,
애플리케이션에는 retry나 deadlock 변환 처리가 없으므로 HTTP 500이 됐다.

## Service 통합 테스트와 비교

| 경로 | 성공 범위 | 실패/deadlock 범위 | reservedCount | Reservation rows | 결과 |
|---|---:|---:|---:|---:|---|
| Service 통합 테스트 | 37~41 | 159~163 | 18~21 | 37~41 | 매회 count mismatch |
| 실제 HTTP k6 | 36~41 | 159~164 | 18~20 | 36~41 | 매회 count mismatch |

HTTP serialization, DispatcherServlet과 네트워크 경로를 포함해도 결과 범위가 거의 동일했다. 두 방식 모두
lost update와 MySQL 1213이 반복됐으므로 Service 통합 테스트가 발견한 경쟁 조건이 실제 API에서도
그대로 노출됨을 확인했다.

## 비교 기준

다음 pessimistic/optimistic lock 실험은 같은 script, capacity 100, unique participant 200명,
Hikari max 10과 fixture 절차를 유지해야 한다. 최소 비교 항목은 다음과 같다.

- Reservation rows와 reservedCount가 모두 100이고 서로 같은지
- 201/409/5xx 합계가 200인지
- MySQL 1213과 retry 횟수
- 전체 RPS보다 정상 201 처리량
- avg/p95/p99와 Hikari active/pending
- DB lock wait와 CPU

현재 단계에서는 어느 lock도 적용하거나 우열을 결론내리지 않았다.

## 결과 파일

- 실행 script: [reservation-concurrency-baseline.js](../loadtest/reservation-concurrency-baseline.js)
- 비교용 요약: [reservation-http-concurrency-before-summary.json](../loadtest/results/reservation-http-concurrency-before-summary.json)
- raw k6 JSON, Actuator 표본과 실행 로그: `build/k6/` 아래 보관하며 기존 `.gitignore`에 따라 commit하지 않는다.

## 최종 검증

- `JAVA_TOOL_OPTIONS=-Dspring.jpa.hibernate.ddl-auto=validate` 조건에서
  `.\gradlew.bat build --rerun-tasks` 성공
- 전체 테스트 30개, 실패/오류/skip 0
- build가 다시 실행한 Service 동시성 테스트 3회에서도 성공 35~41, deadlock 159~165,
  reservedCount 19~20과 Reservation 35~41행으로 count mismatch 재현
- 모든 fixture 정리 후 Program 0행, Reservation 0행
- Facility 1,000,000건, distinct name 1,000,000건, CRC32 합계 `2148598155341640` 유지
- 기존 지역 분포와 region별 district 10개 유지
- Facility 인덱스는 PRIMARY와 기존 세 검색 인덱스만 유지
- Redis PONG, Spring Boot health UP, Prometheus public-service target UP
- Java 기능 코드, DB schema, Hikari, Redis, monitoring, 기존 성능 결과 파일에는 Git 변경 없음

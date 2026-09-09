# Reservation Concurrency Baseline

## 목적

Facility에 연결되는 최소 Program/Reservation 기능을 만들고, 별도 동시성 제어 없이 일반적인
`@Transactional`만 사용했을 때 정원과 예약 수의 정합성이 유지되는지 실제 MySQL에서 검증한다.

이번 구현에는 pessimistic lock, `SELECT FOR UPDATE`, optimistic lock/`@Version`, JVM lock,
Redis lock, atomic update, isolation 변경, retry와 queue가 없다. MySQL/InnoDB가 트랜잭션과 FK를
처리하기 위해 내부적으로 사용하는 lock은 DB의 정상 동작이며 애플리케이션의 동시성 해결책으로
추가한 것이 아니다.

`@Transactional`은 한 요청 안의 Program 변경과 Reservation 저장을 함께 commit 또는 rollback한다.
그러나 서로 다른 트랜잭션이 같은 `reservedCount`를 읽고 수정하는 read-modify-write 경쟁까지
자동으로 직렬화하지는 않는다. 이 한계를 측정하기 위해 먼저 lock-free baseline을 둔다.

## 최소 도메인과 API

### Program

- `id`: auto increment PK
- `facility_id`: 기존 Facility FK, NOT NULL
- `name`: 프로그램 이름, NOT NULL
- `capacity`: 정원
- `reserved_count`: 현재 예약 수, 초기값 0

### Reservation

- `id`: auto increment PK
- `program_id`: Program FK, NOT NULL
- `participant_id`: 예약자 식별자, NOT NULL
- `created_at`: 예약 생성 시각, NOT NULL
- `UNIQUE(program_id, participant_id)`: 같은 사용자의 같은 프로그램 중복 예약 방지

```http
POST /api/programs/{programId}/reservations
Content-Type: application/json

{"participantId":"participant-1"}
```

성공은 HTTP 201이며 예약 ID, program ID, participant ID, 생성 시각, 현재 예약 수와 정원을 반환한다.
Program이 없으면 404, 정원이 찼거나 중복 예약이면 409, participant ID가 비어 있으면 400이다.

Service의 순서는 의도적으로 단순하다.

1. Program을 일반 `findById`로 조회한다.
2. `reservedCount >= capacity`이면 409를 반환한다.
3. 영속 상태 Program의 `reservedCount`를 1 증가시킨다.
4. Reservation을 저장한다.
5. 일반 `@Transactional` 경계에서 commit한다.

## 정상 불변식

동시 요청이 끝난 뒤 다음 조건이 모두 성립해야 정상이다.

1. 성공한 예약 수는 `capacity` 이하다.
2. 실제 Reservation 행 수는 `capacity` 이하다.
3. `Program.reservedCount == 실제 Reservation 행 수`다.
4. 최종 예약 상태가 `capacity`를 초과하지 않는다.

중복 예약 제약 때문에 서로 다른 participant ID 200개를 사용했다. 따라서 unique 충돌은 이번 정원
실험의 변수가 아니다.

## 통합 테스트 방법

[ReservationConcurrencyBaselineTests](../src/test/java/me/chung/publicservice/service/ReservationConcurrencyBaselineTests.java)는
실제 `public_service` MySQL을 사용한다.

- 기존 Facility 중 첫 행을 참조하는 capacity 100 Program을 생성한다.
- 200개 fixed thread pool과 200-count ready latch를 만든다.
- 모든 작업이 준비된 뒤 start latch를 한 번에 열어 같은 Service proxy를 호출한다.
- 각 작업은 서로 다른 participant ID를 사용한다.
- Future를 모두 기다린 뒤 성공/실패, `reservedCount`, Reservation COUNT와 예외 종류를 읽는다.
- 실험이 끝나면 생성한 Program ID에 속한 Reservation과 Program만 삭제한다.
- 같은 테스트를 세 번 반복한다.

Hikari max는 기존 10 그대로다. 실제 isolation level도 변경하지 않았으며 MySQL에서
`REPEATABLE_READ`로 확인됐다. 다음 명령으로 재실행할 수 있다.

```powershell
$env:JAVA_TOOL_OPTIONS='-Dspring.jpa.hibernate.ddl-auto=validate'
.\gradlew.bat test `
  --tests "me.chung.publicservice.service.ReservationConcurrencyBaselineTests" `
  --rerun-tasks
```

테스트는 race가 없다고 가정하지 않는다. 네 불변식 중 실제로 하나 이상 깨졌는지 검증하므로 현재
baseline의 문제 재현을 통과 조건으로 사용한다.

## 실제 결과

2026-09-09, MySQL 8.4.11에서 기록한 최종 세 번의 실행 결과다.

| run | capacity | 요청 | 성공 | 실패 | reservedCount | Reservation 행 | overbooking | 예외 |
|---:|---:|---:|---:|---:|---:|---:|---|---|
| 1 | 100 | 200 | 38 | 162 | 18 | 38 | 없음 | `CannotAcquireLockException`, MySQL 1213 / SQLState 40001: 162 |
| 2 | 100 | 200 | 41 | 159 | 21 | 41 | 없음 | `CannotAcquireLockException`, MySQL 1213 / SQLState 40001: 159 |
| 3 | 100 | 200 | 37 | 163 | 18 | 37 | 없음 | `CannotAcquireLockException`, MySQL 1213 / SQLState 40001: 163 |

세 실행 모두 다음 결과가 반복됐다.

- 성공 수와 실제 Reservation 행 수는 일치했다.
- 성공 및 Reservation 행은 capacity 100을 넘지 않아 **이번 실행에서는 overbooking이 발생하지 않았다.**
- `reservedCount`는 실제 행보다 각각 20, 20, 19 작았다.
- `reservedCount == Reservation 행 수` 불변식은 세 번 모두 깨졌다.
- 실패는 정원 409가 아니라 MySQL deadlock으로 인한 `CannotAcquireLockException`이었다.

최종 build 전 탐색 실행 여섯 번도 성공 36~45건, reservedCount 18~25건 범위에서 모두 같은 count
mismatch가 발생했다. 최종 build를 포함한 총 아홉 번 모두 불변식 위반이 재현됐다.

## 발생한 race condition

여러 트랜잭션이 같은 `reservedCount` 값을 읽은 뒤 각자 1을 더한 같은 값을 UPDATE했다. UPDATE는
`reserved_count = reserved_count + 1` 형태의 atomic SQL이 아니라 JPA dirty checking이 만든
`reserved_count = ?` 형태다. 늦게 commit한 값이 앞선 증가를 덮어써 실제 Reservation 행 증가분 일부가
Program count에서 사라졌다. 이것이 관찰된 lost update다.

`SHOW ENGINE INNODB STATUS`의 마지막 deadlock 기록에서는 두 트랜잭션이 모두 같은 Program PK 레코드의
S lock을 보유한 상태에서 `update program ... reserved_count=19 where id=...`를 위해 X lock을 기다렸고,
MySQL이 한 트랜잭션을 rollback했다. 이 lock 패턴은 Reservation insert의 FK 검사가 parent Program에
S lock을 잡은 뒤, 같은 flush에서 Program dirty update가 X lock 승격을 요구하는 흐름과 일치한다.

따라서 이번 결과에는 두 문제가 함께 있다.

1. commit에 성공한 요청끼리도 동일한 이전 값을 기반으로 써서 `reservedCount` 증가가 유실된다.
2. 동시 트랜잭션의 S→X lock 경쟁으로 대량의 deadlock과 요청 실패가 발생한다.

deadlock이 많은 요청을 rollback한 결과 이번 timing에서는 overbooking이 나타나지 않았다. 이것을 정원
보호 수단으로 볼 수는 없다. 애플리케이션이 capacity 검사를 원자적으로 보장하지 않으며, 실행 순서나
flush 패턴이 달라지면 stale capacity 판정으로 capacity 초과 Reservation이 commit될 가능성이 남아 있다.

## 단일 요청 검증

[ReservationControllerIntegrationTests](../src/test/java/me/chung/publicservice/controller/ReservationControllerIntegrationTests.java)는
다음을 실제 MySQL과 MockMvc로 검증한다.

- 여유 정원에서 HTTP 201, `reservedCount` 증가 및 Reservation 저장
- 정원이 찬 뒤 다음 participant에게 HTTP 409
- 같은 `(program_id, participant_id)`의 두 번째 요청에 HTTP 409
- 존재하지 않는 Program에 HTTP 404

fixture는 기존 Facility를 읽기만 하고 각 테스트가 만든 Program/Reservation만 ID 조건으로 정리한다.
테스트 전후 새 두 테이블은 0행이다.

## 다음 실험 가설

아직 어느 lock이 최선이라고 결론내리지 않는다. 다음 단계에서 같은 capacity 100 / 200 participant 조건과
불변식을 그대로 사용해 후보를 각각 검증할 수 있다.

- **Pessimistic lock:** Program 조회 시 row lock으로 capacity 확인과 증가를 직렬화하면 lost update와
  overbooking을 막을 수 있는지, 처리량과 대기 시간이 어떻게 변하는지 측정한다.
- **Optimistic lock:** `@Version` 충돌로 stale update를 검출할 수 있는지, 충돌률과 사용자 실패율,
  retry 정책을 포함할 때의 비용을 측정한다.

현재 코드에는 두 방식 모두 적용하지 않았다. baseline test의 성공 조건과 결과는 해결책 적용 후에는
반대로 정상 불변식을 검증하도록 변경해야 한다.

## 최종 검증

- `.\gradlew.bat build --rerun-tasks`를 `ddl-auto=validate`로 실행했고 전체 30개 테스트가
  실패·오류·skip 없이 통과했다. 기존 23개와 예약 API 4개, 동시성 반복 3개다.
- 수동으로 맞춘 신규 `program.name NOT NULL`을 포함해 애플리케이션 context의 schema validation을
  다시 실행해 통과했다.
- 실험 fixture 정리 후 `program`과 `reservation`은 각각 0행이다.
- Facility는 1,000,000건, distinct name 1,000,000건, 기존 CRC32 합계
  `2148598155341640`, 지역별 분포와 region별 district 10개를 유지했다.
- Facility 인덱스는 `PRIMARY(id)`, `idx_facility_region(region)`,
  `idx_facility_region_type(region,type)`,
  `idx_facility_region_district_type(region,district,type)` 그대로다.
- 기존 Facility/캐시/monitoring 코드와 설정, 성능 결과 파일, Hikari 설정에는 Git diff가 없다.
- MySQL/Redis/Prometheus/Grafana는 실행 중이며 Redis PONG, Spring Boot health와 Prometheus target은
  UP으로 확인했다.

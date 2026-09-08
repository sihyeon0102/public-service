# Facility 성능 실험용 데이터

## 실행

Java 21, 기존 Docker MySQL 연결 및 빈 `facility` 테이블이 필요하다.
프로젝트 루트 PowerShell에서 실행한다.

```powershell
.\gradlew.bat build

# 100,000건: 최초 검증용
java -jar build/libs/public-service-0.0.1-SNAPSHOT.jar --spring.profiles.active=seed --seed.facility.count=100000

# 500,000건: 테이블을 명시적으로 초기화한 다음 실행
java -jar build/libs/public-service-0.0.1-SNAPSHOT.jar --spring.profiles.active=seed --seed.facility.count=500000

# 1,000,000건: 전체 실험을 시작하기로 결정하고 테이블을 초기화한 다음 실행
java -jar build/libs/public-service-0.0.1-SNAPSHOT.jar --spring.profiles.active=seed --seed.facility.count=1000000
```

`seed.facility.count`는 기본 100,000이며 100~1,000,000 사이의 100의 배수만 허용한다.
이는 지역 비율을 정수 건수로 정확히 유지하기 위한 제한이다.
숫자는 추가 건수가 아니라 빈 테이블에 생성할 총 건수다.
100,000건이 있는 상태에서 1,000,000건을 요청하면 중단한다.

`seed` 프로필에서만 생성기가 Bean으로 등록된다. 이 프로필은 웹 서버를 시작하지 않고,
JPA DDL을 `validate`로 설정하며, 성공 후 애플리케이션 컨텍스트를 닫고 종료한다.
일반 `bootRun` 또는 프로필 없는 JAR 실행에서는 데이터가 생성되지 않는다.
일반 실행 환경에 `SPRING_PROFILES_ACTIVE=seed`를 지정하지 않는다.
기존 `application.properties`, DB 접속정보, `compose.yml`, `build.gradle`은 변경하지 않는다.

## JDBC Batch 및 중복 방지

- 기존 DataSource와 JDBC `PreparedStatement.addBatch()/executeBatch()`를 사용한다.
- Batch size는 1,000건이다. 전체 데이터를 List나 JPA 영속성 컨텍스트에 보관하지 않는다.
- `rewriteBatchedStatements=true`는 `application-seed.properties`에서만 적용한다.
  MySQL Connector/J가 이 INSERT 배치를 여러 행 INSERT로 재작성할 수 있게 한다.
- 같은 JDBC 연결에서 MySQL `GET_LOCK`으로 동시 seed 실행을 차단한다.
  이 잠금은 seed 실행끼리의 협조적 잠금이며, 임의의 외부 INSERT를 차단하지 않는다.
  seed/reset 중에는 다른 데이터 쓰기 작업을 실행하지 않는다.
- 테이블에 한 행이라도 있으면 삽입 전에 실패한다. 자동 삭제나 자동 이어넣기는 없다.
- 모든 batch는 하나의 트랜잭션에서 실행하고 마지막에 한 번 commit한다.
  실패하면 전체 롤백한다. 데이터가 늘어나면 단일 트랜잭션의 undo/redo 비용도 증가한다.
- 100,000건마다 진행 로그를 남긴다. 진행 로그는 아직 commit 전이며,
  최종 `Facility seed committed` 로그가 완료를 뜻한다.
- 자동 증가 PK는 롤백만으로 초기화되지 않는다. PK까지 같은 값으로 재현하려면 아래 TRUNCATE를 사용한다.

## 결정적인 분포

| region | 비율 | 100,000건 | 1,000,000건 |
|---|---:|---:|---:|
| SEOUL | 30% | 30,000 | 300,000 |
| GYEONGGI | 30% | 30,000 | 300,000 |
| BUSAN | 15% | 15,000 | 150,000 |
| INCHEON | 8% | 8,000 | 80,000 |
| DAEGU | 8% | 8,000 | 80,000 |
| DAEJEON | 5% | 5,000 | 50,000 |
| GWANGJU | 4% | 4,000 | 40,000 |

위 지역 순서로 생성한다. 지역 내부의 0부터 시작하는 순번을 `i`라고 하면:

- district: `<REGION>-DISTRICT-<i % 10 + 1>`, 지역당 가상 district 10개.
  실제 행정구역 데이터가 아니다. 예: `SEOUL-DISTRICT-1`.
- type: `FacilityType.values()[(i / 10) % 유형수]` (정수 나눗셈).
  현재 `SPORTS_CENTER`, `LIBRARY`, `CULTURAL_CENTER`, `COMMUNITY_CENTER`, `PARK`, `OTHER`.
- operatingStatus: `OperatingStatus.values()[(i / (10 * 유형수)) % 상태수]`.
  현재 `OPERATING`, `TEMPORARILY_CLOSED`, `CLOSED`.
- 각 district에 모든 유형이 들어가고 각 district/type에 모든 상태가 섞인다.
  유형과 상태는 거의 균등하며, 지역별 마지막 주기의 나머지 때문에 소폭 차이가 난다.
- name: 전체 생성 순서대로 `Facility-1`부터 `Facility-N`까지. DB의 PK와는 별개다.
- address: `<district> Test Road <i + 1>`.
- latitude: 지역별 기준 위도 + `(i % 1000) * 0.00001`.
- longitude: 지역별 기준 경도 + `((i / 1000) % 1000) * 0.00001`.
  주소와 좌표는 성능 테스트용 합성 값이며 실제 시설 위치를 의미하지 않는다.

Random을 사용하지 않는다. 동일 코드·Enum 순서·생성 개수로 다시 실행하면 PK를 제외한 동일 데이터를 얻는다.
지역별로 연속 삽입하므로 PK와 region에 상관관계가 있다. 향후 실행계획·성능 비교 시
이 분포와 삽입 순서를 유지하여 비교한다.

## MySQL 검증 및 명시적 초기화

현재 Docker 컨테이너에 접속한다. 비밀번호는 프롬프트에 입력한다.

```powershell
docker exec -it public-service-mysql mysql -uroot -p
```

`application.properties`의 JDBC URL에 지정된 데이터베이스를 선택한 다음 실행한다.

```sql
USE public_service;

SELECT COUNT(*) AS total FROM facility;
SELECT region, COUNT(*) AS cnt FROM facility GROUP BY region ORDER BY region;
SELECT region, district, COUNT(*) AS cnt FROM facility GROUP BY region, district ORDER BY region, district;
SELECT type, COUNT(*) AS cnt FROM facility GROUP BY type ORDER BY type;
SELECT operating_status, COUNT(*) AS cnt FROM facility GROUP BY operating_status;
SELECT COUNT(DISTINCT name) AS unique_names FROM facility;
SHOW INDEX FROM facility;
```

실험을 다시 시작할 때만 아래 초기화를 직접 실행한다.
**선택한 DB의 facility 전체 데이터가 삭제되며 TRUNCATE는 롤백할 수 없다.**
실행 전에 `SELECT DATABASE()`로 실험용 DB인지 확인한다.

```sql
SELECT DATABASE();
TRUNCATE TABLE facility;
SELECT COUNT(*) AS total FROM facility;
```

TRUNCATE는 AUTO_INCREMENT도 초기화한다. 이후 원하는 생성량의 seed 명령을 실행한다.
생성기는 직접 초기화하지 않으며 PK 이외의 인덱스도 생성하지 않는다.

## 테스트

```powershell
.\gradlew.bat build
```

기존 실제 MySQL API 테스트를 유지한다. 일반 컨텍스트에서 seed Bean이 없는지도 확인한다.
추가 데이터 생성 규칙 테스트는 메모리에서 분포·재현성·Enum 혼합·개수 검증만 수행한다.
이 테스트에서 1,000,000건 분포를 검사하더라도 DB에 해당 데이터를 삽입하지 않는다.

## 최초 100,000건 실측 (2026-09-08)

- MySQL 8.4.11의 빈 테이블에 100,000건 삽입 완료. 전체 1,000,000건 삽입은 실행하지 않았다.
- 생성기 로그 기준 삽입·commit 포함 1,859ms. Spring Boot 기동 시간은 별도이며,
  이 수치는 해당 로컬 환경의 1회 측정으로 성능 보장이나 조회 성능 측정값이 아니다.
- 총 행 수와 `COUNT(DISTINCT name)` 모두 100,000. 지역 분포는 위 표와 정확히 일치했다.
- 유형별: SPORTS_CENTER 16,690 / LIBRARY 16,690 / CULTURAL_CENTER 16,660 /
  COMMUNITY_CENTER 16,660 / PARK 16,650 / OTHER 16,650.
- 상태별: OPERATING 33,520 / TEMPORARILY_CLOSED 33,340 / CLOSED 33,140.
- 각 지역에 district 10개, 각 district에 유형 6개, 각 district/type에 상태 3개 확인.
- 필수 생성 값의 NULL 및 위도/경도 범위 오류 0건.
- 같은 100,000건 명령을 재실행하면 `facility must be empty` 오류와 종료 코드 1로 중단.
- `SHOW INDEX FROM facility` 결과 PRIMARY(id) 하나만 존재.
- 생성 전 테스트의 롤백으로 AUTO_INCREMENT가 이미 증가해 최초 생성 PK는 232였다.
  생성 이름은 요청대로 Facility-1부터 시작한다. PK 초기화가 필요하면 TRUNCATE 후 재생성한다.

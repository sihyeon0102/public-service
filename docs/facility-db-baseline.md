# Facility DB Performance Baseline

## Test Environment

- 측정일: 2026-09-08 (Asia/Seoul). 첫 인덱스 변경 전 Before 진단.
- 소스 기준 커밋: `929a0db1395edba42ab5111766c040c5c47f0042`.
- 데이터: `public_service.facility` 1,000,000건, 기존 seed가 지역 순서대로 생성한 합성 데이터.
- MySQL 8.4.11 / Docker 컨테이너 `public-service-mysql` / InnoDB.
- Java 21.0.12.1 / Spring Boot 4.1.1 / Hibernate ORM 7.4.5.Final.
- 호스트 CPU: Intel Core Ultra 7 258V, 논리 프로세서 8개.
- Docker VM: CPU 8개, 메모리 16,508,375,040 bytes. 컨테이너별 Memory/NanoCpus 제한 값은 모두 0.
- InnoDB buffer pool: 134,217,728 bytes (128 MiB), page size: 16,384 bytes.
- `innodb_parallel_read_threads=4` (기존 값 확인만 수행; 각 쿼리의 실제 worker 사용 여부는 별도 측정하지 않음).
- 정보 스키마 관측치: TABLE_ROWS=987,910 (추정), DATA_LENGTH=131,776,512 bytes, INDEX_LENGTH=0.
  INDEX_LENGTH=0은 보조 인덱스가 없다는 뜻이며 PRIMARY가 없다는 뜻은 아니다.
- 인덱스: **PRIMARY(id), UNIQUE, BTREE 하나뿐**. 변경 전후 SHOW INDEX로 확인.
- 검색: `JpaSpecificationExecutor.findAll(specification, pageable)`.
  전달된 region/district/type만 equality predicate로 만들고 AND로 결합한다.
- 정렬/페이징: `ORDER BY id ASC LIMIT 0,20`, page=0, size=20.
- Redis/애플리케이션 캐시는 없다. DB 버퍼 풀과 OS 캐시는 존재한다.
- 실행 중인 API 서버가 없어 기존 JAR를 임시 실행했다. 실행 인자로만
  `--spring.profiles.active=verification --spring.jpa.hibernate.ddl-auto=validate --server.port=8080 --server.address=127.0.0.1`을 지정했다.
  seed는 실행하지 않았으며 검증 후 임시 프로세스를 종료했다.

### Measurement Method

1. 실제 HTTP 요청 A~E를 각 1회 실행하여 응답과 Hibernate SQL을 확인했다.
   모두 HTTP 200, content 20건이며 totalElements는 아래 분포와 일치했다.
2. 로그의 projection, WHERE, ORDER BY, COUNT 형태를 그대로 유지하고 별칭만 f로 줄였다.
   바인딩 값은 같은 SQL 리터럴로 치환하고 offset=0, size=20을 사용했다.
   JDBC prepared execution 자체를 벤치마크한 것은 아니다.
3. MySQL CLI의 단일 연결, `START TRANSACTION READ ONLY`에서 진단했다.
   A Page → A COUNT → B Page → B COUNT → … → E COUNT 순서이며 동시 실행하지 않았다.
4. SQL마다 `EXPLAIN FORMAT=TRADITIONAL` 1회,
   `EXPLAIN ANALYZE` 워밍업 1회 + 측정 5회를 연속 실행했다. 총 ANALYZE 60회.
   모든 ANALYZE 대상은 SELECT이며 마지막에 읽기 트랜잭션을 종료했다.
5. 표의 시간은 최상위 iterator의 `actual time=a..b` 중 b(ms)이다.
   5회 중앙값과 최소~최대를 기록했다. 출력 자체가 반올림된 값이다.
   하위 iterator 시간은 상위 시간에 포함되므로 서로 더하지 않는다.
   이 해석은 [MySQL EXPLAIN 문서](https://dev.mysql.com/doc/refman/8.4/en/explain.html)에 따른다.
6. 별도 읽기 전용 세션에서 동일 일반 SELECT를 각 1회 실행하고,
   기존 활성화된 `performance_schema.events_statements_history`의 해당 세션 직전 event에서
   ROWS_EXAMINED/ROWS_SENT를 확인했다. 이 추가 실행의 시간은 5회 통계에 섞지 않았다.
   Performance Schema 설정은 바꾸지 않았다.
7. 서버 재시작, 버퍼 비우기, 통계 갱신(ANALYZE TABLE), 인덱스/쿼리/설정 변경은 하지 않았다.
   워밍업은 수행했지만 모든 페이지의 메모리 상주나 동일한 OS 캐시 상태를 보장하지 않는다.
   버퍼 풀 크기는 보고된 데이터 크기와 비슷하며, 다른 페이지도 공간을 사용한다.

**이 수치는 실행계획 진단용 DB baseline이다. EXPLAIN ANALYZE 계측 오버헤드가 포함되며,
HTTP 지연시간·처리량·p95/p99·동시 부하의 최종 성능값이 아니다.**
Page와 COUNT는 별도로 반복했으므로 두 중앙값의 합도 실제 API 지연시간으로 간주하지 않는다.

## Dataset Distribution

여기서 selectivity는 요청에서 사용한 의미인 “전체 중 일치하는 행의 비율”이다.
낮은 비율일수록 더 좁은 조건이다.

| 조건 | 실제 일치 건수 | 일치 비율 |
|---|---:|---:|
| A 전체 | 1,000,000 | 100% |
| B SEOUL | 300,000 | 30% |
| C GWANGJU | 40,000 | 4% |
| D SEOUL + LIBRARY | 50,000 | 5% |
| E SEOUL + SEOUL-DISTRICT-1 + LIBRARY | 5,000 | 0.5% |

전체 region 분포는 SEOUL 300,000 / GYEONGGI 300,000 / BUSAN 150,000 /
INCHEON 80,000 / DAEGU 80,000 / DAEJEON 50,000 / GWANGJU 40,000이다.

seed는 이 지역 순서로 삽입한다. SEOUL은 id=1~300,000, GWANGJU는 id=960,001~1,000,000이다.
따라서 region과 PK 순서 사이에 강한 상관관계가 있다.
결과 비율만으로 첫 페이지 탐색량을 예측하면 안 된다.
구체적인 seed 규칙은 [facility-seeding.md](facility-seeding.md)를 따른다.

## Test Queries

실제 Hibernate 로그에서 각 요청마다 아래 두 종류의 SQL을 모두 확인했다.

```sql
select f1_0.id,f1_0.address,f1_0.district,f1_0.latitude,f1_0.longitude,
       f1_0.name,f1_0.operating_status,f1_0.region,f1_0.type
from facility f1_0
where f1_0.region=? and f1_0.type=?
order by f1_0.id limit ?,?;

select count(f1_0.id)
from facility f1_0
where f1_0.region=? and f1_0.type=?;
```

위 예는 D이며 바인딩은 SEOUL, LIBRARY, offset 0, size 20이다.
A는 WHERE 자체가 없다. `? IS NULL OR ...`는 어느 SQL에도 없다.
모든 조건이 20건보다 많은 결과를 가지므로 이번 요청에서는 COUNT가 모두 실행됐다.
일반적으로 Spring Data의 Page 생성 과정에서 결과만으로 총건수를 알 수 있으면 COUNT를 생략할 수 있지만,
이 측정의 다섯 요청은 로그로 2개 SQL 실행을 확인했다.

실제 집계 표현은 **COUNT(id)**이다. id가 NOT NULL PK여서 COUNT(*)와 결과는 같지만,
본 측정은 쿼리를 바꾸지 않고 COUNT(id)로 수행했다.

아래는 실제 진단에 사용한 10개 SQL이다. 각각 앞에
`EXPLAIN FORMAT=TRADITIONAL` 또는 `EXPLAIN ANALYZE`를 붙여 실행했다.

### A. 조건 없음

**Page SELECT**

```sql
select f.id,f.address,f.district,f.latitude,f.longitude,f.name,f.operating_status,f.region,f.type from facility f order by f.id limit 0,20;
```

**COUNT**

```sql
select count(f.id) from facility f;
```

### B. region = SEOUL

**Page SELECT**

```sql
select f.id,f.address,f.district,f.latitude,f.longitude,f.name,f.operating_status,f.region,f.type from facility f where f.region='SEOUL' order by f.id limit 0,20;
```

**COUNT**

```sql
select count(f.id) from facility f where f.region='SEOUL';
```

### C. region = GWANGJU

**Page SELECT**

```sql
select f.id,f.address,f.district,f.latitude,f.longitude,f.name,f.operating_status,f.region,f.type from facility f where f.region='GWANGJU' order by f.id limit 0,20;
```

**COUNT**

```sql
select count(f.id) from facility f where f.region='GWANGJU';
```

### D. SEOUL + LIBRARY

**Page SELECT**

```sql
select f.id,f.address,f.district,f.latitude,f.longitude,f.name,f.operating_status,f.region,f.type from facility f where f.region='SEOUL' and f.type='LIBRARY' order by f.id limit 0,20;
```

**COUNT**

```sql
select count(f.id) from facility f where f.region='SEOUL' and f.type='LIBRARY';
```

### E. SEOUL + SEOUL-DISTRICT-1 + LIBRARY

**Page SELECT**

```sql
select f.id,f.address,f.district,f.latitude,f.longitude,f.name,f.operating_status,f.region,f.type from facility f where f.region='SEOUL' and f.district='SEOUL-DISTRICT-1' and f.type='LIBRARY' order by f.id limit 0,20;
```

**COUNT**

```sql
select count(f.id) from facility f where f.region='SEOUL' and f.district='SEOUL-DISTRICT-1' and f.type='LIBRARY';
```

## Execution Plans

### EXPLAIN summary

전체 계획의 select_type은 SIMPLE, possible_keys는 NULL이다.
Page는 모두 key=PRIMARY, key_len=8, type=index이고, 별도 filesort/temporary 표시는 없다.
이는 WHERE 검색용 인덱스 접근이 아니라 **PK 정렬 순서로 스캔**한다는 의미다.

| 쿼리 | access type | possible_keys | key | 예상 rows | filtered (%) | Extra |
|---|---|---|---|---:|---:|---|
| A_PAGE | index | NULL | PRIMARY | 20 | 100.00 | — |
| A_COUNT | index | NULL | PRIMARY | 987910 | 100.00 | Using index |
| B_PAGE | index | NULL | PRIMARY | 20 | 10.00 | Using where |
| B_COUNT | ALL | NULL | NULL | 987910 | 10.00 | Using where |
| C_PAGE | index | NULL | PRIMARY | 20 | 10.00 | Using where |
| C_COUNT | ALL | NULL | NULL | 987910 | 10.00 | Using where |
| D_PAGE | index | NULL | PRIMARY | 20 | 1.67 | Using where |
| D_COUNT | ALL | NULL | NULL | 987910 | 1.67 | Using where |
| E_PAGE | index | NULL | PRIMARY | 20 | 0.25 | Using where |
| E_COUNT | ALL | NULL | NULL | 987910 | 0.17 | Using where |

EXPLAIN의 rows/filtered는 예상치이며 실제 처리량이 아니다.
특히 C_PAGE는 rows=20으로 보여도 실제 960,020행을 스캔했다.
E는 Page/COUNT의 filtered 예상치도 다르다. 값을 임의로 맞추지 않고 그대로 기록했다.
조회 결과 비율은 위 Dataset Distribution의 실측값을 사용해야 한다.

### Timing and actual rows

단위는 ms. “scan 행”은 ANALYZE의 가장 아래 scan iterator가 상위로 전달한 행 수이다.
현재 loops=1이므로 반복 횟수 보정은 필요하지 않다.
물리 디스크 읽기 횟수·MVCC 버전 검사 횟수와 동일한 지표는 아니다.

| 쿼리 | 워밍업 | 측정 5회 | 중앙값 | 범위 | 일반 SELECT ROWS_EXAMINED |
|---|---:|---|---:|---|---:|
| A_PAGE | 2.87 | 0.0222, 0.00921, 0.00808, 0.00734, 0.00782 | 0.00808 | 0.00734~0.0222 | 20 |
| A_COUNT | 15.6 | 14.9, 14.7, 14.4, 14.8, 13.9 | 14.7 | 13.9~14.9 | 0 |
| B_PAGE | 0.0504 | 0.0135, 0.00934, 0.0156, 0.0108, 0.0149 | 0.0135 | 0.00934~0.0156 | 20 |
| B_COUNT | 144 | 142, 145, 142, 150, 148 | 145 | 142~150 | 1000000 |
| C_PAGE | 200 | 230, 199, 211, 210, 200 | 210 | 199~230 | 960020 |
| C_COUNT | 125 | 134, 133, 128, 133, 133 | 133 | 128~134 | 1000000 |
| D_PAGE | 0.036 | 0.024, 0.0239, 0.0303, 0.0274, 0.0253 | 0.0253 | 0.0239~0.0303 | 80 |
| D_COUNT | 149 | 158, 151, 146, 156, 162 | 156 | 146~162 | 1000000 |
| E_PAGE | 0.273 | 0.296, 0.303, 0.289, 0.313, 0.323 | 0.303 | 0.289~0.323 | 1151 |
| E_COUNT | 158 | 157, 157, 155, 154, 156 | 156 | 154~157 | 1000000 |

| 조건 | Page scan 행 → 반환 | COUNT scan 행 → 일치 → 집계 반환 | 더 비싼 쪽 |
|---|---|---|---|
| A | 20 → 20 | 내부 Count rows 경로, scan 행 미노출 → 전체 1,000,000건 집계 → 1 | COUNT |
| B | 20 → 20 | 1,000,000 → 300,000 → 1 | COUNT |
| C | 960,020 → 20 | 1,000,000 → 40,000 → 1 | Page SELECT |
| D | 80 → 20 | 1,000,000 → 50,000 → 1 | COUNT |
| E | 1,151 → 20 | 1,000,000 → 5,000 → 1 | COUNT |

COUNT의 최상위 rows=1은 **집계 결과 한 행**이라는 뜻이지 한 행만 검사했다는 뜻이 아니다.
A_COUNT는 ANALYZE가 `Count rows in f` 한 노드만 출력하고,
일반 실행의 ROWS_EXAMINED도 0으로 보고됐다. 내부 엔진의 실제 index entry 검사량은
이 두 출력에서 확인할 수 없다. 이를 “아무 행도 읽지 않는다” 또는 O(1) 메타데이터 조회라고 결론 내리지 않는다.
전통 EXPLAIN은 PRIMARY의 index scan을 표시했다.

InnoDB는 정확한 총건수를 항상 저장해 두고 반환하는 방식이 아니며, 조건 없는 COUNT에 별도 처리가 있다.
보조 인덱스가 없을 때의 COUNT(*)는 clustered index를 사용한다.
[MySQL COUNT 설명](https://dev.mysql.com/doc/refman/8.4/en/aggregate-functions.html).
본 관측의 A_COUNT와 B~E_COUNT의 속도 차이는 이런 별도 실행 경로와 부합하지만,
병렬 worker 기여나 정확한 물리 읽기량까지 분리 측정한 것은 아니다.

### Captured EXPLAIN ANALYZE trees

아래는 중앙값을 재구성한 계획이 아니라 **워밍업 다음 첫 측정(RUN1)의 실제 출력**이다.
표의 5회 중앙값과 시간이 다를 수 있다.

#### A. 조건 없음

**Page SELECT**

```text
-> Limit: 20 row(s)  (cost=0.0407 rows=20) (actual time=0.0155..0.0222 rows=20 loops=1)
    -> Index scan on f using PRIMARY  (cost=0.0407 rows=20) (actual time=0.0146..0.0205 rows=20 loops=1)
```

**COUNT**

```text
-> Count rows in f  (actual time=14.9..14.9 rows=1 loops=1)
```

#### B. region = SEOUL

**Page SELECT**

```text
-> Limit: 20 row(s)  (cost=1.84 rows=2) (actual time=0.0064..0.0135 rows=20 loops=1)
    -> Filter: (f.region = 'SEOUL')  (cost=1.84 rows=2) (actual time=0.00608..0.0123 rows=20 loops=1)
        -> Index scan on f using PRIMARY  (cost=1.84 rows=20) (actual time=0.00547..0.0102 rows=20 loops=1)
```

**COUNT**

```text
-> Aggregate: count(f.id)  (cost=110681 rows=1) (actual time=142..142 rows=1 loops=1)
    -> Filter: (f.region = 'SEOUL')  (cost=100802 rows=98791) (actual time=0.0121..134 rows=300000 loops=1)
        -> Table scan on f  (cost=100802 rows=987910) (actual time=0.0113..91.3 rows=1e+6 loops=1)
```

#### C. region = GWANGJU

**Page SELECT**

```text
-> Limit: 20 row(s)  (cost=1.84 rows=2) (actual time=230..230 rows=20 loops=1)
    -> Filter: (f.region = 'GWANGJU')  (cost=1.84 rows=2) (actual time=230..230 rows=20 loops=1)
        -> Index scan on f using PRIMARY  (cost=1.84 rows=20) (actual time=0.0119..192 rows=960020 loops=1)
```

**COUNT**

```text
-> Aggregate: count(f.id)  (cost=110681 rows=1) (actual time=134..134 rows=1 loops=1)
    -> Filter: (f.region = 'GWANGJU')  (cost=100802 rows=98791) (actual time=126..133 rows=40000 loops=1)
        -> Table scan on f  (cost=100802 rows=987910) (actual time=0.0106..96.1 rows=1e+6 loops=1)
```

#### D. SEOUL + LIBRARY

**Page SELECT**

```text
-> Limit: 20 row(s)  (cost=2.01 rows=0.333) (actual time=0.00706..0.024 rows=20 loops=1)
    -> Filter: ((f.`type` = 'LIBRARY') and (f.region = 'SEOUL'))  (cost=2.01 rows=0.333) (actual time=0.00681..0.0229 rows=20 loops=1)
        -> Index scan on f using PRIMARY  (cost=2.01 rows=20) (actual time=0.00343..0.0178 rows=80 loops=1)
```

**COUNT**

```text
-> Aggregate: count(f.id)  (cost=102448 rows=1) (actual time=158..158 rows=1 loops=1)
    -> Filter: ((f.`type` = 'LIBRARY') and (f.region = 'SEOUL'))  (cost=100802 rows=16465) (actual time=0.0163..156 rows=50000 loops=1)
        -> Table scan on f  (cost=100802 rows=987910) (actual time=0.0128..107 rows=1e+6 loops=1)
```

#### E. SEOUL + SEOUL-DISTRICT-1 + LIBRARY

**Page SELECT**

```text
-> Limit: 20 row(s)  (cost=3.06 rows=0.05) (actual time=0.00971..0.296 rows=20 loops=1)
    -> Filter: ((f.`type` = 'LIBRARY') and (f.district = 'SEOUL-DISTRICT-1') and (f.region = 'SEOUL'))  (cost=3.06 rows=0.05) (actual time=0.00946..0.294 rows=20 loops=1)
        -> Index scan on f using PRIMARY  (cost=3.06 rows=20) (actual time=0.00495..0.229 rows=1151 loops=1)
```

**COUNT**

```text
-> Aggregate: count(f.id)  (cost=100966 rows=1) (actual time=157..157 rows=1 loops=1)
    -> Filter: ((f.`type` = 'LIBRARY') and (f.district = 'SEOUL-DISTRICT-1') and (f.region = 'SEOUL'))  (cost=100802 rows=1647) (actual time=0.0196..157 rows=5000 loops=1)
        -> Table scan on f  (cost=100802 rows=987910) (actual time=0.0165..109 rows=1e+6 loops=1)
```

## Findings

### Full Table Scan and Index Scan

- B/C/D/E COUNT는 모두 type=ALL, key=NULL, Table scan이다.
  실제 1,000,000행을 검사한 후 조건에 맞는 행을 집계한다.
- A COUNT는 type=index, PRIMARY를 이용한 조건 없는 내부 Count rows 경로다.
  조건부 COUNT의 full table scan과 구분해야 한다.
- Page A~E는 type=index, PRIMARY scan이다. 별도 정렬은 없고,
  조건에 맞는 20개를 찾으면 LIMIT에서 중단한다. “추가 인덱스가 없다”가 “모든 쿼리는 type=ALL”을 뜻하지 않는다.
- 이번 10개 계획에는 filesort/Sort 노드가 없다. 현재 병목 후보는 별도 정렬보다 **불필요하게 읽고 검사하는 행 수**다.

### SELECT와 COUNT 비용 분리

- B/D/E의 첫 페이지는 PK 앞쪽에서 20건을 빨리 찾지만 COUNT는 전체 테이블을 읽는다.
  예를 들어 E의 일치 비율은 0.5%인데 COUNT 중앙값은 156ms로 B의 145ms보다 작지 않았다.
  인덱스 없이 결과가 적어진다는 사실만으로 검사할 행 수는 줄지 않는다.
- C는 Page 210ms(199~230), COUNT 133ms(128~134)로 Page가 더 비싸다.
  GWANGJU 앞의 다른 지역 960,000행을 지나서 20건을 찾기 때문이다.
  선택도가 더 높은 region이 무조건 빠르다는 가정의 반례다.
- B는 Page 0.0135ms지만 이는 SEOUL이 PK 앞부분에 집중된 첫 페이지라는 현재 데이터 배치의 효과다.
  효율적인 region 인덱스 탐색이 구현됐다는 증거가 아니다.
- A는 Page 0.00808ms 대비 COUNT 14.7ms다. 조건이 없어도 총건수 계산은 별도 비용이다.
- 가장 유력한 공통 병목은 **Page 응답마다 발생하는 조건부 COUNT의 100만 행 스캔**이다.
  동시에 **PK 뒤쪽 지역의 Page 스캔**이 독립적인 병목 후보로 확인됐다.
- 깊은 OFFSET은 이번 측정 범위가 아니다. 첫 페이지의 결과를 모든 페이지로 일반화하지 않는다.

### 추정치와 실제 분포

- 전체 예상 rows=987,910은 실제 1,000,000과 다르며 통계 기반 추정치다.
- B/C COUNT의 filtered는 둘 다 10%로 추정됐지만 실제 일치 비율은 30%와 4%다.
- D COUNT는 예상 일치 rows 약 16,465 대비 실제 50,000,
  E COUNT는 약 1,647 대비 실제 5,000이었다.
- C_PAGE의 rows=20 예상과 실제 960,020 scan은 PK/region 상관관계와 LIMIT 위치를
  전통 EXPLAIN의 예상치만으로 판단하면 안 되는 이유를 보여준다.
- 통계 갱신이나 histogram 생성도 Before 상태를 바꿀 수 있어 수행하지 않았다.

## Next Hypothesis

**아래는 다음 실험의 후보이며 실제 인덱스를 생성하거나 SQL을 변경하지 않았다.**

| 후보 | 검증할 가설 | 비교 시 주의점 |
|---|---|---|
| (region) | B/C의 region 범위 탐색과 COUNT의 전체 스캔 감소 | 30%와 4%에서 실제 검사량·시간 차이, ORDER BY id 유지 여부를 확인 |
| (region, type) | D의 5% 범위를 좁히고 조건부 COUNT를 인덱스 중심으로 처리할 가능성 | region만 지정한 B/C는 type이 정렬 사이에 있어 ORDER BY id를 공짜로 만족한다고 가정하지 않음 |
| (region, district, type) | E의 0.5% 범위를 좁히고 100만 행 검사 감소 | district가 없는 D에서 region+type 범위를 같은 효율로 좁힌다고 가정하지 않음 |

복합 인덱스는 선두 컬럼 조합과 조건 형태가 중요하다.
[MySQL 복합 인덱스 설명](https://dev.mysql.com/doc/refman/8.4/en/multiple-column-indexes.html).
결과 비율이 낮으면 적절한 인덱스로 검사 범위를 크게 줄일 가능성이 있지만,
실제 이득은 인덱스 순서, 정렬, 커버링 여부, 데이터 분포와 옵티마이저 선택에 달려 있다.

Page는 Facility의 모든 컬럼을 반환하므로 위 후보만으로 완전한 covering SELECT가 된다고 가정하지 않는다.
COUNT와 Page의 key, scan 행, sort, 시간 변화를 따로 비교해야 한다.
모든 후보를 한 번에 넣지 말고 향후 별도 승인된 실험에서 후보별 효과와 인덱스 저장공간·쓰기 비용을 비교한다.
현재는 WHERE 변경, COUNT 제거, Slice 전환, keyset pagination, Redis/Cache 도입도 하지 않았다.

## Verification and Artifacts

- DB 건수와 PRIMARY(id)만 존재하는 것을 측정 전후 확인했다.
- INSERT/UPDATE/DELETE/TRUNCATE, CREATE INDEX/ALTER TABLE, 통계/설정 변경을 실행하지 않았다.
- Java, Repository, Entity, application.properties, compose.yml, build.gradle을 수정하지 않았다.
- API 검증/DB 진단만 수행했으며 테스트 재실행으로 추가 데이터 쓰기를 발생시키지 않았다.
- 커밋 대상은 이 문서 `docs/facility-db-baseline.md` 하나다.
- 원본 로그는 기존 Git 제외 경로에 남겼다:
  `build/facility-baseline-api.log`,
  `build/facility-db-baseline-plans.log`,
  `build/facility-db-baseline-summary.json`,
  `build/facility-db-baseline-examined.log`.
  build를 지우면 사라지므로 비교에 필요한 SQL·계획·5회 시간·카운터는 본문에 보존했다.

다음 단계의 부하테스트에서는 동일 데이터셋·요청 분포·워밍업·동시성 조건을 고정하고,
DB 진단 시간과 HTTP 응답시간/처리량을 분리하여 측정한다.

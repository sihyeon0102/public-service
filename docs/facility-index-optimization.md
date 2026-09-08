# Facility Index Optimization Experiment

## Purpose and controls

2026-09-08 측정, 2026-09-09 결과 정리 및 최종 검증. 기존 [DB Before](facility-db-baseline.md) 및 [HTTP Before](facility-http-baseline.md)와 비교하는 첫 인덱스 실험이다.
출발 commit은 `03e900ef2a199f86b0469ddbaec3af7dbf225255`이며 working tree는 clean이었다.
실험에서 변경한 서비스 변수는 아래 세 보조 인덱스뿐이다.

- Windows host의 기존 Java 21.0.12.1 / Spring Boot 4.1.1 프로세스를 재시작하지 않았다.
- Docker MySQL 8.4.11, 같은 `public-service-mysql` 컨테이너와 데이터 볼륨을 사용했다.
- `public_service.facility` 1,000,000건, 기존 지역/유형/PK 분포를 유지했다.
- Hikari max=10, InnoDB buffer pool=128 MiB, Prometheus scrape=15초를 유지했다.
- Prometheus/Grafana 설정, API, Repository, Pageable, SQL, Entity, Redis/Cache 관련 변경이 없다.
- k6 `grafana/k6:2.1.0`, 기존 [스크립트](../loadtest/facility-baseline.js)를 그대로 사용했다.
  SHA-256: `CE05E86B18542FA03226F8E36A51E511D2FBD33328EC5327E73291089C9DDB00`.
- 애플리케이션은 기존 `--spring.jpa.hibernate.ddl-auto=validate` 실행 상태다. 인덱스를 Entity annotation이나 자동 migration으로 추가하지 않았다.

## Index selection

| HTTP 조건 | 일치 건수 | 후보 | equality 뒤의 정렬 순서 |
|---|---:|---|---|
| A: SEOUL + LIBRARY | 50,000 (5%) | `(region, type)` | id |
| B: GWANGJU | 40,000 (4%) | `(region)` | id |
| C: SEOUL + SEOUL-DISTRICT-1 + LIBRARY | 5,000 (0.5%) | `(region, district, type)` | id |

InnoDB 보조 인덱스에는 PK 열이 내부적으로 포함된다. 따라서 이 테이블에서 위 인덱스의 키 순서는
개념적으로 각각 `(region,type,id)`, `(region,id)`, `(region,district,type,id)`다.
명시한 모든 검색 열이 equality로 고정되면 해당 범위의 남은 순서는 id이므로 `ORDER BY id LIMIT 0,20`을
별도 정렬 없이 처리할 수 있다는 가설이다. id를 정의에 다시 쓰거나 9개 응답 열 전체를 covering index로 늘리지 않았다.
[MySQL Index Extensions](https://dev.mysql.com/doc/refman/8.4/en/index-extensions.html)

leftmost prefix는 검색 가능 범위와 관련된다. `(region,type)`도 region 검색에는 사용할 수 있지만,
type이 고정되지 않은 B에서는 type별 그룹을 가로지른 전역 id 순서를 바로 제공하지 않는다.
마찬가지로 `(region,district,type)`는 A에서 district가 비어 있어 A 전용 인덱스의 정렬/탐색을 그대로 대체하지 못한다.
따라서 region 접두어가 겹친다는 이유만으로 세 후보를 모두 중복이라고 판단할 수 없다.
[MySQL Multiple-Column Indexes](https://dev.mysql.com/doc/refman/8.4/en/multiple-column-indexes.html),
[ORDER BY Optimization](https://dev.mysql.com/doc/refman/8.4/en/order-by-optimization.html)

COUNT는 검색 열과 NOT NULL PK id만 필요하므로 보조 인덱스만으로 계산할 수 있다.
Page SELECT는 주소/이름/좌표 등 나머지 열을 위해 clustered row 접근이 필요하다.
COUNT도 결과 범위 내 항목은 전부 읽어야 하며 O(1) 집계로 바뀌는 것은 아니다.

## Application and SQL

[실험 SQL](../sql/facility-search-indexes.sql)을 B → A → C 순서로 한 문장씩 적용하고,
각 단계에서 해당 대상의 Page/COUNT를 진단했다. 모든 인덱스 적용 후 여섯 쿼리를 다시 진단했다.
중간에 FORCE INDEX, invisible index, ANALYZE TABLE, 캐시 비우기, 서버 설정 변경은 수행하지 않았다.
CREATE INDEX 과정의 통계 수집과 버퍼 상태 변화는 인덱스 생성에 수반되는 효과다.

SQL 파일은 **수동 일회성 실험용**이다. 현재 DB에는 이미 적용되어 있으므로 다시 실행하지 않는다.
새 실험 DB에서는 `SELECT DATABASE()`, `COUNT(*)`, `SHOW INDEX`로 대상을 확인한 후 MySQL client에서
`SOURCE`로 해당 파일을 실행하거나 문장을 순서대로 실행할 수 있다. 자동 초기화/삭제 구문은 포함하지 않았다.

Page/COUNT는 기존 Hibernate SQL의 projection, predicate, 정렬 및 LIMIT를 유지하고
진단 시 바인드 값만 동일한 리터럴로 치환했다.

```sql
SELECT f.id,f.address,f.district,f.latitude,f.longitude,f.name,
       f.operating_status,f.region,f.type
FROM facility f
WHERE /* 아래 A/B/C 조건 */
ORDER BY f.id LIMIT 0,20;

SELECT COUNT(f.id) FROM facility f WHERE /* 동일 조건 */;

-- A: f.region='SEOUL' AND f.type='LIBRARY'
-- B: f.region='GWANGJU'
-- C: f.region='SEOUL' AND f.district='SEOUL-DISTRICT-1' AND f.type='LIBRARY'
```

## DB execution plans

각 SQL은 traditional EXPLAIN 1회, EXPLAIN ANALYZE 워밍업 1회 + 측정 5회다.
시간은 최상위 iterator의 종료 시간(ms)의 중앙값과 범위다. 하위 iterator 시간과 더하지 않았다.
별도 동일 SELECT 직후 기존 `performance_schema.events_statements_history`에서 해당 세션의
`ROWS_EXAMINED`/`ROWS_SENT`를 확인했다. 계측 설정은 변경하지 않았다.
이 값은 statement 수준 검사 행 수이며 물리 I/O 페이지 수나 B-tree 탐색 횟수가 아니다.
ANALYZE에는 계측 오버헤드가 있고 HTTP 동시 부하 측정과도 다르다.

### Stepwise result

| 적용 직후 | 대상 | 실제 Page / COUNT key | Page 중앙값 ms | COUNT 중앙값 ms | 검사 행 Page / COUNT |
|---|---|---|---:|---:|---:|
| `(region)` | B | idx_facility_region | 0.115 | 5.22 | 20 / 40,000 |
| + `(region,type)` | A | idx_facility_region_type | 0.0561 | 10.7 | 20 / 50,000 |
| + `(region,district,type)` | C | idx_facility_region_district_type | 0.116 | 1.36 | 20 / 5,000 |

### Final plans, all three indexes present

| 대상 | 쿼리 | actual key | access | 예상 rows | filtered | 실제 검사 행 | Extra |
|---|---|---|---|---:|---:|---:|---|
| A | Page | idx_facility_region_type | ref | 100,346 | 100% | 20 | Using index condition |
| A | COUNT | idx_facility_region_type | ref | 100,346 | 100% | 50,000 | Using where; Using index |
| B | Page | idx_facility_region | ref | 74,656 | 100% | 20 | 없음 |
| B | COUNT | idx_facility_region | ref | 74,656 | 100% | 40,000 | Using index |
| C | Page | idx_facility_region_district_type | ref | 9,154 | 100% | 20 | Using index condition |
| C | COUNT | idx_facility_region_district_type | ref | 9,154 | 100% | 5,000 | Using where; Using index |

최종 `possible_keys`에는 세 보조 인덱스가 모두 나타났으나 위와 같이 대상별 적합한 인덱스를 선택했다.
예상 rows는 실제 COUNT와 다르므로 실행 행 수로 오인하면 안 된다.
모든 Page tree는 `Limit: 20 → Index lookup`, COUNT는 `Aggregate → [Filter] → Covering index lookup`이다.
A/C의 type 검사 Filter는 남지만 lookup key에 type도 포함되어 검사 범위는 이미 좁혀졌다.
여섯 쿼리 모두 full table scan, 전체 index scan, filesort/Sort iterator가 없다.
Page는 20행에서 중단하고 COUNT는 해당 범위를 끝까지 읽어 1행의 집계 결과를 반환했다.

| 대상 | 쿼리 | Before 검사 행 → After | Before 중앙값 ms | After 중앙값 ms (범위) |
|---|---|---:|---:|---:|
| A | Page | 80 → 20 | 0.0253 | 0.0594 (0.0536–0.0902) |
| A | COUNT | 1,000,000 → 50,000 | 156 | 11.1 (10.2–20.4) |
| B | Page | 960,020 → 20 | 210 | 0.0486 (0.0444–0.0504) |
| B | COUNT | 1,000,000 → 40,000 | 133 | 5.52 (5.33–5.75) |
| C | Page | 1,151 → 20 | 0.303 | 0.0739 (0.0716–0.0923) |
| C | COUNT | 1,000,000 → 5,000 | 156 | 1.37 (1.33–1.64) |

A의 Page는 원래 PK 앞부분 80행만 읽어 매우 짧았다. 이번 중앙값은 오히려 증가했다.
작은 PK scan과 보조 인덱스 조회 후 clustered row 접근의 차이, 캐시/계측 변동이 있는 구간이므로
모든 SQL이 빨라졌다고 주장하지 않는다. A의 개선 근거는 주로 COUNT의 100만 행 full scan 제거다.
B는 데이터의 id 뒤쪽에 있던 GWANGJU를 바로 찾게 되어 Page scan 자체도 크게 감소했다.
C는 COUNT 범위가 0.5%로 좁아져 가장 적은 집계 항목을 읽는다.

## HTTP experiment method

Before와 같은 스크립트, Docker 이미지, 대상별 독립 실행을 사용했다.
A/B/C smoke(각 1 VU, 10초) 모두 성공한 후 A/B/C warm-up을 실행하고 A/B/C baseline을 순차 실행했다.
warm-up은 1 VU 시작 → 5초에 3 VU → 20초 유지 → 5초에 0 VU이며 별도 결과다.
baseline은 5/10/20/40 VU 각각 10초 상승 + 40초 유지 + 5초 하강, 총 220초다.
think time은 매 반복 0.2초다. 응답 정상성 threshold만 사용하고 임의의 latency SLO는 없다.

단계 RPS는 Before와 동일하게 **해당 단계 요청 수 / 55초**로 계산한다.
상승/하강을 포함하므로 40 VU 고정 유지 구간만의 RPS라고 해석하지 않는다.
custom Trend도 해당 단계 전체를 포함한다. 전체 http_reqs에는 setup 검증 요청 1개가 포함된다.
graceful 종료로 경계의 일부 요청이 명목 단계 밖에서 끝날 수 있다.
외부 wrapper가 A/B의 Docker 실행 시작/종료 UTC를 별도 기록했다. 시나리오 시작과는 컨테이너/setup 준비 시간만큼 차이가 있다.
C는 작업 중단 중에도 k6가 완료되어 전체 summary 파일이 남았다. wrapper 시간 파일은 작성되지 않아
Docker 컨테이너 `festive_bell`의 start/die 이벤트로 시간을 복원했다. Docker exit code=0,
k6 실행시간 220.119초, 40 VU 단계와 모든 threshold 성공을 확인했다. C도 재실행하지 않았다.
복원 근거와 나노초 timestamp를 결과 JSON의 `endpoints.C.server.timing`에 보존했다.

CPU/버퍼/JVM의 정확히 같은 내부 상태는 보장하지 않는다. 기존 warm-up과 실행 순서를 재사용하고
캐시를 강제로 비우거나 재시작하지 않았다. 동일 PC에서 순차 실행한 단일 Before/After 표본이며
전체 반복 실험의 신뢰구간이나 최대 처리 용량을 측정한 것은 아니다.
결과 확인에 사용한 실행 사이의 대기 시간은 완전히 같지 않다. 이를 포함한 시작/종료 UTC는 결과 JSON에 보존했다.

재실행은 프로젝트 루트의 PowerShell에서 다음과 같이 수행한다. `build/k6`는 raw 결과용이며 Git에서 제외된다.
먼저 MODE=smoke로 A/B/C를 모두 검증하고, MODE=warmup으로 A/B/C를 실행한 뒤 MODE=baseline으로 A/B/C를 실행한다.
각 실행이 성공한 경우에만 다음 대상으로 넘어간다. 재실행 결과가 이번 기록을 덮지 않도록 별도 파일명을 사용한다.

```powershell
New-Item -ItemType Directory -Force build/k6 | Out-Null
docker run --rm -i `
  -e TARGET=A -e MODE=baseline `
  -e BASE_URL=http://host.docker.internal:8080 `
  -e SUMMARY_PATH=/results/repeat-baseline-A.json `
  -v "${PWD}/loadtest:/scripts:ro" -v "${PWD}/build/k6:/results" `
  grafana/k6:2.1.0 run --quiet /scripts/facility-baseline.js
```

## HTTP results

40 VU **단계 전체** 비교. 지연시간 단위는 ms이며 RPS는 단계 요청 수/55초다.

| 대상 | RPS Before → After | avg Before → After | p95 Before → After | p99 Before → After | HTTP 실패율 |
|---|---:|---:|---:|---:|---:|
| A | 30.29 → 121.18 | 947.48 → 83.40 | 1,271.96 → 259.59 | 1,509.09 → 403.49 | 0% → 0% |
| B | 12.11 → 140.89 | 2,723.29 → 44.08 | 3,741.50 → 132.77 | 4,085.89 → 217.04 | 0% → 0% |
| C | 24.47 → 161.84 | 1,219.58 → 12.82 | 1,928.68 → 19.28 | 2,118.09 → 31.40 | 0% → 0% |

RPS는 각각 약 4.00 / 11.64 / 6.61배다. 동일 VU의 closed-loop 테스트에서 응답이 빨라지면
각 VU가 더 자주 반복하므로 After의 총 요청량도 늘어난다. 동일한 고정 도착률 실험은 아니다.

After 단계별 결과:

| 대상 | VU | RPS | avg ms | p95 ms | p99 ms |
|---|---:|---:|---:|---:|---:|
| A | 5 | 19.73 | 16.17 | 19.78 | 24.35 |
| A | 10 | 39.67 | 16.29 | 20.07 | 23.05 |
| A | 20 | 75.05 | 28.96 | 98.38 | 205.52 |
| A | 40 | 121.18 | 83.40 | 259.59 | 403.49 |
| B | 5 | 19.53 | 18.27 | 27.37 | 54.99 |
| B | 10 | 38.93 | 20.22 | 41.77 | 60.51 |
| B | 20 | 74.64 | 30.07 | 86.18 | 233.06 |
| B | 40 | 140.89 | 44.08 | 132.77 | 217.04 |
| C | 5 | 19.87 | 14.55 | 22.64 | 37.68 |
| C | 10 | 40.07 | 13.94 | 24.56 | 35.65 |
| C | 20 | 79.40 | 16.49 | 31.24 | 69.62 |
| C | 40 | 161.84 | 12.82 | 19.28 | 31.40 |

모든 단계의 HTTP 실패율과 응답 check 실패율은 0%다.

| 대상 | 전체 requests | 전체 RPS | iterations | iterations/sec | 측정 UTC |
|---|---:|---:|---:|---:|---|
| A | 14,061 | 63.88 | 14,060 | 63.88 | 11:36:43.975–11:40:24.787 |
| B | 15,070 | 68.47 | 15,069 | 68.46 | 11:41:20.987–11:45:01.959 |
| C | 16,566 | 75.26 | 16,565 | 75.25 | 11:45:29.463–11:49:09.986 |

위 시각은 모두 2026-09-08 UTC이며 한국 시각은 +9시간이다. 전체·단계별 median/p90/p95/p99/max와
요청 수, smoke/warm-up 검사 결과는 [After JSON](../loadtest/results/facility-index-after-summary.json)에 있다.

## Server metrics and interpretation

기존 Grafana datasource health는 OK이며 10개 dashboard 패널이 그대로 있다. Prometheus target도 UP이다.
조건별 수치는 같은 Prometheus 데이터에서 읽었다. A/B/C 모두 `uri="/api/facilities"`로 집계되므로
HTTP query parameter에 따른 서버 metric label 분리는 없으며 **실행 시간창**으로 구분했다.

```promql
# Before 진단과 같은 짧은 창. 15초 scrape 기준 표본이 적어 변동성이 크다.
sum(rate(http_server_requests_seconds_count{job="public-service",application="public-service",uri="/api/facilities"}[30s]))
histogram_quantile(0.95, sum by(le)(rate(http_server_requests_seconds_bucket{job="public-service",application="public-service",uri="/api/facilities"}[30s])))

# 서버 gauges (application="public-service"도 결과 JSON의 실제 쿼리에 포함)
hikaricp_connections_active{job="public-service"}
hikaricp_connections_idle{job="public-service"}
hikaricp_connections_pending{job="public-service"}
hikaricp_connections_max{job="public-service"}
process_cpu_usage{job="public-service"}
system_cpu_usage{job="public-service"}
sum(jvm_memory_used_bytes{job="public-service",area="heap"})
jvm_threads_live_threads{job="public-service"}
```

전체 실행 요약은 시작+45초부터 종료까지 15초 간격 12개 표본이다.
단계별 비교는 각 단계의 명목 시작+10~50초에 15초 간격으로 얻은 3개 표본이다.
컨테이너/setup 준비 시간과 scrape 시점 차이로 경계 표본이 이전 부하 수준을 반영할 수 있다.
pending 최대값은 이 **표본에서의 최대**이며 요청별 대기시간이나 순간 전역 최대를 측정한 것이 아니다.
0 표본도 실행 내내 대기가 한 번도 없었다는 증거는 아니다.

Grafana의 기존 p95 패널은 위 histogram 식의 `[5m]` 버전이다. 긴 창에는 이전 대상과 warm-up이 섞인다.
결과 JSON에는 이를 `grafanaP95`로 별도 저장한다. A/B/C별 k6 p95와 동등한 백분위수로 비교하지 않는다.
서버 histogram은 bucket 보간과 rate 창에 기반하고 k6는 클라이언트의 단계별 요청 지연 분포다.
HTTP error percentage는 4xx/5xx가 없는 경우 트래픽이 있는 구간에서 분자를 0으로 처리했고,
k6 HTTP 실패율과 응답 본문 checks도 함께 확인했다.

40 VU 구간의 3개 표본 비교:

| 대상 | active 최대 Before → After | pending 최대 Before → After | pending 평균 Before → After | Windows CPU 평균 Before → After | JVM CPU 평균 Before → After |
|---|---:|---:|---:|---:|---:|
| A | 10 → 10 | 23 → 2 | 15.00 → 0.67 | 96.02% → 84.85% | 1.75% → 4.35% |
| B | 10 → 10 | 28 → 13 | 17.67 → 4.33 | 98.17% → 86.33% | 0.56% → 4.90% |
| C | 10 → 1 | 28 → 0 | 21.67 → 0 | 96.15% → 60.12% | 1.16% → 4.79% |

After Hikari max는 항상 10이다. 40 VU idle 최솟값은 A/B 0, C 9였다.
전체 12개 표본에서 C active 최대는 2이며, 위 표의 40 VU 구간 최대 1과 구분한다.
Windows system CPU는 MySQL 전용 CPU가 아니다. Docker/IDE/다른 host 작업을 포함한다.
After는 요청 수가 훨씬 많아 JVM process CPU가 늘었지만 host CPU 평균은 낮아졌다.
CPU 원인을 프로파일링하거나 컨테이너별로 분리 측정한 것은 아니다.

전체 12개 표본에서 heap used 범위(MB, 1 MB=1,000,000 bytes):

| 대상 | Before MB | After MB | After threads 최대 |
|---|---:|---:|---:|
| A | 39.51–172.99 | 47.09–114.67 | 46 |
| B | 42.86–97.39 | 43.33–106.24 | 49 |
| C | 46.69–113.51 | 39.56–115.11 | 49 |

heap은 GC와 기존 JVM 상태의 영향을 받으므로 이를 인덱스의 메모리 절감 효과라고 단정하지 않는다.

| 대상 실행창 | 진단용 30초 histogram p95 표본 최대 Before → After, ms | 기존 Grafana 5분 p95 After 범위, ms |
|---|---:|---:|
| A | 1,377.49 → 175.45 | 16.81–122.04 |
| B | 3,467.79 → 177.75 | 147.81–165.98 |
| C | 2,092.79 → 32.36 | 76.63–96.09 |

5분 범위는 해당 실행창에 관측한 대시보드 값이며 해당 endpoint만의 분포가 아니다.
짧은 표본 창의 최대값 역시 k6 단계 전체 p95를 대체하지 않는다.

## Findings and remaining bottleneck

Before의 공통 병목 후보였던 100만 행 COUNT full scan이 모두 covering index lookup으로 바뀌었다.
B는 추가로 PK 뒤쪽의 첫 20건을 찾던 960,020행 scan이 사라졌다.
이 두 변화는 B의 큰 지연 감소, 모든 대상의 RPS 증가 및 Hikari 대기 감소와 일관된다.
풀 크기를 그대로 유지했으므로 풀 확대가 개선 원인은 아니다.

하지만 A/B는 40 VU에서 여전히 active=10 및 pending을 관측했다. COUNT가 매 요청마다
50,000/40,000개 인덱스 항목을 읽는 비용이 남아 있고, 부하 증가 시 CPU 및 connection 점유가 늘 수 있다.
현재 남은 후보는 이 반복 집계 비용과 공유 로컬 자원 경쟁이다. 대기 감소를 확인했을 뿐
MySQL CPU, 애플리케이션 처리, 로깅, 네트워크 비용의 기여도를 완전히 분리한 것은 아니다.

C는 5,000개 COUNT와 20개 Page lookup으로 줄어 40 VU에서도 풀 포화가 관측되지 않았다.
응답 평균 약 12.82ms에 비해 think time 200ms가 크므로 요청 생성 속도의 영향이 커졌다.
따라서 161.84 RPS를 서버 최대 처리량으로 해석하지 않는다. 이번 실험은 40 VU를 넘기지 않았다.

다음에는 인덱스를 더 추가하기보다 동일 실험의 반복성, 혼합 트래픽 및 COUNT의 실제 connection 점유를
확인할 가치가 있다. COUNT가 없는 응답 방식/캐시/풀 변경은 별도의 실험 변수이며 이번에는 적용하지 않았다.

## Index cost and production recommendation

`mysql.innodb_index_stats`의 size × 16,384 bytes로 확인한 할당 공간은 다음과 같다.
OS 파일 크기, redo 크기, 실제 메모리 상주량을 뜻하지 않는다.

| 인덱스 | size pages | bytes | MiB |
|---|---:|---:|---:|
| PRIMARY | 8,043 | 131,776,512 | 125.67 |
| idx_facility_region | 1,572 | 25,755,648 | 24.56 |
| idx_facility_region_type | 1,636 | 26,804,224 | 25.56 |
| idx_facility_region_district_type | 2,921 | 47,857,664 | 45.64 |
| 보조 인덱스 합계 | 6,129 | 100,417,536 | 95.77 |

세 조건의 첫 페이지 지연시간을 모두 중요하게 보는 현재 읽기 실험에는 세 인덱스를 유지하는 것이 합리적이다.
세 인덱스가 각각 실제 선택되어 WHERE와 id 정렬을 함께 해결했다. 이 세 후보 중 하나의 넓은 인덱스만으로
나머지 검색 패턴의 정렬 성질까지 대체할 수는 없다. 다만 모든 가능한 인덱스 구성 중 전역 최소임을 증명한 것은 아니다.

운영에서는 세 개를 무조건 유지해야 한다고 결론 내리지 않는다. region 접두어가 반복되어 저장/캐시 공간이 겹치고,
INSERT/DELETE에는 세 B-tree를 추가로 관리하며 검색 열 UPDATE에도 관련 인덱스 변경 비용이 생긴다.
쓰기 지연, redo, page split, 혼합 부하는 이번 읽기 실험에서 측정하지 않았다.
특히 폭이 넓은 district 복합 인덱스는 약 45.64 MiB다. C가 드물다면 그 비용 대비 이득을 재평가해야 한다.
B가 중요한 경우에는 `(region)`을 단순 중복으로 제거해서는 안 된다.
추후 실제 A/B/C 트래픽 비율, p95 요구, 쓰기량과 공간 예산을 바탕으로 후보 제거 실험과 쓰기 측정을 별도로 해야 한다.
이번에는 요청된 세 후보를 유지하며 추가 인덱스나 다른 최적화를 적용하지 않았다.

## Verification and artifacts

2026-09-09 최종 확인:

- `.\gradlew.bat build --rerun-tasks`: BUILD SUCCESSFUL, 34초, 7개 task 실제 실행.
- 기존 18개 테스트: Controller 통합 14 + application context 1 + seed 규칙 3, 실패/오류/skip 모두 0.
- 테스트 실행 프로세스에만 `JAVA_TOOL_OPTIONS=-Dspring.jpa.hibernate.ddl-auto=validate`를 설정하여
  schema 자동 변경을 방지했고 종료 후 원래 환경 값으로 복구했다. 설정 파일은 변경하지 않았다.
- 통합 테스트 fixture는 기존 transaction rollback 방식이다. 테스트 후 Facility 1,000,000건,
  이름 고유 개수 1,000,000, 지역별 건수와 각 region의 district 10개를 확인했다.
- 전체 행 내용의 기존 CRC32 합계 검증값 `2148598155341640`이 실험 전/후 일치했다.
  이는 일관성 검사이며 암호학적 무결성 증명은 아니다.
- A/B/C API는 최종 HTTP 200, content 20, totalElements 50,000/40,000/5,000을 유지했다.
- SHOW INDEX: PRIMARY(id), idx_facility_region(region), idx_facility_region_type(region,type),
  idx_facility_region_district_type(region,district,type). 그 외 인덱스는 없다.
- Java/Repository/Entity/설정/Compose/monitoring/기존 k6 스크립트의 Git diff는 없다.

버전 관리 대상은 다음 세 파일이다. API key, token, 운영 credential을 포함하지 않는다.

- [SQL 정의](../sql/facility-search-indexes.sql)
- [After 결과 JSON](../loadtest/results/facility-index-after-summary.json): 단계별 HTTP 통계,
  PromQL·실행 시각·server 표본 요약·중간/최종 DB 계획과 반복 측정값.
- 이 문서.

`build/k6/` raw 결과 및 `build/` 내 임시 진단/집계 스크립트는 기존 ignore 규칙으로 제외한다.
기존 Before 결과나 loadtest 스크립트를 덮어쓰지 않았다. 실험은 완료된 A/B/C 결과를 재사용해 마무리했다.

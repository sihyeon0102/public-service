# Facility Cache Before Baseline

## Purpose

2026-09-09(Asia/Seoul), 검색 인덱스 적용 후에도 같은 인기 검색 URL이 반복될 때 남는
HTTP/DB/Hikari 비용을 측정했다. 이 문서는 Redis/Spring Cache를 추가하기 전 비교 기준이다.

이번 실험에서 바꾼 것은 부하 패턴뿐이다. Facility API, Repository와 SQL, Pageable,
Hikari 설정, Entity, DB schema와 데이터, 세 검색 인덱스, Compose 및 monitoring 설정은 변경하지 않았다.
Redis와 Spring Cache 의존성/설정/프로세스는 없다.

## Endpoint selection

각 endpoint는 한 실행에서 하나의 완전히 같은 URL만 반복했다.

| 대상 | URL 조건 | 일치 건수 | 선택 이유 |
|---|---|---:|---|
| A | `region=SEOUL&type=LIBRARY&page=0&size=20` | 50,000 | 인덱스 After에서도 매 요청 COUNT가 50,000개 항목을 읽고, 40 VU p95 259.59ms로 세 조건 중 가장 높았다. |
| B | `region=GWANGJU&page=0&size=20` | 40,000 | COUNT 40,000개가 남았고, 인덱스 After의 40 VU Hikari pending 최대 표본 13으로 세 조건 중 가장 높았다. |

C(`SEOUL + SEOUL-DISTRICT-1 + LIBRARY`)는 제외했다. 인덱스 After에서 COUNT 범위 5,000건,
40 VU p95 19.28ms, pending 0이어서 반복 조회로 인한 잔여 DB 비용을 보여주는 대표 조건으로는 A/B보다 약하다.

이전 인덱스 After 수치는 대상 선정 근거다. 이번 실행 결과가 더 좋아졌더라도 캐시 효과가 아니다.
버퍼/JVM/host 부하가 다른 단일 실행끼리 생긴 변동이며, Redis After 비교에는 이 문서의 새 baseline을 사용한다.

## Environment

- 시작 commit: `d28ab17173c010081164076a28abd79d77acb286`.
- Facility 1,000,000건, 이름 고유 개수 1,000,000.
- MySQL 8.4.11 / 같은 `public-service-mysql` container와 volume.
- Java 21.0.12.1 / Spring Boot 4.1.1, Windows host `:8080`.
- Hikari max=10, Prometheus scrape interval=15초.
- k6 image `grafana/k6:2.1.0`.
- 인덱스: `PRIMARY(id)`, `idx_facility_region(region)`,
  `idx_facility_region_type(region,type)`,
  `idx_facility_region_district_type(region,district,type)`.
- Redis/Cache 없음. `build.gradle`에도 Redis/Cache dependency가 없다.

## Hot-read workload

[facility-cache-baseline.js](../loadtest/facility-cache-baseline.js)는 `TARGET=A|B`와
`MODE=smoke|warmup|baseline`을 받는다. 각 VU는 선택한 동일 URL을 요청하고 0.2초를 기다린다.
응답은 HTTP 200, JSON, content 20건, 예상 totalElements, page=0, size=20을 매번 검사한다.
latency SLO는 두지 않았고 응답 정상성만 threshold로 사용했다.

실행 순서:

1. A smoke, B smoke: 1 VU × 10초.
2. A warm-up, B warm-up: 1 VU 시작, 5초간 3 VU로 상승, 20초 유지, 5초 하강.
3. A baseline, B baseline: 각각 5 → 10 → 20 → 40 VU.
4. VU별 10초 상승 + 40초 유지 + 5초 하강, 총 220초.

이전 결과에서 40 VU에 pool 포화가 나타난 적이 있고 Redis After와 같은 조건을 유지해야 하므로
40 VU를 넘기지 않았다. 이 테스트는 closed-loop VU 방식이다. 응답이 빨라지면 같은 VU가 더 자주 요청한다.
단계 RPS는 이전 문서와 같이 단계 요청 수/55초이며 상승·유지·하강을 모두 포함한다.
서버의 최대 처리량을 측정하는 constant-arrival-rate 테스트는 아니다.

재실행 예시:

```powershell
New-Item -ItemType Directory -Force build/k6 | Out-Null
docker run --rm -i `
  -e TARGET=A -e MODE=baseline `
  -e BASE_URL=http://host.docker.internal:8080 `
  -e SUMMARY_PATH=/results/cache-repeat-A.json `
  -v "${PWD}/loadtest:/scripts:ro" `
  -v "${PWD}/build/k6:/results" `
  grafana/k6:2.1.0 run --quiet /scripts/facility-cache-baseline.js
```

Redis After에서는 script, TARGET 순서, smoke/warm-up, stage, think time을 그대로 사용한다.
결과 파일 이름은 기존 baseline을 덮어쓰지 않게 바꾼다.

## HTTP results

Smoke와 warm-up 모두 check rate 100%, HTTP 실패율 0%였다. baseline 전체와 모든 단계도
응답/본문 check 실패 0, HTTP 실패율 0%였다.

| 대상 | VU | requests | RPS | avg ms | p95 ms | p99 ms |
|---|---:|---:|---:|---:|---:|---:|
| A | 5 | 1,085 | 19.73 | 16.05 | 20.90 | 26.85 |
| A | 10 | 2,156 | 39.20 | 18.58 | 27.55 | 44.01 |
| A | 20 | 4,322 | 78.58 | 18.56 | 27.15 | 38.50 |
| A | 40 | 8,153 | **148.24** | **31.83** | **62.14** | **103.64** |
| B | 5 | 1,087 | 19.76 | 15.65 | 21.94 | 34.69 |
| B | 10 | 2,172 | 39.49 | 17.02 | 28.01 | 53.13 |
| B | 20 | 4,385 | 79.73 | 15.51 | 23.54 | 33.46 |
| B | 40 | 8,578 | **155.96** | **20.59** | **44.56** | **72.62** |

| 대상 | 전체 requests | 전체 RPS | iterations | iterations/sec | 전체 avg / p95 / p99 ms |
|---|---:|---:|---:|---:|---:|
| A | 15,717 | 71.40 | 15,716 | 71.39 | 25.27 / 51.91 / 85.11 |
| B | 16,223 | 73.67 | 16,222 | 73.66 | 18.41 / 35.99 / 63.69 |

전체 http_reqs에는 setup 검증 요청 1개가 추가된다. 단계별 custom request 합은 iterations와 일치한다.
A 실행은 2026-09-09 01:09:38–01:13:19 KST, B는 01:15:45–01:19:26 KST다.

## Prometheus and Hikari

두 실행 모두 Prometheus target은 UP이었다. Spring HTTP metric은 query parameter를 label로 보존하지 않고
`uri="/api/facilities"`로 합치므로 A/B를 실행 시간창으로 분리했다.

서버 요약은 baseline 시작+45초부터 종료까지 15초 간격 12개 표본,
단계별 값은 각 명목 단계 시작+10~50초의 3개 표본이다. 15초 scrape에서 놓치는 순간 peak가 있을 수 있다.

40 VU 표본:

| 대상 | HTTP p95 평균 / 최대 ms (`rate[30s]`) | system CPU 평균 / 최대 | JVM CPU 평균 / 최대 | Heap 범위 MB | Hikari active 평균 / 최대 | idle 평균 / 최소 | pending 평균 / 최대 | max |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| A | 46.14 / 59.73 | 77.60% / 90.11% | 3.76% / 5.12% | 48.90–78.54 | 4.00 / 7 | 6.00 / 3 | 0 / 0 | 10 |
| B | 22.61 / 30.05 | 61.84% / 76.97% | 3.42% / 5.12% | 57.62–91.15 | 4.00 / 7 | 6.00 / 3 | 0 / 0 | 10 |

JVM live thread 최대 표본은 A 42, B 31이다. Windows system CPU는 MySQL 전용 CPU가 아니며
Docker, IDE와 다른 host 작업을 포함한다. Heap은 GC/JVM 이전 상태에 따라 변하므로 캐시 판단의 단독 근거가 아니다.

기존 Grafana 5분 p95는 A 실행창 21.17–43.71ms, B 실행창 21.65–53.03ms였다.
5분 창에는 warm-up이나 인접 구간이 섞일 수 있어 endpoint별 k6 p95와 직접 같은 값으로 비교하지 않는다.
Redis After에서도 동일 PromQL과 시간창을 사용한다.

이번 단일 실행에서는 40 VU까지 Hikari pending 표본이 0이고 active 최대도 7이었다.
따라서 현재 조건에서 connection pool이 포화됐다고 말할 수 없다. 다만 active가 증가하고 idle이 줄었으며,
아래 DB statement 증거는 동일 요청마다 connection과 쿼리 실행이 계속 필요하다는 점을 보여준다.

## Remaining database work

Performance Schema의 `events_statements_summary_by_digest`를 각 baseline 직전/직후 읽었다.
이 누적 통계는 동시 실행 중 원자적 snapshot이 아니므로 HTTP 요청 수와 수십 건 차이가 난다.
정확한 request-to-query 비율로 쓰지 않고 대량 반복 DB 실행 및 비용 규모를 확인하는 용도로 사용한다.

| 대상 | 쿼리 | statement 증가(약) | rows examined 증가 | 누적 DB timer | statement당 평균 |
|---|---|---:|---:|---:|---:|
| A | Page SELECT | 15,677 | 314,000 | 8.40s | 0.54ms |
| A | COUNT | 15,708 | **785,800,000** | **141.84s** | 9.03ms |
| B | Page SELECT | 16,174 | 324,180 | 6.81s | 0.42ms |
| B | COUNT | 16,214 | **648,760,000** | **78.59s** | 4.85ms |

DB timer는 동시 statement의 시간 합계이며 220초 벽시계 시간과 직접 비교하지 않는다.
A는 Page+COUNT timer 중 COUNT가 약 94.4%, B는 약 92.0%다.

부하 종료 후 다른 요청이 없는 유휴 상태에서 각 endpoint를 한 번 더 호출하고 digest 증가를 확인했다.

- A: Page 1회/20행 검사 + COUNT 1회/50,000행 검사.
- B: Page 1회/20행 검사 + COUNT 1회/40,000행 검사.

즉 같은 URL과 결과를 반복해도 현재는 응답 재사용이 없으며 매번 Page SELECT와 COUNT가 실행된다.
인덱스가 full scan을 제거했지만 COUNT는 일치하는 인덱스 항목 전체를 계속 순회한다.

## Cache experiment decision

Redis가 반드시 필요하다고 결론 내리기에는 아직 부족하다. 이번 실행은 오류와 pending 없이 40 VU를 처리했고,
캐시는 일관성, TTL, invalidation, 장애 처리와 메모리 비용을 추가한다. 실제 인기 검색의 반복률과 허용 stale 시간이
정해지지 않았다면 DB 인덱스만으로 충분할 수도 있다.

하지만 다음 Redis 실험을 할 근거는 충분하다. 같은 응답인데도 A/B 모두 요청마다 두 DB 쿼리를 실행하고,
COUNT만 각각 약 7.86억/6.49억 항목을 검사했다. Redis hit에서 이 DB 작업이 사라지는지 측정하면
캐시의 이득과 복잡성을 수치로 비교할 수 있다.

Redis After에서 비교할 항목:

1. 동일 5/10/20/40 VU의 RPS, avg, p95, p99, error rate.
2. HTTP server histogram p95와 Windows/JVM CPU, Heap.
3. Hikari active/idle/pending/max. 특히 cache hit 중 active와 digest 증가가 감소하는지.
4. Page/COUNT digest statement/rows/timer delta. 첫 miss 이후 hit에서 DB 증가가 없어야 한다.
5. cache hit/miss/put/eviction 수와 hit ratio, Redis command latency 및 memory.
6. cold miss와 warm hit를 분리하고, TTL 만료·갱신 후 데이터 정합성을 별도 검증.

After에서 cache hit만 측정해 miss 비용을 숨기지 않는다. 같은 smoke/warm-up 순서를 사용하되
warm-up이 cache를 채운다는 점을 명시하고 cold-cache 결과는 별도 실행으로 보존해야 한다.

## Result files and verification

- 실행 스크립트: [facility-cache-baseline.js](../loadtest/facility-cache-baseline.js)
- 비교용 작은 결과: [facility-cache-before-summary.json](../loadtest/results/facility-cache-before-summary.json)
- raw k6/Prometheus/Performance Schema snapshot: `build/k6/` (기존 `.gitignore`로 제외)

결과 JSON에는 smoke/warm-up, 전체 및 단계별 k6 결과, 실제 PromQL과 server 표본,
DB digest delta, 실행 시각과 script SHA-256을 보존했다.

최종 검증:

- `.\gradlew.bat build --rerun-tasks`: BUILD SUCCESSFUL, 22초, 7개 task 실행.
- 기존 테스트 18개: Controller 통합 14 + context 1 + seed 규칙 3, 실패/오류/skip 모두 0.
- test process에만 `JAVA_TOOL_OPTIONS=-Dspring.jpa.hibernate.ddl-auto=validate`를 적용하여 schema 자동 변경을 방지했다.
- Facility 1,000,000건, distinct name 1,000,000, 지역별 건수와 각 region district 10개 유지.
- 데이터 CRC32 합계 검증값 `2148598155341640` 유지. 이는 변경 감지용 값이며 암호학적 증명은 아니다.
- `SHOW INDEX`에서 PRIMARY와 기존 세 검색 인덱스만 확인했다.
- Spring Boot health UP, Prometheus public-service target UP, MySQL/Prometheus/Grafana container UP.
- Java, Repository, API, application.properties, build.gradle, Compose, monitoring,
  기존 `facility-baseline.js`, 기존 인덱스 SQL에는 Git diff가 없다.
- 실제 API key, token, 운영 credential은 새 파일에 없다.

`build/k6/`의 raw 결과와 측정 보조 스크립트는 기존 `build/` ignore 규칙에 따라 commit하지 않는다.

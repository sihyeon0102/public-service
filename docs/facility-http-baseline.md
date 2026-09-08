# Facility HTTP Performance Baseline

## Purpose

이 문서는 DB 인덱스 적용 전 Facility 검색 API의 HTTP Before baseline을 기록한다.
목표는 현재 병목을 관찰하고 이후 인덱스 실험을 같은 조건으로 비교할 기준을 만드는 것이다.
이번 작업에서는 인덱스, 쿼리, Hikari 설정, 캐시와 애플리케이션 코드를 최적화하지 않았다.

측정일은 2026-09-08이며 기준 소스 커밋은
`88a260f089f1a620fd905051924011bb10d358bf`이다.

## Environment

- Windows host에서 Java 21.0.12.1 / Spring Boot 4.1.1 애플리케이션을 port 8080으로 실행했다.
- Docker Compose의 기존 MySQL 8.4.11, Prometheus 3.14.0, Grafana 13.2.1을 사용했다.
- Facility는 1,000,000건이며 측정 전후 데이터 체크값이 같았다.
- `SHOW INDEX FROM facility`에는 `PRIMARY(id)` BTREE 하나만 있었다.
- InnoDB buffer pool은 128 MiB이며 강제로 비우거나 서버를 재시작하지 않았다.
- Hikari maximum pool size는 기본값 10이다. 변경하지 않았다.
- Prometheus scrape interval은 15초다.
- k6는 공식 Docker image `grafana/k6:2.1.0`을 사용했다.
- k6 container는 `http://host.docker.internal:8080`으로 Windows host의 Spring Boot에 접근했다.
- Redis와 애플리케이션 Cache는 없다.
- 같은 로컬 PC에서 Spring Boot, Docker MySQL, k6, Prometheus, Grafana가 함께 실행됐다.
  따라서 전체 CPU는 이 구성 요소와 다른 host 작업의 영향을 함께 받는다.

이 결과는 이 로컬 환경의 단일 실험이다. 최종 SLO나 운영 용량을 뜻하지 않으며,
`EXPLAIN ANALYZE` 시간과도 측정 범위가 다르다.

## Dataset

| Endpoint | 조건 | 일치 건수 | 선택도 |
|---|---|---:|---:|
| A | SEOUL + LIBRARY | 50,000 | 5% |
| B | GWANGJU | 40,000 | 4% |
| C | SEOUL + SEOUL-DISTRICT-1 + LIBRARY | 5,000 | 0.5% |

세 요청 모두 page=0, size=20이다. 응답은 content 20개와 예상 totalElements를 반환한다.

## k6 Test Design

### Script and endpoint selection

스크립트는 [facility-baseline.js](../loadtest/facility-baseline.js) 하나다.
`TARGET=A|B|C`와 `MODE=smoke|warmup|baseline` 환경 변수로 endpoint와 실행 종류를 선택한다.
각 endpoint는 별도로 실행하여 k6 결과가 서로 섞이지 않도록 했다.

```text
A /api/facilities?region=SEOUL&type=LIBRARY&page=0&size=20
B /api/facilities?region=GWANGJU&page=0&size=20
C /api/facilities?region=SEOUL&district=SEOUL-DISTRICT-1&type=LIBRARY&page=0&size=20
```

모든 반복은 다음을 검사한다.

- HTTP 200
- JSON 응답
- content 20개
- endpoint별 예상 totalElements
- page=0, size=20

성능 SLO가 정해지지 않았으므로 지연시간 threshold는 없다.
`checks: rate==1`, `http_req_failed: rate==0`만 정상성 조건으로 사용했다.

### Smoke and warm-up

본 측정 전에 A/B/C 각각 다음 절차를 수행했다.

1. Smoke: 1 VU, 10초
2. Warm-up: 5초 동안 1→3 VU, 20초 유지, 5초 동안 0 VU로 감소
3. Baseline: 5→10→20→40 VU 단계

Smoke 결과는 모두 check 100%, HTTP failure 0이었다.

| Endpoint | Smoke 요청 수 | avg | p95 |
|---|---:|---:|---:|
| A | 32 | 131.14ms | 145.11ms |
| B | 21 | 313.65ms | 357.55ms |
| C | 30 | 154.90ms | 205.43ms |

Warm-up도 모두 check 100%, HTTP failure 0이었다. warm-up 결과는 baseline 통계에 포함하지 않았다.
After 측정에서도 DB/OS cache를 강제로 비우지 않고 같은 smoke와 warm-up 절차를 반복한다.

### VU stages and think time

각 endpoint baseline은 아래 네 scenario를 순차 실행했다. 총 명목 시간은 220초다.

| 구간 | ramp-up | 유지 | ramp-down |
|---:|---:|---:|---:|
| 5 VU | 10초 | 40초 | 5초 |
| 10 VU | 10초 | 40초 | 5초 |
| 20 VU | 10초 | 40초 | 5초 |
| 40 VU | 10초 | 40초 | 5초 |

각 반복 뒤 `sleep(0.2)`를 적용했다. 이는 VU가 응답 즉시 무한 tight loop로 재요청하는 것을 피하면서도,
DB 병목을 관찰할 만큼 짧은 think time이다. 이 0.2초도 iteration throughput에 포함된다.

단계별 custom metric은 ramp-up, 40초 유지, ramp-down 전체 55초를 포함한다.
단계 RPS는 해당 custom request count / 55초로 계산했다. 일정 VU 유지 구간만의 RPS가 아니다.
전체 k6 request 수에는 실행 전 setup 검증 요청 1개가 포함되며 단계별 request 수에는 포함되지 않는다.

### Commands

PowerShell에서 raw summary는 Git에서 제외된 `build/k6`에 저장한다.

```powershell
New-Item -ItemType Directory -Force -Path build/k6

# 예: A smoke
docker run --rm -i `
  -e TARGET=A -e MODE=smoke `
  -e BASE_URL=http://host.docker.internal:8080 `
  -e SUMMARY_PATH=/results/smoke-A.json `
  -v "${PWD}/loadtest:/scripts:ro" `
  -v "${PWD}/build/k6:/results" `
  grafana/k6:2.1.0 run --quiet /scripts/facility-baseline.js

# 같은 명령에서 MODE=warmup 또는 MODE=baseline, TARGET=A/B/C를 선택한다.
```

## Baseline Results

### Overall by endpoint

전체 행은 네 VU scenario와 setup 요청을 합친 값이다. endpoint 간 상대 비교에 사용하되,
부하가 시간에 따라 변하므로 일정 동시성 처리량으로 해석하지 않는다.

| Endpoint | 총 요청 | RPS | iterations/s | avg | median | p90 | p95 | p99 | max | 실패율 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| A | 4,901 | 22.23 | 22.23 | 527.93ms | 392.54ms | 1,094.07ms | 1,171.03ms | 1,363.84ms | 1,852.16ms | 0% |
| B | 2,377 | 10.76 | 10.76 | 1,322.39ms | 969.73ms | 3,037.89ms | 3,295.21ms | 3,869.26ms | 4,319.21ms | 0% |
| C | 4,580 | 20.80 | 20.80 | 579.08ms | 388.27ms | 1,316.18ms | 1,460.51ms | 1,979.50ms | 3,352.87ms | 0% |

모든 endpoint에서 check rate는 100%, `http_req_failed`는 0%였다. 타임아웃도 없었다.

### A — SEOUL + LIBRARY

| VU | 요청 | RPS | avg | median | p90 | p95 | p99 | max | 실패율 |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 5 | 638 | 11.60 | 168.06ms | 156.87ms | 216.90ms | 254.71ms | 321.84ms | 409.10ms | 0% |
| 10 | 1,018 | 18.51 | 263.73ms | 236.75ms | 404.13ms | 462.25ms | 516.63ms | 598.32ms | 0% |
| 20 | 1,578 | 28.69 | 401.17ms | 403.49ms | 557.08ms | 600.48ms | 673.61ms | 815.32ms | 0% |
| 40 | 1,666 | 30.29 | 947.48ms | 1,029.39ms | 1,203.93ms | 1,271.96ms | 1,509.09ms | 1,852.16ms | 0% |

20→40 VU에서 RPS 증가는 약 5.6%에 그친 반면 p95는 약 2.12배가 됐다.

### B — GWANGJU

| VU | 요청 | RPS | avg | median | p90 | p95 | p99 | max | 실패율 |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 5 | 407 | 7.40 | 378.28ms | 373.13ms | 429.11ms | 442.71ms | 785.37ms | 914.74ms | 0% |
| 10 | 610 | 11.09 | 579.90ms | 557.85ms | 730.78ms | 836.74ms | 1,017.85ms | 1,219.04ms | 0% |
| 20 | 693 | 12.60 | 1,185.61ms | 1,221.94ms | 1,584.40ms | 1,688.49ms | 1,868.74ms | 2,356.95ms | 0% |
| 40 | 666 | 12.11 | 2,723.29ms | 2,887.27ms | 3,516.51ms | 3,741.50ms | 4,085.89ms | 4,319.21ms | 0% |

20→40 VU에서 RPS는 약 3.9% 감소했고 p95는 약 2.22배가 됐다. 가장 명확한 포화다.

### C — SEOUL + district + LIBRARY

| VU | 요청 | RPS | avg | median | p90 | p95 | p99 | max | 실패율 |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 5 | 632 | 11.49 | 172.01ms | 166.17ms | 202.62ms | 227.05ms | 298.83ms | 325.02ms | 0% |
| 10 | 1,052 | 19.13 | 249.23ms | 216.46ms | 400.87ms | 440.33ms | 549.95ms | 685.97ms | 0% |
| 20 | 1,549 | 28.16 | 412.93ms | 411.91ms | 563.99ms | 605.54ms | 674.09ms | 830.54ms | 0% |
| 40 | 1,346 | 24.47 | 1,219.58ms | 1,238.84ms | 1,710.07ms | 1,928.68ms | 2,118.09ms | 3,352.87ms | 0% |

20→40 VU에서 RPS가 약 13.1% 감소하고 p95가 약 3.19배가 됐다.

## Server Metrics

Prometheus는 15초마다 scrape했다. 아래 endpoint별 값은 실행 시작 45초 후부터 종료까지
15초 간격으로 읽은 12개 표본이다. HTTP rate/p95는 30초 창을 사용해 인접 endpoint의 요청이 섞이지 않게 했다.
이는 Grafana dashboard의 5분 창과 구분되는 endpoint 진단용 계산이다.

| Endpoint | server RPS avg / max | server p95 avg / max | system CPU avg / max | JVM process CPU avg / max | Heap used min / max | Threads max |
|---|---:|---:|---:|---:|---:|---:|
| A | 22.46 / 31.80 | 0.583s / 1.377s | 89.2% / 100% | 2.16% / 4.20% | 39.5 / 173.0 MB | 54 |
| B | 11.13 / 13.67 | 1.466s / 3.468s | 91.7% / 100% | 0.59% / 0.99% | 42.9 / 97.4 MB | 56 |
| C | 21.50 / 31.67 | 0.674s / 2.093s | 88.0% / 100% | 1.02% / 1.40% | 46.7 / 113.5 MB | 56 |

`system_cpu_usage`는 Spring Boot JVM이 관찰한 Windows 전체 CPU 사용률이며 Docker와 다른 host 작업을 포함한다.
MySQL container CPU만 분리한 값이 아니다. 낮은 JVM process CPU와 높은 system CPU의 조합은
Spring 애플리케이션 자체의 CPU 연산이 주된 포화 지점이라는 가설을 지지하지 않는다.

### Hikari by VU stage

각 유지 구간에서 Prometheus가 수집한 3개 표본의 최대값이다. 15초 scrape 사이의 짧은 peak는 놓칠 수 있다.

| Endpoint | VU | active max | pending max | idle min | system CPU avg |
|---|---:|---:|---:|---:|---:|
| A | 5 | 3 | 0 | 7 | 62.9% |
| A | 10 | 9 | 0 | 1 | 86.3% |
| A | 20 | 10 | 3 | 0 | 93.8% |
| A | 40 | 10 | 23 | 0 | 96.0% |
| B | 5 | 5 | 0 | 5 | 61.9% |
| B | 10 | 9 | 0 | 1 | 88.7% |
| B | 20 | 10 | 8 | 0 | 94.7% |
| B | 40 | 10 | 28 | 0 | 98.2% |
| C | 5 | 3 | 0 | 7 | 56.6% |
| C | 10 | 10 | 0 | 0 | 75.7% |
| C | 20 | 10 | 5 | 0 | 93.8% |
| C | 40 | 10 | 28 | 0 | 96.1% |

Hikari max는 전 구간에서 10으로 유지됐다. 20 VU부터 active가 10에 도달하고 pending이 나타났으며,
40 VU의 B/C는 수집된 모든 유지 구간 표본에서 active=10, idle=0이었다.

Grafana 기본 패널은 최근 5분 창을 사용하고 query parameter를 label로 갖지 않는다.
따라서 시간 창이 겹치면 A/B/C를 분리할 수 없다. 세 실행 전체 시간에서 dashboard와 동일한 PromQL로 관찰한 값은:

- HTTP p95: 평균 1.733초, 최대 3.333초
- HTTP RPS: 평균 11.41, 최대 17.79 (5분 이동 창)
- HTTP 4xx/5xx rate: 모두 0%

k6와 server p95는 서로 다른 histogram/수집 창으로 계산하므로 숫자가 정확히 같을 필요는 없다.
k6가 클라이언트에서 모든 요청을 보고한 값이 endpoint별 기준 결과다.

## Correlation With DB Baseline

기존 [DB baseline](facility-db-baseline.md)은 다음 실제 scan을 기록했다.

| Endpoint | Page SELECT scan | COUNT scan | 단일 DB 진단 중앙값 |
|---|---:|---:|---:|
| A | 80행 | 1,000,000행 | Page 0.025ms, COUNT 156ms |
| B | 960,020행 | 1,000,000행 | Page 210ms, COUNT 133ms |
| C | 1,151행 | 1,000,000행 | Page 0.303ms, COUNT 156ms |

모든 Pageable 요청은 content 20개 SELECT와 totalElements COUNT를 순차 실행한다.
A/C는 조건부 COUNT의 100만 행 full table scan이 공통 비용이다.
B는 그 비용에 PK 뒤쪽 GWANGJU 20건을 찾는 960,020행 index scan까지 더해진다.

이 차이는 HTTP 결과와 같은 방향이다. 5 VU에서 B avg 378ms는 A/C 약 168~172ms보다 두 배 이상이고,
B의 최대 처리량은 약 12~13 RPS에서 정체된 반면 A/C는 20 VU에서 약 28 RPS에 도달했다.
동시 요청이 늘면 느린 DB 조회가 connection을 오래 점유하고 반환을 늦출 수 있다.
그 결과 active가 max 10에 도달하고 이후 요청이 pending으로 대기하며 tail latency가 커지는 흐름과 일치한다.

이는 “Hikari 크기 10이 근본 원인”이라는 결론은 아니다. 풀을 무작정 키우면 이미 높은 system CPU와
full scan 동시 실행을 더 늘릴 수 있다. 현재 증거가 가장 강하게 지목하는 원인은 인덱스 없는 Page/COUNT scan이며,
pool saturation은 그 비용이 동시성 아래 누적된 결과일 가능성이 높다.
MySQL 전용 CPU exporter나 query latency 계측은 이번 범위에 없으므로 인과를 완전히 분리했다고 주장하지 않는다.

## Findings

- 모든 요청은 정상 응답했지만 20→40 VU에서 처리량 증가가 멈추거나 감소하고 p95가 크게 상승했다.
- B(GWANGJU)가 모든 단계에서 가장 느렸고 40 VU p95는 3.74초였다.
- A도 40 VU에서 처리량 증가 없이 p95 1.27초로 증가했다.
- C는 낮은 선택도에도 COUNT가 전체를 검사하므로 A와 비슷하며, 40 VU에서는 RPS가 감소했다.
- 20 VU부터 Hikari active=10과 pending 증가가 관찰됐다.
- 40 VU에서 Windows system CPU는 거의 포화됐지만 JVM process CPU는 낮았다.
- Heap은 최대 약 173 MB였고 지속적인 단조 증가나 메모리 부족 징후는 이 짧은 실행에서 보이지 않았다.
- 40 VU까지 이미 포화 징후가 명확해 100 VU 이상으로 확대하지 않았다.
- 이번 결과는 warm cache 비교 기준이다. 실행 순서 A→B→C와 동일 warm-up을 After에서도 유지한다.

## Next Hypothesis

다음 단계에서는 아직 구현하지 않은 아래 가설을 하나씩 실험할 가치가 있다.

1. `(region)` 후보가 GWANGJU Page와 region COUNT의 검사 범위를 줄여 B의 지연시간과 pool 점유를 크게 낮출 수 있다.
2. `(region, type)` 후보가 A의 50,000건 범위를 좁혀 COUNT full scan을 줄일 수 있다.
3. `(region, district, type)` 후보가 C의 5,000건 COUNT를 좁힐 수 있다.
4. 각 후보 적용 후 같은 script·순서·warm-up·stage를 재사용하고 DB 실행계획과 HTTP/Hikari를 함께 비교해야 한다.

인덱스 순서가 `ORDER BY id`와 Page SELECT에 어떤 계획을 만들지는 EXPLAIN ANALYZE로 다시 확인해야 한다.
이번 측정에서는 CREATE INDEX, ALTER TABLE, 쿼리 변경, pool 변경, Cache 도입을 수행하지 않았다.

## Result Files

- 실행 스크립트: [facility-baseline.js](../loadtest/facility-baseline.js)
- 작은 비교 결과: [facility-before-summary.json](../loadtest/results/facility-before-summary.json)
- 상세 raw k6/Prometheus 결과: `build/k6/` — 기존 `.gitignore`의 build 규칙으로 제외

JSON에는 smoke, warm-up, 전체와 단계별 k6 통계, endpoint별 server metric 범위,
단계별 Hikari/CPU 표본을 보존했다. 대용량 요청별 raw log는 생성하거나 커밋하지 않았다.

## Reproduction Checklist

After 비교 전 다음을 동일하게 맞춘다.

- Facility 1,000,000건과 같은 데이터 분포
- 변경하려는 실험 인덱스 외 동일 schema
- Hikari max 10과 동일 Spring Boot 설정
- 같은 MySQL container 및 128 MiB buffer pool
- 같은 API와 k6 script
- A→B→C 순서
- endpoint마다 smoke 10초와 warm-up 30초
- 5/10/20/40 VU, 각 10초 ramp-up + 40초 유지 + 5초 ramp-down
- 0.2초 think time
- Prometheus scrape interval 15초
- 같은 host에서 불필요한 작업을 줄이고 system CPU도 함께 기록

최종 검증에서 Facility는 1,000,000건, 데이터 체크값은 `2148598155341640`,
인덱스는 `PRIMARY(id)` 하나였다. Java 기능 코드, DB schema와 Compose 설정은 변경하지 않았다.

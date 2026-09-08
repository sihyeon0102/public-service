# Local Monitoring with Prometheus and Grafana

## 목적과 전체 구조

이 구성은 Facility API의 성능을 개선하는 기능이 아니라 HTTP, JVM, DB 연결 상태를 관찰하기 위한 로컬 환경이다.
Facility 1,000,000건, 검색 쿼리, PRIMARY(id)만 있는 인덱스 상태를 유지한다.
k6 부하테스트는 아직 수행하지 않았다.

```text
Windows host
  IntelliJ 또는 java -jar로 실행한 Spring Boot :8080
    ├─ Facility API ───────────────→ localhost:13306 → Docker MySQL :3306
    └─ /actuator/prometheus ←────── Docker Prometheus :9090
                                      ↑
                                 Docker Grafana :3000

Prometheus → http://host.docker.internal:8080/actuator/prometheus
Grafana    → http://prometheus:9090  (Compose 네트워크 내부)
Browser    → http://localhost:9090 또는 http://localhost:3000
```

- **Spring Boot / Actuator / Micrometer:** 요청 횟수, 처리시간, JVM 메모리, 연결 풀 상태를 meter에 기록하고 노출한다.
- **MySQL:** 기존 Docker DB다. 저장 데이터와 volume 설정을 변경하지 않았다.
- **Prometheus:** 15초마다 애플리케이션 지표를 가져와 시계열로 저장한다. 이 주기적인 수집이 **scrape**다.
- **Grafana:** Prometheus에 PromQL 질의를 보내 그래프를 그린다. 지표를 가져오는 연결 설정이 **datasource**다.
- **Provisioning:** datasource와 dashboard를 파일로 읽어 자동 등록하는 방식이다. UI 수동 등록은 필요 없다.

## 버전과 파일

- Spring Boot 4.1.1, Java 21, 기존 Gradle 구성.
- 추가 의존성: `spring-boot-starter-actuator`, `micrometer-registry-prometheus`.
  개별 버전을 지정하지 않고 Spring Boot dependency management를 따른다.
  현재 resolved registry 버전은 Micrometer 1.17.1이다.
- Docker 이미지: `mysql:8.4`(기존), `prom/prometheus:v3.14.0`, `grafana/grafana:13.2.1`.
- 설정/대시보드는 다음 파일로 관리한다.

```text
monitoring/
  prometheus/prometheus.yml
  grafana/
    provisioning/
      datasources/prometheus.yml
      dashboards/dashboards.yml
    dashboards/facility-monitoring.json
```

Prometheus와 Grafana 버전은 재현성을 위해 태그를 고정했다.
MySQL 서비스와 mysql-data volume은 기존 설정을 그대로 보존했다.

## 시작하기

1. Docker Desktop을 먼저 실행하고 엔진이 준비될 때까지 기다린다.
2. 프로젝트 루트 PowerShell에서 Compose를 실행한다.

```powershell
docker compose up -d
docker compose ps
docker ps
```

`up -d`는 compose.yml의 서비스를 백그라운드에서 실행한다.
이미 실행 중이고 설정이 바뀌지 않은 MySQL은 그대로 사용한다.
최초 실행은 Prometheus/Grafana 이미지 다운로드 때문에 시간이 걸릴 수 있다.

`docker compose ps`는 이 프로젝트의 서비스 상태를 보여주며,
`docker ps`는 현재 실행 중인 모든 컨테이너를 보여준다.

3. IntelliJ에서 Gradle 프로젝트를 다시 로드하고 Java 21로 `PublicServiceApplication`을 실행한다.
   seed 프로필을 활성화하지 않는다. 기존 서버가 이미 8080에서 실행 중이면 중복 실행하지 않는다.
   데이터셋 측정 시에는 Run Configuration의 program arguments에
   `--spring.jpa.hibernate.ddl-auto=validate`를 넣으면 기존 테이블 검증만 수행한다.

또는 JAR로 실행할 수 있다.

```powershell
.\gradlew.bat build
java -jar build/libs/public-service-0.0.1-SNAPSHOT.jar --spring.jpa.hibernate.ddl-auto=validate
```

Spring Boot는 **host에서 별도로 실행**해야 한다. Compose는 Java 애플리케이션을 실행하지 않는다.
Windows host의 API가 Docker Desktop에서 들어오는 요청을 받을 수 있어야 한다.
기본 서버 바인딩을 사용하거나 `--server.address=0.0.0.0`으로 실행한다.
`127.0.0.1`에만 바인딩하면 host.docker.internal 경유 수집이 실패할 수 있다.
현재 환경에서 실제 host.docker.internal 수집 성공을 확인했다.

4. 브라우저에서 확인한다.

| 용도 | 주소 |
|---|---|
| Spring Boot health | http://localhost:8080/actuator/health |
| Prometheus 원본 지표 | http://localhost:8080/actuator/prometheus |
| meter 진단 | http://localhost:8080/actuator/metrics |
| Prometheus UI | http://localhost:9090 |
| Prometheus Targets | http://localhost:9090/targets |
| Grafana | http://localhost:3000 |
| 자동 대시보드 | http://localhost:3000/d/facility-monitoring/facility-local-monitoring |

Grafana 계정은 **admin / local-monitoring-only**다.
이는 repository에 명시한 **로컬 실험 전용 기본 계정**이며 운영/개인 credential이 아니다.
인터넷 공개용 설정으로 사용하지 않는다. Prometheus/Grafana의 host 포트는 127.0.0.1에만 공개한다.
Actuator는 별도 인증 없이 기존 API 포트에서 제공하므로 신뢰할 수 있는 로컬 개발망에서만 사용한다.

Grafana의 `Public Service` 폴더에 `Facility Local Monitoring` 대시보드가 자동으로 표시된다.
datasource UID는 `public-service-prometheus`이며 컨테이너 내부 URL은 `http://prometheus:9090`이다.
Grafana 안에서 localhost:9090을 사용하면 Grafana 컨테이너 자신을 가리키므로 올바른 연결이 아니다.

## Spring Boot 지표 설정

```properties
management.endpoints.web.exposure.include=health,prometheus,metrics
management.metrics.tags.application=${spring.application.name}
management.metrics.distribution.percentiles-histogram.http.server.requests=true
```

- endpoint를 `*`로 노출하지 않았다. env/beans/configprops는 HTTP 404로 접근 불가능함을 확인했다.
- DB 접속정보와 기존 API 구현은 그대로다.
- `/actuator/metrics/http.server.requests`에서 실제 meter 이름 `http.server.requests`와 단위 seconds를 확인했다.
- 위 설정은 HTTP request meter에만 percentile histogram을 적용한다.
- 모든 metric에 공통 `application="public-service"` 태그가 붙는다.
- Prometheus가 수집할 때 `job="public-service"`, `instance="host.docker.internal:8080"`도 붙는다.
  이 두 scrape label은 Actuator 원본 응답에 원래 들어 있는 label이 아니다.

실제 `/actuator/prometheus`에서 확인한 이름은 다음과 같다.

| 용도 | 실제 metric |
|---|---|
| HTTP 완료 요청 | http_server_requests_seconds_count / http_server_requests_seconds_sum |
| HTTP histogram | http_server_requests_seconds_bucket |
| Heap | jvm_memory_used_bytes / jvm_memory_committed_bytes / jvm_memory_max_bytes |
| CPU | process_cpu_usage / system_cpu_usage |
| Threads | jvm_threads_live_threads / jvm_threads_daemon_threads |
| JDBC | jdbc_connections_active / jdbc_connections_idle / jdbc_connections_max / jdbc_connections_min |
| Hikari | hikaricp_connections_active / hikaricp_connections_idle / hikaricp_connections_pending / hikaricp_connections_max |

HTTP label에 uri, method, status, outcome 등이 존재한다.
검색 조건 region/district/type 값은 별도 label로 추가하지 않았다.
즉 이 대시보드는 모든 `/api/facilities` 검색 요청을 같은 URI로 집계하며,
SEOUL과 GWANGJU 요청을 개별 시계열로 구분하지 않는다.
단건 조회 URI도 실제 ID 대신 `/api/facilities/{id}` 템플릿으로 집계된다.

JDBC의 label은 `name="dataSource"`, Hikari는 `pool="HikariPool-1"`로 확인했다.
풀 크기를 변경하거나 DB exporter를 추가하지 않았다.
이 지표들은 **애플리케이션 연결 풀 상태**이며 MySQL 내부 CPU, 쿼리 실행계획, 잠금 대기 자체를 측정하는 지표는 아니다.

## Dashboard 패널과 PromQL

HTTP 패널은 `uri=~"/api/facilities.*"` 조건으로 Facility API만 대상으로 한다.
Actuator scrape 요청이 RPS와 p95를 왜곡하지 않도록 제외한다.
조회 구간은 최근 5분, dashboard 새로고침은 15초다.

### 1. HTTP Request Rate (RPS)

Facility API留?吏묎퀎?⑸땲?? Actuator scrape ?붿껌? ?쒖쇅?⑸땲??

{{method}} {{uri}}:

```promql
sum by (method, uri) (rate(http_server_requests_seconds_count{job="public-service",application="public-service",uri=~"/api/facilities.*"}[5m]))
```

### 2. HTTP p95 Latency

理쒓렐 5遺꾩쓽 Facility HTTP histogram?쇰줈 怨꾩궛??洹쇱궗 p95. 紐⑤뱺 ?묐떟 ?곹깭瑜??ы븿?섎ŉ ?몃옒?쎌씠 ?놁쑝硫?媛믪씠 ?놁쓣 ???덉뒿?덈떎.

{{uri}} p95:

```promql
histogram_quantile(0.95, sum by (le, uri) (rate(http_server_requests_seconds_bucket{job="public-service",application="public-service",uri=~"/api/facilities.*"}[5m])))
```

### 3. HTTP Error Rate (4xx / 5xx)

Facility ?꾩껜 ?붿껌 以?4xx/5xx??鍮꾩쑉(%). ?붿껌???놁쑝硫?NaN?????덉뒿?덈떎.

4xx %:

```promql
100 * (sum(rate(http_server_requests_seconds_count{job="public-service",application="public-service",uri=~"/api/facilities.*",status=~"4.."}[5m])) or (0 * sum(rate(http_server_requests_seconds_count{job="public-service",application="public-service",uri=~"/api/facilities.*"}[5m])))) / sum(rate(http_server_requests_seconds_count{job="public-service",application="public-service",uri=~"/api/facilities.*"}[5m]))
```

5xx %:

```promql
100 * (sum(rate(http_server_requests_seconds_count{job="public-service",application="public-service",uri=~"/api/facilities.*",status=~"5.."}[5m])) or (0 * sum(rate(http_server_requests_seconds_count{job="public-service",application="public-service",uri=~"/api/facilities.*"}[5m])))) / sum(rate(http_server_requests_seconds_count{job="public-service",application="public-service",uri=~"/api/facilities.*"}[5m]))
```

### 4. JVM Heap Memory

heap ?곸뿭??used / committed / max ?⑷퀎?낅땲??

used:

```promql
sum(jvm_memory_used_bytes{job="public-service",application="public-service",area="heap"})
```

committed:

```promql
sum(jvm_memory_committed_bytes{job="public-service",application="public-service",area="heap"})
```

max:

```promql
sum(jvm_memory_max_bytes{job="public-service",application="public-service",area="heap"} >= 0)
```

### 5. Process / System CPU

JVM ?꾨줈?몄뒪? Windows host CPU ?ъ슜瑜? MySQL container CPU?????⑤꼸??蹂꾨룄 痢≪젙 ??곸씠 ?꾨떃?덈떎.

JVM process:

```promql
process_cpu_usage{job="public-service",application="public-service"}
```

Windows system:

```promql
system_cpu_usage{job="public-service",application="public-service"}
```

### 6. JVM Threads

JVM live / daemon thread ??

Live:

```promql
jvm_threads_live_threads{job="public-service",application="public-service"}
```

Daemon:

```promql
jvm_threads_daemon_threads{job="public-service",application="public-service"}
```

### 7. Hikari Active Connections

?ъ슜 以묒씤 DB ?곌껐 ??

{{pool}}:

```promql
hikaricp_connections_active{job="public-service",application="public-service"}
```

### 8. Hikari Idle Connections

??먯꽌 ?湲?以묒씤 DB ?곌껐 ??

{{pool}}:

```promql
hikaricp_connections_idle{job="public-service",application="public-service"}
```

### 9. Hikari Pending Connections

?곌껐???산린 ?꾪빐 湲곕떎由щ뒗 ?ㅻ젅???섏씠硫??곌껐 ???먯껜???꾨떃?덈떎.

{{pool}}:

```promql
hikaricp_connections_pending{job="public-service",application="public-service"}
```

### 10. Hikari Max Connections

??먯꽌 ?덉슜?섎뒗 理쒕? ?곌껐 ??

{{pool}}:

```promql
hikaricp_connections_max{job="public-service",application="public-service"}
```

Heap max는 JVM이 알 수 없는 크기를 -1로 보고하는 pool을 제외한다.
CPU 사용률의 원래 값은 0~1이며 Grafana percentunit 표시로 백분율로 보여준다.
Hikari pending은 연결을 기다리는 **스레드 수**이지 생성된 연결 수가 아니다.

### p95 계산 의미와 한계

`http_server_requests_seconds_bucket`은 le(초 단위 상한)별 누적 요청 수다.
`rate(...[5m])`로 bucket의 초당 증가율을 계산하고 `sum by (le, uri)`로
상태 코드·method 등의 시계열을 URI별로 합친 후,
`histogram_quantile(0.95, ...)`로 최근 5분의 근사 p95를 계산한다.
직접 계산된 클라이언트 percentile이나 max를 p95로 대신 사용하지 않는다.

- 충분한 scrape 표본이 필요하다. 시작 직후에는 최소 2번의 scrape를 기다린다.
- 요청이 없는 구간의 p95는 NaN/No data가 정상이다. 신규 시계열이 첫 scrape에만 존재해도
  아직 증가율이 잡히지 않을 수 있다. 성공/실패 요청을 더 보내고 다음 scrape를 기다린다.
- 오차는 histogram bucket 간격과 표본 수의 영향을 받는다.
- p95 패널은 4xx/5xx를 포함한 모든 완료 응답을 집계한다.
- error 패널은 4xx와 5xx를 분리한다. 트래픽은 있으나 해당 오류가 없으면 0%,
  요청 자체가 없으면 0/0으로 NaN일 수 있다.
- 향후 k6의 클라이언트 지연시간과 서버 meter p95는 측정 범위가 다르므로 동일하다고 가정하지 않는다.
- 기본 생성 bucket 범위도 실제 응답으로 확인해야 한다. 긴 요청이 상한을 넘는 상황은
  후속 실험에서 별도로 검토한다. 이번에는 histogram 경계를 임의 튜닝하지 않았다.

## 종료와 데이터 보존

```powershell
docker compose down
```

이 명령은 이 프로젝트의 컨테이너와 네트워크를 종료/제거한다.
기본적으로 named volume은 보존하므로 다음 `docker compose up -d`에서 기존 데이터를 다시 사용한다.
Spring Boot host 프로세스는 별개이므로 IntelliJ Stop 또는 실행 터미널 Ctrl+C로 종료한다.

**Facility 데이터를 유지하려면 `docker compose down -v` 또는 volume 삭제 명령을 사용하지 않는다.**
현재 `mysql-data`에 DB가 들어 있다.
`prometheus-data`에는 시계열(보관 기간 7일), `grafana-data`에는 Grafana 내부 설정/계정이 저장된다.

MySQL은 계속 사용하면서 모니터링만 멈추려면:

```powershell
docker compose stop prometheus grafana
docker compose up -d prometheus grafana
```

Grafana 계정 환경변수는 초기 DB 생성 때 사용된다.
volume에 계정이 이미 생성된 후 compose의 비밀번호만 바꿔도 기존 계정이 자동 갱신된다고 가정하지 않는다.

## 문제 확인 순서

```powershell
# Compose 문법과 컨테이너 상태
docker compose config --quiet
docker compose ps
docker ps

# 최근 로그
docker compose logs --tail 100 prometheus grafana
docker compose logs --tail 100 mysql

# Prometheus 설정 문법
docker compose exec -T prometheus promtool check config /etc/prometheus/prometheus.yml

# Windows host 애플리케이션
Invoke-RestMethod http://localhost:8080/actuator/health
Invoke-WebRequest http://localhost:8080/actuator/prometheus -UseBasicParsing
Get-NetTCPConnection -LocalPort 8080 -State Listen

# 실제 수집 상태
Invoke-RestMethod http://localhost:9090/api/v1/targets
```

- **Targets DOWN:** Spring Boot가 켜져 있는지, port 8080인지 먼저 확인한다.
  Targets의 lastError를 읽고 host.docker.internal 경로, 서버 바인딩, Windows 방화벽의
  Docker Desktop/Java 통신 허용 여부를 점검한다. 방화벽 전체를 끄지 않는다.
- **Prometheus 지표 없음:** UI에서 `up{job="public-service"}`가 1인지 확인한다.
  API를 호출하고 최대 15초 뒤 다시 조회한다.
- **Grafana No data:** 최근 15분 같은 올바른 시간 범위인지 확인한다.
  Prometheus에서 동일 PromQL이 나오는지 먼저 확인한 뒤 Grafana datasource의 연결 테스트를 확인한다.
  HTTP rate/p95는 idle 상태에서 0/NaN일 수 있지만 JVM/풀 gauge는 계속 보여야 한다.
- **Hikari active/pending이 0:** 요청이 없는 순간에는 정상이다.
  짧은 쿼리의 순간 변화는 15초 scrape 사이에 지나갈 수 있으므로 0만 보고 풀을 사용하지 않는다고 판단하지 않는다.
- **dashboard 파일 변경이 UI에 안 보임:** provisioning은 30초마다 확인한다.
  필요하면 `docker compose restart grafana`를 실행한다.
  provisioned dashboard는 JSON을 수정해 관리하며 UI 변경을 기준 파일로 간주하지 않는다.
- **Prometheus 설정 변경:** `docker compose restart prometheus`로 다시 읽힌다.
- **8080 이미 사용 중:** 현재 실행 중인 Java 프로세스가 있는지 확인한다.
  기존 서버를 멈춘 다음 IntelliJ에서 시작한다. DB나 모니터링 컨테이너를 지울 필요는 없다.

## 검증 기록 (2026-09-08)

- `/actuator/health`: HTTP 200, UP.
- `/actuator/prometheus`: HTTP 200. HTTP 요청 후 count/sum/bucket 생성 확인.
- env/beans/configprops: HTTP 404.
- Prometheus Target: `http://host.docker.internal:8080/actuator/prometheus`, UP, lastError 비어 있음.
- `up{job="public-service"}=1`과 HTTP/JVM/JDBC/Hikari Prometheus query 결과 확인.
- Grafana: 버전 13.2.1, /api/health database=ok.
- datasource health: status=OK, Successfully queried the Prometheus API.
- dashboard UID=facility-monitoring, provisioned=true, panels=10.
- JSON의 10개 패널 전체 PromQL을 실제 Prometheus API로 질의해 유효한 시계열 반환 확인.
- Facility 목록 API 6개 조건을 순차 호출했을 때 meter count가 9 → 15로 증가했다.
  totalElements는 1,000,000 / 300,000 / 40,000 / 50,000 / 30,000 / 5,000이고 각 content는 20건이었다.
- 실제 histogram_quantile query에서 목록 API p95 값이 반환됐다.
  이는 몇 건의 수동 요청에 대한 지표 연결 검증일 뿐 성능 측정 결과가 아니다.
- idle 시 Hikari active=0, idle=10, pending=0, max=10.
  JDBC active=0, idle=10, max=10도 수집됐다.
- `.\gradlew.bat build` 성공, 기존 전체 18개 테스트 통과.
  실행 환경에서 `JAVA_TOOL_OPTIONS=-Dspring.jpa.hibernate.ddl-auto=validate`를 지정해
  테스트 시에도 테이블 DDL을 검증만 하도록 했으며 코드/기존 DB 설정은 바꾸지 않았다.
  기존 통합 테스트의 임시 데이터는 트랜잭션 롤백되며 AUTO_INCREMENT는 증가할 수 있다.
- Facility 건수 1,000,000 유지. 전체 생성 컬럼의 CRC32 합계 체크값은 전후
  `2148598155341640`으로 동일했다. 이는 변경 감지 보조값이며 암호학적 무결성 증명은 아니다.
- SHOW INDEX: PRIMARY(id) 하나만 존재. Entity/Repository/API 코드, 검색 쿼리는 수정하지 않았다.
- 추가 수집 자체에는 오버헤드가 있으므로 향후 Before/After 부하 실험에서는
  monitoring 설정과 scrape 간격을 동일하게 유지한다.

## 공식 참고 문서

- [Spring Boot Metrics](https://docs.spring.io/spring-boot/reference/actuator/metrics.html):
  Prometheus registry, http.server.requests 및 percentile histogram 설정.
- [Spring Boot Endpoints](https://docs.spring.io/spring-boot/reference/actuator/endpoints.html):
  endpoint 노출 범위 설정.
- [Docker Desktop networking](https://docs.docker.com/desktop/features/networking/):
  container에서 host.docker.internal로 host 접근.
- [Prometheus configuration](https://prometheus.io/docs/prometheus/latest/configuration/configuration/):
  scrape interval, metrics_path, static targets.
- [Grafana provisioning](https://grafana.com/docs/grafana/latest/administration/provisioning/):
  datasource와 dashboard 파일 자동 등록.

공식 설정을 참고하되, 본 문서의 metric 이름과 동작 검증은 현재 Boot 4.1.1 +
Micrometer 1.17.1의 실제 endpoint 응답을 기준으로 작성했다.

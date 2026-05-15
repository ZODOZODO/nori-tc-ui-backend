---
name: ui-backend-perf
description: nori-tc-ui-backend (Spring Boot + 헥사고날 멀티모듈) 성능 개선 measurement-first 워크플로우. Actuator http.server.requests + JFR + 쿼리 EXPLAIN 으로 실측 → ui-front baseline JSON cross-reference → 변경마다 사용자 승인 → after 측정 → 효과 없으면 롤백. "측정 안 한 추정 개선" 금지.
allowed-tools: [AskUserQuestion, Read, Glob, Grep, Bash, Write, Edit]
---

# nori-tc-ui-backend Performance — Measurement-First

## WHY (이 skill 의 존재 이유)

ui-front 의 [ui-front-perf](../../../../nori-tc-ui-front/.claude/skills/ui-front-perf/SKILL.md) audit 이 식별한 P1 백엔드 후보 (예: `/api/live-map` cold 9.4초 / EqpPage sidebar 4 endpoint fan-out ~190ms / `/api/dlq/gateway` 279ms) 는 **UI cache 로 해결 불가** — 백엔드 변경이 필수다. 그러나:

- 백엔드 변경 효과는 **클라이언트 측 Playwright 측정만으로는 분리 불가**. 네트워크 / DB / Redis / Kafka / serialization 어느 layer 가 병목인지 모름.
- 같은 endpoint 가 클라이언트에선 9.4초인데 서버 측 p95 는 2초일 수도 있다 (네트워크 / TCP / Vite proxy / serialization). 양쪽 측정 cross-reference 가 필수.
- 추정 기반 변경 (예: "쿼리 N+1 일 거다 → join fetch" / "Redis 캐시 추가하면 빠를 거다") 은 효과를 측정하지 않으면 검증 불가. ui-front-perf 와 같은 함정.

본 skill 은 **server-side 측정 → 변경 → 재측정 → 효과 없으면 롤백** 사이클을 강제한다. ui-front-perf 와 대칭 구조 + 측정 데이터 cross-reference.

## 사용자 인자

- `$1` = 감사 모드 (선택). 기본 `audit-full`. 옵션:
  - `audit-full` — ui-front baseline JSON cross-reference + 서버 측 전체 Actuator metrics scan + JFR 1회 캡처
  - `audit-endpoint` — 특정 endpoint 만 (`$2` 로 endpoint path: `/api/live-map` 등). EXPLAIN + JFR 표적 분석
  - `verify` — 직전 변경의 after 측정만 (변경 직후 빠른 검증용)

`$1` 비어있으면 AskUserQuestion 으로 모드 선택받음.

## Step 0 — 사전 점검 (서버 가동 + Actuator 노출)

backend 가 가동 중이고 Actuator metrics endpoint 가 노출되어 있어야 의미 있는 측정이 가능하다.

### 0-1. 서버 가동 확인
```bash
curl -s -o /dev/null -w "backend HTTP %{http_code}\n" http://localhost:8080/actuator/health
```
- HTTP 200 / 401 = 정상.
- 비응답 / 503 = 미가동. 사용자에게 명령 안내 후 중단 (자동 실행 안 함):
  ```
  C:\workspace_nori\nori-tc-exec\exec\windows\build-trace-apps.bat ui-backend
  C:\workspace_nori\nori-tc-exec\exec\windows\run-trace-tc-ui-backend-app.bat
  ```
  → run-app skill (`run-trace-tc-ui-backend-app.bat`) 가 JFR / GC / heap dump 옵션을 자동 적용해 가동.

### 0-2. Actuator metrics 노출 점검
```bash
curl -s http://localhost:8080/actuator/metrics 2>&1 | head -20
curl -s "http://localhost:8080/actuator/metrics/http.server.requests" 2>&1 | head -40
```
- 200 + JSON 응답 = 정상.
- 404 / 401 = `management.endpoints.web.exposure.include=health,info,metrics,prometheus` 미설정. exec 측 `apps/tc-ui-backend-app/config/tc-ui-backend.local.properties` 에 추가 후 backend 재가동 필요. 사용자에게 안내.
- workspace 전체 grep 결과 (`2026-05-14` 시점) `management.*` properties 미발견 → **노출 설정 부재가 default 일 가능성 높음**. 사용자에게 첫 사용 시 확인 요청.

### 0-3. JFR 디렉터리 확인
run-trace-tc-ui-backend-app.bat 이 `C:\tc-trace\tc-ui-backend-app\jfr\*.jfr` 로 dump 한다 ([run-trace-tc-ui-backend-app.bat:103](../../../../nori-tc-exec/exec/windows/run-trace-tc-ui-backend-app.bat#L103)). 분석 도구 후보:
- JDK Mission Control (jmc.exe) — UI 분석
- `jfr summary <file>.jfr` — 텍스트 요약 (자동 분석용)
- `jfr print --events jdk.SocketRead,jdk.JavaMonitorWait,jdk.JavaErrorThrow <file>.jfr | head -200`

## Step 1 — Baseline 측정

### 1-1. ui-front baseline JSON cross-reference (입력)
ui-front 측 baseline 을 1차 근거로 사용:
```bash
ls c:/workspace_nori/nori-tc-ui-front/.claude/perf/measurements/baseline-*.json | tail -3
```
가장 최신 파일 Read → `perPage.*.cold.top5Slowest` / `perPage.*.warm.top5Slowest` 중 **`/api/*` path + ms ≥ 100ms** 항목만 추출 → 백엔드 측정 대상으로 등록.

**현 시점 ([baseline-2026-05-14T1100.json](../../../../nori-tc-ui-front/.claude/perf/measurements/baseline-2026-05-14T1100.json)) 식별된 P1 후보**:
- `/api/live-map` cold 9417ms / warm 5096ms ([baseline-2026-05-14T1100.json:478-518](../../../../nori-tc-ui-front/.claude/perf/measurements/baseline-2026-05-14T1100.json#L478-L518))
- `/api/eqp/{id}/{param-versions,manage,runtime-state,checkout-status}` afterSidebar 4 endpoint ~190ms 동시 ([baseline-2026-05-14T1100.json:91-117](../../../../nori-tc-ui-front/.claude/perf/measurements/baseline-2026-05-14T1100.json#L91-L117))
- `/api/dlq/gateway` cold 279ms / warm 184ms ([baseline-2026-05-14T1100.json:374-432](../../../../nori-tc-ui-front/.claude/perf/measurements/baseline-2026-05-14T1100.json#L374-L432))

### 1-2. 서버 측 Actuator metrics 캡처
Actuator http.server.requests 의 p95/p99 를 endpoint 별로 캡처:
```bash
TS=$(date -u +%Y-%m-%dT%H%M)
mkdir -p .claude/perf/measurements
OUT=".claude/perf/measurements/backend-baseline-${TS}.json"
curl -s "http://localhost:8080/actuator/metrics/http.server.requests?tag=uri:/api/live-map" > "${OUT}.live-map.json"
curl -s "http://localhost:8080/actuator/metrics/http.server.requests?tag=uri:/api/dlq/gateway" > "${OUT}.dlq-gateway.json"
curl -s "http://localhost:8080/actuator/metrics/http.server.requests?tag=uri:/api/eqp/{id}/manage" > "${OUT}.eqp-manage.json"
ls -la .claude/perf/measurements/backend-baseline-${TS}* | tail -10
```
저장 파일 경로를 사용자에게 명시. **출력 마지막 5~10줄 코드블록 인용 필수**.

> NOTE: Spring Boot Actuator 는 path variable 을 `{id}` 로 normalize 한다 (URI template) — `/api/eqp/CMP_001/manage` 와 `/api/eqp/CMP_002/manage` 는 같은 tag 로 집계된다.

대안: `/actuator/prometheus` 노출되어 있으면 한 번에 dump:
```bash
curl -s http://localhost:8080/actuator/prometheus | grep -E "^http_server_requests_seconds.*quantile" | head -40
```

### 1-3. JFR 캡처 (느린 endpoint 가 실행되는 동안)
1. ui-front 에서 측정 대상 페이지 진입 (예: LiveMapPage) — Playwright 또는 수동
2. JFR 은 이미 dumponexit 옵션으로 가동 중이므로 별 명령 불필요. 종료 시 자동 dump.
3. 종료 안 하고 in-flight dump 원하면:
   ```bash
   jcmd <PID> JFR.dump filename=C:\tc-trace\tc-ui-backend-app\jfr\manual-${TS}.jfr
   ```
   ※ `jcmd` 는 JDK 에 포함 (`%JAVA_HOME%\bin\jcmd.exe`). PID 는 `jcmd -l | grep tc-ui-backend-app`.

### 1-4. 쿼리 EXPLAIN / DB 측정
LiveMap 9.4초 같은 응답 시간이 DB 쿼리 때문인지 확인:
1. 해당 endpoint 의 service / repository 진입점 찾기 → 실제 쿼리 추출
   - LiveMap: [LiveMapController.java:46](ui/adapter/tc-ui-web-adapter/src/main/java/com/nori/tc/ui/adapters/web/controller/LiveMapController.java#L46) → `LiveMapQueryService.getEquipmentViews()` → 호출 chain 추적
   - DLQ: [DlqController.java](ui/adapter/tc-ui-web-adapter/src/main/java/com/nori/tc/ui/adapters/web/controller/DlqController.java)
   - EQP detail: [EqpController.java](ui/adapter/tc-ui-web-adapter/src/main/java/com/nori/tc/ui/adapters/web/controller/EqpController.java)
2. SQL 로깅 활성화 (이미 켜져 있을 가능성 — `logging.level.org.hibernate.SQL=DEBUG`):
   ```bash
   grep -rn "logging.level.org.hibernate" c:/workspace_nori/nori-tc-exec/apps/tc-ui-backend-app/config/ c:/workspace_nori/nori-tc-exec/config/
   ```
3. 콘솔 trace log (`C:\tc-trace\tc-ui-backend-app\console\*-stdout.log`) 에서 endpoint 호출 직후 SQL 캡처
4. 후보 쿼리에 `EXPLAIN ANALYZE` 적용 → seq scan / index miss / N+1 식별

### 1-5. 정적 검사 (보조 신호)
다음 항목을 Grep 으로 카운트:
- `@Transactional(readOnly = true)` 미적용 read endpoint — `LiveMapQueryService` 등 query 클래스
- `@Cacheable` / `@CachePut` / Spring Cache 사용 여부 — read-heavy endpoint 에 캐시 부재
- `JOIN FETCH` / `EntityGraph` 사용 — N+1 위험 indicator
- `Page<T>` vs `List<T>` — 페이지네이션 누락된 list endpoint
- `WebClient` / `RestTemplate` 동기 호출 + 외부 시스템 — DemoTesterClient 같은 외부 의존

정적 검사 결과는 보조 신호. **변경 트리거가 아님** — 서버 metrics + JFR + 쿼리 EXPLAIN 이 우선.

## Step 2 — 측정 결과 기반 후보 식별

baseline JSON + JFR + EXPLAIN 결과를 합쳐 다음 우선순위로 분류:

### P1 (최우선 — 실측 병목, 백엔드 변경 필수)
- **응답 시간 > 500ms** endpoint (서버 측 p95 기준)
  - JFR / EXPLAIN 으로 layer 식별 (DB / 외부 호출 / serialization / lock contention)
  - 대표 변경 패턴:
    - DB: 인덱스 추가 / JOIN FETCH / 쿼리 재작성 / 캐싱
    - 외부 호출 (DemoTesterClient 등): 타임아웃 단축 / 비동기화 / 캐싱
    - serialization: response DTO 슬림화 / projection
- **100~500ms 의 fan-out** (같은 페이지에서 4+ endpoint 동시 호출)
  - 대표 변경: batch endpoint 통합 (예: `GET /api/eqp/{id}/detail-bundle` ← `param-versions + manage + runtime-state + checkout-status`)
  - **EqpController 에 이미 `EqpManageDetailBatchResponse` 가 존재** ([EqpController.java:13](ui/adapter/tc-ui-web-adapter/src/main/java/com/nori/tc/ui/adapters/web/controller/EqpController.java#L13)) — batch 응답 DTO 의 선재 기반. 신규 endpoint 도입 비용 낮음.

### P2 (백엔드 변경 가능 + 측정 검증 가능)
- **단일 endpoint 중간 응답 시간** (100~500ms 단독)
  - 캐싱 후보 (예: `/api/dlq/gateway` cold 279ms / warm 184ms — 차이가 95ms 뿐. warm 도 느림 → 캐시 효과 약하거나 미적용. 쿼리 자체 최적화 우선)
- **외부 시스템 호출 동기 → 비동기 전환**
  - `DemoTesterClient.getJobs()` 같은 호출이 main thread block 하는 경우
- **`@Transactional(readOnly = true)` 누락** + 읽기 endpoint
  - 측정 가능: read 트랜잭션은 일부 DB 에서 flush mode 차이로 응답 시간 단축

### P3 (정적 신호 — 효과 측정 어려움 / 적용 신중)
- Spring Cache 무차별 추가 — invalidation 비용 / consistency 문제. 명시적 요구 시만.
- ThreadPool / Executor 튜닝 — 실제 thread starvation 측정 (JFR `jdk.JavaThreadStatistics`) 후만.
- HTTP/2 / gzip 활성화 — 응답 크기 측정 후 적용.

### 보고서 작성
`.claude/perf/<YYYY-MM-DD>-<mode>-perf.md` 에 다음 구조로 기록:
- ui-front baseline JSON cross-reference 결과 (P1 후보 endpoint + ms)
- 서버 측 Actuator metrics baseline (출력 마지막 5~10줄 인용)
- JFR / EXPLAIN 결과 layer 식별
- P1/P2/P3 분류 + file:line + 변경 패턴 + 측정 방법
- ui-front-perf 와의 협업 SPEC 후보 (예: [p1-3-polling-consolidation.md](../../../../nori-tc-ui-front/.claude/specs/p1-3-polling-consolidation.md) R2)

## Step 3 — 변경 후보별 사용자 승인 cycle

**한 번에 한 변경만**. compound 변경 금지 (효과 분리 불가).

각 후보에 대해 AskUserQuestion 으로 승인 받기:
```
질문: "<file:line> 의 <변경 내용> 적용할까요?"
옵션 A (권장): 적용 → after 측정 → 효과 확인 후 commit
옵션 B: 건너뛰기 — 다음 후보로
```

질문 본문에 포함:
- file:line
- 변경 코드 미리보기 (before / after)
- 예상 효과 + 측정 방법 (어떤 metric 이 어떻게 변하면 효과 있다고 판단하는지)
- 위험 (caller 변경 동반 / API 호환성 깨짐 / @Import 추가 필요 등)

예시:
> EqpController:80~120 에 `@GetMapping("/{id}/detail-bundle")` 신규 endpoint 추가할까요?
> 이유: ui-front 측 baseline 에서 sidebar click 후 4 endpoint 동시 ~190ms. 백엔드에서 1 query 로 묶으면 RTT 1 회로 감소.
> 변경: EqpController + EqpManageDetailBatchResponse (선재) + EqpManagementService.getDetailBundle() 신규 메서드.
> 위험: ui-front 의 useEqpManageDetailQuery 4건 호출처 마이그레이션 필요 (별 SPEC).
> 측정 가능: ✓ (Actuator http.server.requests p95 / Playwright Network)

## Step 4 — 변경 적용 + 빌드 검증

1. **Edit** 으로 적용. 신규 controller method / service method 는 Write 또는 Edit.
2. 새 `@RestController` / `@Service` / `@Component` / `@Repository` 추가 시 같은 모듈의 `*ModuleConfiguration` 의 `import` + `@Import` 두 줄을 반드시 추가 — 누락 시 컴파일 성공·런타임 실패 (`NoResourceFoundException`).
   - 점검: `validate-module-imports` skill 호출 권장.
3. 빌드:
   ```bash
   C:\workspace_nori\nori-tc-exec\exec\windows\build-trace-apps.bat ui-backend 2>&1 | tail -25
   ```
   또는 build-trace skill 호출.
4. **마지막 5~10줄 코드블록 인용 필수**. PASS / FAIL 명시.
5. 단위 테스트:
   ```bash
   ./gradlew :ui:adapter:tc-ui-web-adapter:test 2>&1 | tail -25
   ./gradlew :ui:tc-ui-core:test 2>&1 | tail -25
   ```

빌드 / 테스트 FAIL 시:
- 자동 롤백 (Edit 으로 원복) + 사용자 보고
- 또는 사용자에게 fix 진행 옵션 제시

## Step 5 — After 측정

baseline 과 동일 조건으로 재측정.

### 5-1. 서버 측 재측정
backend 재가동:
```
run-trace-tc-ui-backend-app.bat
```
warmup (5~10 회 요청 후) — JIT compile 완료 대기:
```bash
for i in 1 2 3 4 5; do curl -s -o /dev/null -w "%{time_total}\n" -H "Cookie: $COOKIE" "http://localhost:8080/api/live-map"; done
```
Actuator metrics dump:
```bash
TS=$(date -u +%Y-%m-%dT%H%M)
curl -s "http://localhost:8080/actuator/metrics/http.server.requests?tag=uri:/api/live-map" > .claude/perf/measurements/backend-after-${TS}.live-map.json
```

### 5-2. ui-front 측 재측정 (cross-reference)
ui-front 레포에서 `ui-front-perf verify` 실행 (사용자에게 안내 — 같은 세션에서 자동 실행 안 함, ui-front 의 dev server 가동 필요):
```bash
cd c:/workspace_nori/nori-tc-ui-front
TS=$(date -u +%Y-%m-%dT%H%M)
OUTPUT_PATH=".claude/perf/measurements/after-${TS}.json" \
  node tests/e2e/_perf_measure.mjs 2>&1 | tail -10
```

특정 page 만 영향이면 `PAGES=LiveMapPage` 등 필터 (빠른 측정).

## Step 6 — diff 비교 + 효과 평가

backend-baseline vs backend-after + (선택) ui-front baseline vs after 비교:
- **서버 측 응답 시간 변화**: 같은 endpoint 의 p95 / p99 / mean 차이 (절대 + %)
- **DB 쿼리 시간 변화**: 콘솔 로그 / EXPLAIN 결과 비교
- **클라이언트 측 응답 시간 변화**: 같은 endpoint 의 cold / warm ms 차이

효과 판정:
- **개선 명확** (서버 p95 -20%+ / 클라이언트 ms -20%+): commit
- **효과 미미** (변화 ±5% 내): 사용자에게 판단 요청 — 의도 명확하면 유지, 아니면 롤백
- **악화** (응답 시간 ↑ / 에러율 ↑ / 다른 endpoint 회귀): **자동 롤백 권장** + 사용자 보고
- **클라이언트와 서버 측 측정 불일치** (서버는 빨라졌는데 클라이언트는 그대로): 네트워크 / proxy / serialization layer 의심 — 추가 조사 후 결정
- **측정 불가** (정적 신호 항목): 사용자 명시적 동의로 진행, "효과 측정 안 됨" 보고서에 기록

판정 결과를 보고서 + commit message 에 인용.

## Step 7 — 반복 / 종료

- 다음 후보 있음 → Step 3 으로
- 다음 후보 없음 → 종료 보고:
  - 적용한 변경 목록 (commit hash + before/after diff)
  - ui-front 협업 SPEC 후보 (별 PR 페어 필요)
  - 측정 불가 항목 (이번 sprint 미적용)
  - 다음 측정 시점 권장 (운영 배포 후 등)

## 산출물

- **backend baseline / after JSON**: `.claude/perf/measurements/backend-<phase>-<ISO>.*.json`
- **JFR**: `C:\tc-trace\tc-ui-backend-app\jfr\*.jfr` (run-app 이 자동 생성, gitignored)
- **마스터 보고서**: `.claude/perf/<YYYY-MM-DD>-<mode>-perf.md` (체크리스트 + 결정 로그)
- **ui-front 협업 SPEC**: 별 markdown — `nori-tc-ui-front/.claude/specs/<name>.md` 와 paired

## 절대 금지

- **측정 없이 변경 적용 금지** — Step 1 baseline 없이 Step 4 진입 금지
- **compound 변경 금지** — 한 번에 한 변경. 효과 분리 불가능해짐
- **추정 효과 보고서 작성 금지** — "N% 감소 예상" 같은 추정값 인용 시 반드시 measurement source 명시. 없으면 "측정 필요" 로만 표기
- **클라이언트 측 baseline 없이 백엔드 단독 최적화 금지** — endpoint 가 실제로 클라이언트에서 어떻게 쓰이는지 모르고 최적화하면 fan-out / 호출 빈도 / cache 정책과 무관한 변경이 됨. ui-front baseline JSON cross-reference 가 1차 근거.
- **운영 데이터 가정 금지** — local 측정의 row 수가 1~10 인데 운영은 1000+ 일 수 있다. local 측정값은 absolute 가 아닌 *상대 비교용*. 운영 측정은 별 단계 (Grafana / 운영 Actuator).
- **ComponentScan 의존 금지** — 새 컴포넌트는 같은 모듈의 `*ModuleConfiguration` 의 `@Import` 에 명시 등록 ([CLAUDE.md MUST NOT](../../../../nori-tc-common/CLAUDE.md))

## 검증 규칙 (글로벌 #1 VERIFY BEFORE CLAIMING 준수)

- 모든 빌드 / 측정 실행 시 **출력 마지막 5~10줄 코드블록 인용 필수**
- PASS / FAIL 명시
- 측정값은 file:line 또는 JSON path / metric URI 출처 명시
- "개선됐다" 주장 전 반드시 before/after diff 출력 인용
- 부재 단언 전 워크스페이스 전체 (`c:\workspace_nori\*`) grep — 동일 패턴이 다른 nori-tc-* 레포에 있는지 먼저 확인

## 컨벤션 참조

- 모듈 구조: [settings.gradle.kts](settings.gradle.kts) — base / adapter / starter 3 layer
- 헥사고날 + `@Import` 명시 등록: [nori-tc-common/CLAUDE.md](../../../../nori-tc-common/CLAUDE.md)
- Actuator 의존: [ui/starter/tc-ui-backend-starter/build.gradle.kts:48-49](ui/starter/tc-ui-backend-starter/build.gradle.kts#L48-L49), [ui/adapter/tc-ui-web-adapter/build.gradle.kts:16](ui/adapter/tc-ui-web-adapter/build.gradle.kts#L16)
- Micrometer / Prometheus 의존: [gradle/libs.versions.toml:152-153](gradle/libs.versions.toml#L152-L153)
- 빌드 / 가동: `nori-tc-exec/exec/windows/build-trace-apps.bat ui-backend` + `run-trace-tc-ui-backend-app.bat`
- JFR / GC / heap dump 디렉터리: `C:\tc-trace\tc-ui-backend-app\{jfr,gc,heap,console}` ([run-trace-tc-ui-backend-app.bat:22-26](../../../../nori-tc-exec/exec/windows/run-trace-tc-ui-backend-app.bat#L22-L26))
- 협업 대상 skill: [ui-front-perf](../../../../nori-tc-ui-front/.claude/skills/ui-front-perf/SKILL.md)
- 협업 SPEC 예: [p1-3-polling-consolidation.md R2](../../../../nori-tc-ui-front/.claude/specs/p1-3-polling-consolidation.md) (Deploy Monitor `/deploys/{deployKey}/monitor` batch endpoint)

## 측정 가능 / 불가 매트릭스

| 변경 유형 | 측정 가능 | 측정 방법 |
|---|---|---|
| 쿼리 인덱스 추가 / 재작성 | ✅ | EXPLAIN ANALYZE before/after + Actuator p95 |
| JOIN FETCH / EntityGraph | ✅ | SQL 콘솔 로그 쿼리 수 + Actuator p95 |
| batch endpoint 통합 | ✅ | Actuator + Playwright Network (RTT 수) |
| Spring Cache 추가 | ✅ | Actuator warm p95 + cache hit ratio (`/actuator/metrics/cache.gets`) |
| `@Transactional(readOnly=true)` | ⚠️ | 일부 DB 에서만 측정 가능. flush mode 차이 크지 않으면 측정 불가 |
| 외부 호출 비동기화 | ✅ | thread block 시간 (JFR `jdk.JavaMonitorWait`) + Actuator p95 |
| response DTO 슬림화 | ✅ | 응답 크기 (curl `%{size_download}`) + Actuator p95 |
| ThreadPool 튜닝 | ⚠️ | JFR thread starvation 측정 어렵다. 명시적 부하 테스트 필요 |
| HTTP/2 / gzip | ✅ | 응답 크기 + 응답 시간 (특히 LiveMap 같은 큰 응답) |
| Hibernate 2nd level cache | ⚠️ | invalidation / consistency 위험 크다. 측정 가능하지만 운영 위험. |

## 같이 쓰는 skill

- [build-trace](../../../../../.claude/skills/build-trace/SKILL.md) — common publishToMavenLocal + bootJar 빌드. Step 4 빌드 검증에 사용.
- [run-app](../../../../../.claude/skills/run-app/SKILL.md) — backend 가동 (JFR 자동). Step 0 / Step 5 재가동에 사용.
- [validate-module-imports](../../../../../.claude/skills/validate-module-imports/SKILL.md) — 새 컴포넌트 추가 시 ModuleConfiguration @Import 누락 점검. Step 4 직후 권장.
- [ui-front-perf](../../../../nori-tc-ui-front/.claude/skills/ui-front-perf/SKILL.md) — 대칭 클라이언트 측 skill. baseline JSON 의 출처. cross-reference 필수.

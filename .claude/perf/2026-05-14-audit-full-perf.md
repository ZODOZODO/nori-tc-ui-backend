# 2026-05-14 audit-full — ui-backend Performance Audit

> 작성 시점: 2026-05-14 (skill ui-backend-perf, audit-full 모드)
> 입력 baseline: [nori-tc-ui-front/.claude/perf/measurements/baseline-2026-05-14T1100.json](../../../nori-tc-ui-front/.claude/perf/measurements/baseline-2026-05-14T1100.json)
> 상태: **v2 — Step 1-1 ~ Step 1-4 측정 완료. Step 3 (변경 cycle) 진입 대기.**
> 측정 산출물: [backend-baseline-2026-05-14T0502.prometheus.txt](measurements/backend-baseline-2026-05-14T0502.prometheus.txt), [manual-perf-baseline-2026-05-14T0504.jfr](file:///C:/tc-trace/tc-ui-backend-app/jfr/manual-perf-baseline-2026-05-14T0504.jfr)

## Step 0 결과 — 사전 점검

| 항목 | 결과 | 출처 |
|---|---|---|
| `/actuator/health` (base path 없음) | HTTP 401 | curl `http://localhost:8080/actuator/health` |
| `/api/actuator/health` (api prefix) | HTTP 200 (public) | curl + [UiSecurityConfig.java:125](../../ui/adapter/tc-ui-web-adapter/src/main/java/com/nori/tc/ui/adapters/web/security/UiSecurityConfig.java#L125) |
| `/api/actuator/metrics` (인증 후) | HTTP 403 | curl + admin/ChangeMe123! 로그인 후 |
| `/api/actuator/metrics` DB permission seed | **워크스페이스 grep 결과 미발견** | Grep `/api/actuator/metrics` across `c:\workspace_nori` |
| JFR 디렉터리 | `C:\tc-trace\tc-ui-backend-app\jfr\` 존재, 기존 dump 10+ 파일 | ls 결과 |
| Actuator base-path | `/api/actuator` (api prefix unification 적용됨) | [05-api-prefix-unification-build-plan.md:60](../../../nori-tc-exec/apps/tc-ui-backend-app/docs/tasks/05-api-prefix-unification-build-plan.md#L60) |

**결론**: backend 가동 중. Actuator 는 노출되어 있으나 `/health` 외 모든 metric endpoint 가 DB 기반 URL 권한 closed-by-default 에 막혀 admin 도 403. 운영 시점 권한 seed 부재가 default — 측정용 우회 필요 (사용자 옵션 A 선택: UiSecurityConfig permitAll 임시 추가 + 측정 후 revert).

## Step 1-1 결과 — ui-front baseline JSON cross-reference

`/api/*` path + ms ≥ 100ms 항목만 추출 (Vite asset `/src/...` 제외).

| Page | Phase | Path | ms | 비고 |
|---|---|---|---|---|
| **LiveMapPage** | **cold** | **`/api/live-map`** | **9417** | **P1 #1 (외톨이 outlier)** |
| **LiveMapPage** | **warm** | **`/api/live-map`** | **5096** | warm 도 5초 — 캐시 부재 + 무거운 쿼리 패턴 |
| EqpPage | afterSidebar | `/api/eqp/CMP_001/param-versions` | 190 | **P1 #2 (4-fan-out)** |
| EqpPage | afterSidebar | `/api/eqp/CMP_001/manage` | 190 | P1 #2 |
| EqpPage | afterSidebar | `/api/eqp/CMP_001/runtime-state` | 189 | P1 #2 |
| EqpPage | afterSidebar | `/api/eqp/CMP_001/checkout-status` | 189 | P1 #2 |
| **DlqPage** | **cold** | **`/api/dlq/gateway`** | **279** | **P1 #3** |
| DlqPage | warm | `/api/dlq/gateway` | 184 | warm 도 빠르지 않음 — 캐시 효과 약함 |
| EqpPage | cold | `/api/model?(...)` | 153 | P2 (페이지네이션/필터 확인 필요) |
| EqpPage | cold | `/api/auth/me` | 153 | P3 (cold start — 첫 호출 분리 어려움) |
| EqpPage | warm | `/api/model?(...)` | 164 | P2 |
| LiveMapPage | cold | `/api/auth/me` | 103 | P3 |

## Step 1-5 결과 — 코드 정적 분석 (controller / service)

### P1 #1 `/api/live-map` (cold 9417ms / warm 5096ms)

[LiveMapController.java:45-50](../../ui/adapter/tc-ui-web-adapter/src/main/java/com/nori/tc/ui/adapters/web/controller/LiveMapController.java#L45-L50) → [LiveMapQueryService.getEquipmentViews()](../../ui/tc-ui-core/src/main/java/com/nori/tc/ui/core/service/LiveMapQueryService.java#L55-L63)

**N+1 패턴 식별** ([LiveMapQueryService.java:55-99](../../ui/tc-ui-core/src/main/java/com/nori/tc/ui/core/service/LiveMapQueryService.java#L55-L99)):
1. `eqpQueryPort.findAll(0, 1000)` — 1 query (모든 enabled EQP 까지 1000 건)
2. per-EQP loop (`map(this::toEquipmentView)`):
   - `eqpManageQueryPort.findManageSnapshotByEqpId(eqp.eqpId())` — N queries (snapshot 안에 params / portStatuses 포함, sub-query 있을 듯)
   - `eqpQueryPort.findRuntimeStateByEqpId(eqp.eqpId())` — N queries
   - `modelDetailQueryPort.findParamsByModelVersionKey(...)` — N queries (조건부 — eqp param 에서 못 찾았을 때)

총 **1 + (2~3)N queries**. EQP 100 개면 ~201~301 queries. 9.4초의 강력한 후보.

추가 가정: warm 도 5초 — DB connection pool / index miss 가능성. EXPLAIN 으로 확인 필요.

**변경 후보** (Step 2 / Step 3 에서 사용자 승인):
- 단일 batch JOIN 쿼리 `findLiveMapView(limit)` — eqp + manage_snapshot + runtime_state + model_param 한 번에
- 또는 eqp 목록 + 위 sub-항목들을 IN-clause 로 묶어 3 queries 총합 (1 + 1 + 1) 으로 축소
- LiveMap 응답 자체에 Spring Cache (TTL 짧게 — 5~10s) — invalidation 부담 적음 (read-mostly 모니터링 페이지)

### P1 #2 EqpPage sidebar 4 endpoint fan-out (~190ms each)

[EqpController.java](../../ui/adapter/tc-ui-web-adapter/src/main/java/com/nori/tc/ui/adapters/web/controller/EqpController.java):
- `/{eqpId}/manage` (line 138-149) → `eqpManagementService.getManageDetail()` → 1 query (`findManageSnapshotByEqpId`)
- `/{eqpId}/param-versions` (line 213-224) → `findByEqpId` + `findParamVersionsByEqpId` (2 queries)
- `/{eqpId}/runtime-state` (line 232-248) → `findRuntimeStateByEqpId` (1 query)
- `/{eqpId}/checkout-status` — 별 controller (탐색 필요)

**중요한 선재 자산**:
- [EqpController.java:165 `/manage-batch?eqpIds=...`](../../ui/adapter/tc-ui-web-adapter/src/main/java/com/nori/tc/ui/adapters/web/controller/EqpController.java#L165) — *multi-EQP* batch endpoint 가 이미 존재 (만 단일 EQP 의 4 endpoint fan-out 문제는 별)
- [EqpManageDetailBatchResponse](../../ui/adapter/tc-ui-web-adapter/src/main/java/com/nori/tc/ui/adapters/web/dto/response/EqpManageDetailBatchResponse.java) DTO 도 선재 — 새 detail-bundle DTO 작성 시 패턴 참고

**변경 후보**:
- 신규 `GET /api/eqp/{eqpId}/detail-bundle` — manage + param-versions + runtime-state + checkout-status 한 응답으로 묶음
- ui-front 측 4 useQuery → 1 useQuery 로 통합 (별 SPEC, 함께 페어 PR)
- 기대 효과: RTT 4 → 1, 서버 측 query 수 감소 (`findByEqpId` 중복 제거 가능)

### P1 #3 `/api/dlq/gateway` (cold 279ms / warm 184ms)

[DlqController.java:81-89](../../ui/adapter/tc-ui-web-adapter/src/main/java/com/nori/tc/ui/adapters/web/controller/DlqController.java#L81-L89) → `gatewayDlqPort.listAllDlq()`

**Redis SCAN 전체 키 + 역직렬화 패턴** (DlqController.java:76-77 주석: "SCAN 커서 방식으로 조회"). 

warm 184ms 가 의미: Redis SCAN 자체가 매 호출마다 새 cursor — 캐시 효과 거의 없음. cold 와 warm 차이 95ms 는 JIT / class-load 정도.

**변경 후보**:
- Spring Cache (TTL 1~5s) — DLQ 는 실시간 polling 가 아니라 운영자 수동 조회 → 짧은 TTL 가능
- 또는 listAllDlq → JSON 직렬화 path 측정 (Jackson 시간 vs Redis 네트워크 시간 분리)
- 변경 전 JFR `jdk.SocketRead` 이벤트로 Redis I/O 시간 측정 필수

### 정적 카운트 결과

`@Transactional|@Cacheable|@CachePut|JOIN FETCH|EntityGraph` 워크스페이스 전체 grep:

| Pattern | 위치 | 카운트 |
|---|---|---|
| 전체 | `c:/workspace_nori/nori-tc-ui-backend/ui` 35 occurrences across 8 files | - |
| `@Cacheable` / `@CachePut` 사용 사례 | **0 (read-mostly endpoint 캐시 부재)** | Grep 결과 |
| JPA adapter 위주 분포 | `JpaUserCrudPort`, `JpaModelManagementPort`, `JpaModelDetailCommandPort`, `JpaEqpParamCommandPort`, `JpaEqpCrudPort`, `DemoJobJdbcCleanupPort`, `DeployJdbcStorePort`, `PermissionAdminFacade` | - |

→ Spring Cache 미사용. P1/P2 후보 모두에 cache 도입 가능성 있음. 단, invalidation 정책 명시 필수.

## Step 1-2 ~ 1-4 결과 — 실측 데이터

### 1-2a. cold/warm 단일 호출 측정 (admin 인증, 재가동 직후)

```
=== COLD (first call, post-restart) ===
200 11.019629s 33253B /api/live-map
200 0.188334s 1628B /api/eqp/CMP_001/manage
200 0.045555s 74B /api/eqp/CMP_001/param-versions
200 0.042781s 129B /api/eqp/CMP_001/runtime-state
200 0.033004s 97B /api/eqp/CMP_001/checkout-status
200 0.268701s 59B /api/dlq/gateway
200 0.031626s 59B /api/dlq/business
200 0.019198s 59B /api/dlq/quarantine
200 0.022957s 347B /api/dlq/repair-task

=== WARM (after 5 warmup rounds) ===
200 5.133967s 33253B /api/live-map
200 0.107595s 1628B /api/eqp/CMP_001/manage
200 0.071670s 74B /api/eqp/CMP_001/param-versions
200 0.036085s 129B /api/eqp/CMP_001/runtime-state
200 0.041486s 97B /api/eqp/CMP_001/checkout-status
200 0.168355s 59B /api/dlq/gateway
200 0.017319s 59B /api/dlq/business
200 0.013617s 59B /api/dlq/quarantine
200 0.010812s 347B /api/dlq/repair-task
```

### 1-2b. Actuator Prometheus `http_server_requests_seconds` (n=7, baseline 캡처)

산출물: [backend-baseline-2026-05-14T0502.prometheus.txt](measurements/backend-baseline-2026-05-14T0502.prometheus.txt)

| URI | count | sum (s) | mean (s) | max (s) | size (B) |
|---|---|---|---|---|---|
| **`/api/live-map`** | 7 | 43.7152 | **6.245** | **11.017** | 33,253 |
| **`/api/dlq/gateway`** | 7 | 1.2253 | **0.175** | **0.267** | 59 |
| `/api/eqp/{eqpId}/manage` | 7 | 0.7162 | 0.102 | 0.186 | 1,628 |
| `/api/eqp/{eqpId}/param-versions` | 7 | 0.2803 | 0.040 | 0.058 | 74 |
| `/api/eqp/{eqpId}/runtime-state` | 7 | 0.2732 | 0.039 | 0.057 | 129 |
| `/api/eqp/{eqpId}/checkout-status` | 7 | 0.1819 | 0.026 | 0.039 | 97 |

**ui-front baseline 과의 일치**:
- live-map warm baseline 5096ms vs server-side warm 5134ms → **거의 일치 (네트워크 latency 미미)**
- dlq/gateway baseline cold 279ms vs server-side cold 268ms → **일치**
- eqp 4 endpoint baseline afterSidebar 각 ~190ms vs server-side single warm: manage 108 + param-versions 72 + runtime-state 36 + checkout-status 41 = **257ms 순차합 / 4 동시 ~190ms 대기 시간** = DB connection pool 경합 또는 직렬화 영향 추정

### 1-3. JFR summary (in-flight dump during live-map 호출)

산출물: `C:\tc-trace\tc-ui-backend-app\jfr\manual-perf-baseline-2026-05-14T0504.jfr` (7.5MB)

```
jdk.ThreadSleep            3496       70385
jdk.ThreadPark             2346       92218
jdk.JavaMonitorWait         778       21170
jdk.SocketRead              348       18267
jdk.FileRead                  6         224
jdk.JavaMonitorEnter          5         125
```

- **SocketRead 348** — DB / Redis I/O 이벤트 다수. live-map 호출 1회 중 발생. N+1 쿼리 패턴의 직접 증거 (single endpoint call → 수백 socket reads).
- ThreadPark 2346 / MonitorWait 778 — connection pool 대기 / 락 경합 가능성. 추가 분석은 `jfr print --events jdk.JavaMonitorWait` 로 가능.

### 1-4. SQL/JPA 콘솔 로그 분석 (live-map 단독 호출)

`C:\tc-trace\tc-ui-backend-app\console\tc-ui-backend-app-20260514-135833-stdout.log` 의 live-map 1 호출 (14:03:33 ~ 14:03:38, **~5초**) 동안 발생 라인 = 114줄.

**중대 발견 — modelVersionKey N+1**:
```
2026-05-14 14:03:33.951 DEBUG 설비 목록 조회 시작. offset=0, limit=1000  [JpaEqpQueryPort]
2026-05-14 14:03:33.967 DEBUG 설비 목록 조회 완료. pageSize=54, totalCount=54
2026-05-14 14:03:33.982 DEBUG tc_model JPA findByModelVersionKey 시작. modelVersionKey=61
2026-05-14 14:03:33.988 DEBUG tc_model JPA findByModelVersionKey 완료. modelVersionKey=61
2026-05-14 14:03:34.077 DEBUG tc_model JPA findByModelVersionKey 시작. modelVersionKey=61
...
(같은 modelVersionKey=61 으로 반복 호출, 매번 5~15ms, 끝까지 14:03:38.876 까지)
```

- **54 EQP 페이지 사이즈** ([baseline test env]) — 모두 같은 `modelVersionKey=61` 으로 추정
- 같은 키 반복 호출이 `TcModelJpaQuerySupport.findByModelVersionKey` 에서 발생. **JPA L1/L2 cache 미적용 또는 transaction boundary 매번 새로 열림**
- 호출당 ~5~15ms (직렬). 54×~10ms = **~540ms+** (PROCESS_TYPE) + **~540ms+** (WAFER_MODE 가 EQP 에 없을 때 fallback) = **최대 1080ms** 만 model 조회로 소비
- 잔여 ~4초 = manage_snapshot 54회 + runtime_state 54회 + serialization 등

**LiveMapQueryService.java:140-147** 의 `resolveModelParamValue()` 가 매 EQP `toEquipmentView()` 마다 호출 → 같은 modelVersionKey 의 중복 호출. **간단한 in-method `Map<Long, List<UiModelParam>>` memoize 로 즉시 해결 가능.**

## 측정 가능 매트릭스 검증 (skill SKILL.md 부합)

| 변경 유형 | 측정 가능 | 측정 방법 | 현재 상태 |
|---|---|---|---|
| live-map model param memoize | ✅ | live-map mean/max + SQL 로그 라인 수 + JFR SocketRead 카운트 | baseline 캡처 완료 |
| live-map manage_snapshot batch | ✅ | 동일 | baseline 캡처 완료 |
| dlq Spring Cache | ✅ | dlq mean + Redis MONITOR | baseline 캡처 완료 |
| eqp detail-bundle 신규 endpoint | ✅ | bundle vs 4-fan-out 동시 mean | baseline 캡처 완료 (4 단독) |

## Step 2 v2 — 측정 후 재정렬된 우선순위

### P1 (실측 ≥ 500ms 또는 명백한 낭비 패턴)

**P1-A** — `/api/live-map` modelVersionKey N+1 캐싱
- 실측: cold 11.02s / warm 5.13s / mean 6.25s, 33KB
- 원인: [LiveMapQueryService.java:140-147](../../ui/tc-ui-core/src/main/java/com/nori/tc/ui/core/service/LiveMapQueryService.java#L140-L147) 의 `resolveModelParamValue()` 가 매 EQP 마다 같은 `modelVersionKey` 로 `modelDetailQueryPort.findParamsByModelVersionKey()` 중복 호출. SQL 로그에서 동일 modelVersionKey=61 가 54회 이상 반복 확인.
- 변경 후보: **`getEquipmentViews()` 진입 시점에 `Map<Long, List<UiModelParam>>` in-method memoize**. 같은 트랜잭션 / 호출 범위에서만 유효 → invalidation 불필요.
- 예상 효과: ~500~1000ms 감소 (54 × 10ms × 2 패턴). 측정 가능.
- 위험: 매우 낮음. service 메서드 내부 변경, signature 보존.

**P1-B** — `/api/live-map` manage_snapshot + runtime_state N+1
- 실측: 위 modelVersionKey 외에도 manage snapshot + runtime state 각 54회 호출
- 원인: [LiveMapQueryService.java:65-77](../../ui/tc-ui-core/src/main/java/com/nori/tc/ui/core/service/LiveMapQueryService.java#L65-L77) 의 `toEquipmentView()` 가 EQP 별로 `findManageSnapshotByEqpId` + `findRuntimeStateByEqpId` 호출
- 변경 후보: 신규 port 메서드 `findManageSnapshotsByEqpIds(Set<String>)` + `findRuntimeStatesByEqpIds(Set<String>)` 도입 (IN-clause 1 쿼리). 또는 단일 JOIN 쿼리로 전체 view 한 번에 prefetch.
- 예상 효과: 추가 ~2~3초 감소. 측정 가능.
- 위험: 중간 — port adapter (`JpaEqpQueryPort`, `JpaEqpManageQueryPort`) 신규 메서드 + ModuleConfiguration `@Import` 영향 없음 (port 메서드 추가만)

**P1-C** — `/api/dlq/gateway` Redis SCAN 캐싱
- 실측: cold 268ms / warm 168ms / mean 175ms. 응답 size 59B (빈 결과). DLQ 항목 0개임에도 168ms — **SCAN 자체가 빈 keyspace 도 매 호출 round-trip**.
- 변경 후보: Spring Cache (Caffeine, TTL 3~5s). DlqController/Service 가 read-only & 운영자 수동 조회 → invalidation 부담 적음.
- 예상 효과: warm 168ms → ~5~20ms (cache hit). 단, cache eviction 직후 첫 호출은 cold 유지.
- 위험: 낮음 — Spring Cache 미사용 환경 (워크스페이스 grep 결과 @Cacheable / @CachePut 0건). cache infrastructure (CacheManager) 추가 필요. ModuleConfiguration 영향 있음.

### P2 (100~500ms 또는 fan-out)

**P2-A** — `/api/eqp/{id}/detail-bundle` 신규 endpoint
- 실측: 단일 warm 합 256ms (108+72+36+41) / 4 동시 ~190ms (baseline JSON Playwright)
- 원인: 4 endpoint fan-out — RTT 4회 + 인증 필터 4회 + 같은 EQP 의 sub-query 중복 (`findByEqpId` 다수 endpoint 에서 반복)
- 변경 후보: [EqpController.java:165](../../ui/adapter/tc-ui-web-adapter/src/main/java/com/nori/tc/ui/adapters/web/controller/EqpController.java#L165) 의 `/manage-batch` 패턴 응용 → `GET /api/eqp/{eqpId}/detail-bundle` 신규. EqpManagementService 에 `getDetailBundle(eqpId)` 신규 메서드, 단일 트랜잭션 내 4 sub-query 호출
- 예상 효과: 4 RTT → 1 RTT. 단일 트랜잭션으로 connection pool 압력 ↓
- 위험: 중간 — ui-front 측 4 useQuery 통합 별 SPEC + paired PR 필요

**P2-B** — Actuator Prometheus histogram 활성화
- 현 상태: prometheus 노출되어 있으나 `_bucket` (histogram) 메트릭 부재 — p95/p99 통계 불가
- 변경 후보: `management.metrics.distribution.percentiles-histogram.http.server.requests=true` 추가 ([application.yaml:51](../../../nori-tc-exec/apps/tc-ui-backend-app/src/main/resources/application.yaml#L51))
- 효과: 측정 정확도 향상 (단지 측정용, 기능 영향 없음). 이건 별 PR.

### P3 (효과 측정 어려움 / cold start)

- `/api/auth/me` cold 103~153ms — JIT warmup. JVM CDS / AOT 외 변경 어려움
- `@Transactional(readOnly=true)` 적용 — Hibernate flush mode 차이만, 측정 가능성 ⚠️

## ui-front 협업 SPEC 후보

- **P2-A `/api/eqp/{id}/detail-bundle`** — backend 신규 endpoint + ui-front 의 [EqpPage useEqpManageDetailQuery 4건](../../../nori-tc-ui-front/src/features/eqp/) 통합. 형식은 [p1-3-polling-consolidation.md](../../../nori-tc-ui-front/.claude/specs/p1-3-polling-consolidation.md) R2 와 동일 패턴.
- **P1-A/B `/api/live-map`** — backend 단독 최적화로 충분. ui-front 페어 불필요 (응답 schema 변경 없음).
- **P1-C `/api/dlq/gateway`** — backend 단독. 단 TTL > polling interval 이면 stale data 우려 — ui-front 측 polling 주기 확인 후 TTL 결정.

## ui-front 협업 SPEC 후보

- **`/api/eqp/{id}/detail-bundle` 도입 페어** — backend 신규 endpoint + ui-front 의 [EqpPage useEqpManageDetailQuery 4건](../../../nori-tc-ui-front/.claude/perf/measurements/baseline-2026-05-14T1100.json#L91-L117) 통합. 형식은 [p1-3-polling-consolidation.md](../../../nori-tc-ui-front/.claude/specs/p1-3-polling-consolidation.md) R2 와 동일 패턴.
- **`/api/live-map` 캐시 정책 페어** — 짧은 TTL (5~10s) 백엔드 캐시 + ui-front 측 polling 간격 정합성

## 절대 금지 / 검증 규칙 준수 상태 (v2)

- ✅ ui-front baseline JSON 1차 근거 사용 (`baseline-2026-05-14T1100.json`)
- ✅ 워크스페이스 전체 grep 으로 Spring Cache 미사용 확인
- ✅ Actuator prometheus baseline 캡처 — `backend-baseline-2026-05-14T0502.prometheus.txt`
- ✅ JFR dump 캡처 — `manual-perf-baseline-2026-05-14T0504.jfr` (7.5MB)
- ✅ SQL 로그 분석 (live-map modelVersionKey N+1 식별)
- ⚠️ EXPLAIN ANALYZE — PostgreSQL/Hibernate query level 분석 미실행. 변경 cycle 진입 후 필요 시 추가.
- ✅ 정적 분석 결과는 *보조 신호* 로만 기록. 변경 트리거는 실측 데이터.

## 임시 보안 변경 (Step 8 에서 revert 예정)

- [UiSecurityConfig.java:125-128](../../ui/adapter/tc-ui-web-adapter/src/main/java/com/nori/tc/ui/adapters/web/security/UiSecurityConfig.java#L125-L128) 에 `/api/actuator/metrics`, `/api/actuator/metrics/**`, `/api/actuator/prometheus` permitAll 추가 (커밋 안 함)
- 모든 측정 cycle 종료 후 자동 revert + 재빌드 + 재가동 필요

## 다음 액션 (사용자 결정)

P1-A (live-map modelVersionKey memoize) 가 가장 risk-low / value-high — 첫 변경 후보로 권장.

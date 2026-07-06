# PLAN: live-map 데모 run 완전한 이력 + 실행중 run 재부착

> SDD feature slug: `live-map-demo-run-history` · 단계: **Plan** (2026-07-06)
> 입력 SPEC: `nori-tc-ui-backend/.claude/specs/live-map-demo-run-history.md` (Specify 완료)
> 대상 레포/브랜치:
> - nori-tc-simulator `orchestrator` 모듈 — `feature/logging-test-console-only`
> - nori-tc-ui-backend — `feature/deploy-bulk-register-perf`
> - nori-tc-ui-front — `feature/deploy-bulk-register-perf` (working tree 로컬 수정 중)
> - nori-tc-exec — `main` (DDL patch only)
>
> 본 Plan 은 SPEC 에 박힌 설계 결정을 **구체화만** 한다(번복 없음). 모든 인용 `file:line` 은 Plan 작성 중 실제 대조함.

---

## 0. 핵심 확정 결론 (한 눈)

| # | 결정 | 근거 |
|---|---|---|
| D1 | `RunLeaseRepository.acquire` 시그니처를 `List<String> eqpKeys` → `List<RunTargetSeed>`(eqpKey+commInterface)로 확장. acquire tx 안에서 `tc_sim_run` + `tc_sim_run_lease` + `tc_sim_run_target`(state=PENDING) 원자 INSERT | R6(a), AC-O11. commInterface 는 acquire 시점에 `request.targets()` 로 이미 확보 가능(`RunOrchestrationService.java:120,126`) |
| D2 | per-target 상태/카운트 write 메서드 `updateTarget(runId, eqpKey, state, completed, errored, now)` 신설(bare 단일 UPDATE). startLot·onLotCompleted·stopTarget 이 호출 | R6(c) + R2. 단건 target 전이는 run-state write 와 동시 아님(`releaseLease`/`heartbeat` 처럼 명시 tx 밖) |
| D3 | `updateState`/`reclaim` 의 **기존 tx 안에서** run 의 비-terminal `tc_sim_run_target` 행을 co-write(blanket UPDATE) | R6(b), AC-O4/AC-O12 + createRun 보상 |
| D4 | 목록 read `listRunHistory(int limit)` 신설 — **2-step 쿼리**(run 200건 LIMIT → 그 run_id 들의 target). JOIN+LIMIT 금지 | R3/R7, AC-O6/O7. JOIN 에 LIMIT 걸면 run×target 행이 잘려 마지막 run 의 설비 일부 누락(버그) |
| D5 | `RunResponse`/`SimulatorRunResponse`/ui-front 타입에 `createdAt`(**String, ISO-8601**)·`scenarioId` **append**. 단건 `from()` 갱신 + 목록용 `fromHistory()` 신설 | R5/C5. String 채택 = Jackson JavaTimeModule 의존 회피(현 RunResponse 는 enum 만 직렬화) |
| D6 | 목록 API 는 **기존** `RunController`/`RunOrchestrationService`/`RunLeaseRepository`/`LiveMapController`/facade/client 에 **메서드만 추가**. 신규 @Bean/@RestController 0 | R6(seam=RunLeaseRepository 확장), R8. → AC-O8/AC-B4 컨텍스트 로드 자명 |
| D7 | DDL = `nori-tc-exec/docs/db_table/patch/postgres_2026_07_06_create_sim_run_target.sql`(신규) + orchestrator `schema.sql` 미러. **Flyway `db/migration/V5` 편입 안 함** | R8. `tc_sim_run`/`tc_sim_run_lease`/`tc_sim_run_target` 3테이블은 `V1__baseline.sql`~V4 에 없음(grep 확인). **주의: 같은 simulator 소유 `tc_simulator_scenario` 는 V1:1217 에 fold됨 — 기준은 소유권 아닌 baseline fold 여부.** tc_sim_run* 계열은 아직 미fold → `docs/db_table/patch/` psql 수동(AC-D1 게이트) |
| D8 | ui-front 단위검증은 **순수 함수**(`buildRunHistoryGroups`)로. 훅/컴포넌트 렌더 테스트 인프라(vitest/testing-library/renderHook)는 **없음**(harness=`node --test`) — 발명 금지 | package.json `test:unit` 실측(vitest/RTL/jsdom 전무). **SPEC AC-F1~F3 이 순수 함수 검증으로 정정됨** — wire/훅/렌더는 tsc(AC-F5)+e2e(AC-F6). (narrowing 아닌 SPEC 준수) |

---

## 1. 영향 파일 목록 (신규 N / 수정 M)

### nori-tc-simulator (orchestrator)
| 파일 | 구분 | 변경 요지 |
|---|---|---|
| `orchestrator/.../adapter/lease/RunLeaseRepository.java` | M | `acquire` 시그니처 확장 + `updateTarget` / `listRunHistory` 메서드 추가. `RunTargetSeed`/`RunHistoryRecord`/`RunTargetRecord` 레코드 신설(같은 패키지) |
| `orchestrator/.../adapter/lease/JdbcRunLeaseRepository.java` | M | acquire tx 에 target INSERT · updateState/reclaim tx 에 target co-write · `updateTarget` bare UPDATE · `listRunHistory` 2-step 쿼리 구현 |
| `orchestrator/.../adapter/lease/InMemoryRunLeaseRepository.java` | M | `RunRow` 에 mode/scenarioId/createdAt + `Map<eqpKey, TargetRow>` 추가. 동일 4메서드 재현(AC-O1/O12 검증 seam) |
| `orchestrator/.../application/RunOrchestrationService.java` | M | createRun: `List<RunTargetSeed>` 로 acquire. startLot/onLotCompleted/stopTarget 에 `updateTarget` 호출. `listRunHistory(limit)` 위임 메서드 추가 |
| `orchestrator/.../domain/TargetState.java` | M(주석) | `FAILED` 가 reserved(`:13`)였으나 reclaim co-write 가 처음으로 set — 주석 갱신(값/동작 변경 아님) |
| `orchestrator/.../api/RunController.java` | M | `@GetMapping`(목록) 추가 + `DEFAULT_HISTORY_LIMIT=200` |
| `orchestrator/.../api/dto/RunResponse.java` | M | record 에 `String createdAt`·`String scenarioId` append. `from()` 갱신 + `fromHistory(RunHistoryRecord)` 신설 |
| `orchestrator/.../api/dto/TargetResponse.java` | M | 필드 무변경. DB 경로용 factory(`fromRecord`)만 추가 — latencyStats=`LatencyStats.empty()`, issuedLotRunIds=`List.of()`(C6) |
| `orchestrator/src/test/resources/schema.sql` | M | `CREATE TABLE tc_sim_run_target` 미러(FK CASCADE, uk(run_id,eqp_key)) — **누락 시 전 Testcontainers FAIL** |
| lease 테스트 3건(아래 §4) | M | acquire 호출부를 `List<RunTargetSeed>` 로 |
| 신규 테스트 다수(§4) | N | AC-O1~O7/O10~O12 |

### nori-tc-ui-backend
| 파일 | 구분 | 변경 요지 |
|---|---|---|
| `.../web/simulator/SimulatorRunResponse.java` | M | top-level record 에 `String createdAt`·`String scenarioId` append(SimulatorTargetResponse 무변경) |
| `.../web/simulator/SimulatorOrchestratorClient.java` | M | `List<SimulatorRunResponse> listRuns()` 추가 |
| `.../web/simulator/HttpSimulatorOrchestratorClient.java` | M | `listRuns` = `sendJson("GET","/api/demo-runs",null,new TypeReference<List<...>>(){})`(getScenarios `:66-71` 답습) |
| `.../web/simulator/LiveMapSimulatorRunFacade.java` | M | `listRuns()` 위임 메서드 추가(**생성자 무변경** → 배선 무영향) |
| `.../web/controller/LiveMapController.java` | M | `@GetMapping("/demo-runs")` → `ApiResponse<List<SimulatorRunResponse>>` |
| 기존 테스트 6건 `new SimulatorRunResponse(...)`(§4) | M | 4-arg → 6-arg |
| 신규 테스트 3건(§4) | N | AC-B1/B2/B3 |

### nori-tc-ui-front
| 파일 | 구분 | 변경 요지 |
|---|---|---|
| `src/features/live-map/api/live-map-demo-run.api.ts` | M | `SimulatorRunResponse` 에 `createdAt:string`·`scenarioId:string` append. `liveMapDemoRunApi.listRuns()` 신설(getScenarios 답습) |
| `src/features/live-map/hooks/useSimulatorRunList.ts` | N | `useQuery`(queryKey `['live-map-demo-run-list']`, enabled=open, refetchInterval 3000) |
| `src/features/live-map/lib/live-map-demo-run-history.ts` | N | 순수 함수 `buildRunHistoryGroups(runs)` (createdAt DESC 정렬 + run→group + 설비행) — AC-F2/F3 단위 테스트 대상 |
| `src/features/live-map/components/LiveMapDemoModal.tsx` | M | '시뮬레이션 진행 현황' 을 단일 `activeRunId`+`useSimulatorRun` 바인딩 → **목록 렌더**로 전환(§3.3) |
| `tests/unit/live-map-demo-run-history.test.ts` | N | AC-F2/F3(순수 함수) |
| i18n 카탈로그 | M | run 그룹 헤더 라벨(시작시각/시나리오) 키 추가 — ko 베이스 |

### nori-tc-exec
| 파일 | 구분 | 변경 요지 |
|---|---|---|
| `docs/db_table/patch/postgres_2026_07_06_create_sim_run_target.sql` | N | `tc_sim_run_target` DDL(§D7). sibling `postgres_2026_06_15_create_sim_run_tables.sql` 스타일 답습(BEGIN/COMMIT, IF NOT EXISTS, public 스키마, 헤더 주석) |

---

## 2. seam 시그니처 확정 (D1~D5 구체)

### 2.1 신규 레코드 (adapter.lease 패키지)
```java
public record RunTargetSeed(String eqpKey, String commInterface) {}

public record RunHistoryRecord(String runId, String mode, String scenarioId,
                               RunState state, Instant createdAt, List<RunTargetRecord> targets) {}

public record RunTargetRecord(String eqpKey, String commInterface, TargetState state,
                              int completedCycleCount, int erroredCycleCount) {}
```

### 2.2 `RunLeaseRepository` 확장
```java
void acquire(String runId, String mode, String scenarioId, String orchestratorInstanceId,
             List<RunTargetSeed> targets, Instant now);          // eqpKeys → targets

void updateTarget(String runId, String eqpKey, TargetState state,
                  int completedCycleCount, int erroredCycleCount, Instant now);   // 신규(D2)

List<RunHistoryRecord> listRunHistory(int limit);                                 // 신규(D4)
```
기존 `updateState`/`reclaim`/`releaseLease`/`heartbeat`/`findStaleActiveRunIds`/`activeLeaseCount`/`findState` 시그니처는 **불변**. 단 `updateState`/`reclaim` 의 **구현**에 target co-write 추가(D3).

### 2.3 각 write 지점 → 메서드 매핑 (`RunOrchestrationService`, 전부 `synchronized(run)` 내부)
| 지점 (코드) | 메모리 전이 | DB write | 원자성 |
|---|---|---|---|
| `createRun` acquire(`:122`) | — | `acquire`: run+lease+target(PENDING) INSERT | (a) **acquire tx 원자** (R6a/AC-O11) |
| `createRun` startLot→`beginLot`(`:134,339`) | PENDING→RUNNING | `updateTarget(RUNNING, 0,0)` | (c) 별도 write |
| `createRun` 보상(`:139-140`) | run FAILED | `updateState(FAILED)` → 비-terminal target FAILED co-write | (b) tx 원자 |
| `onLotCompleted` self-loop(`:191-198`) | count++ (RUNNING 유지) | `updateTarget(RUNNING, counts)` | 별도 write |
| `onLotCompleted` ONCE(`:207-208`) | completeLot/errorLot + COMPLETED | `updateTarget(COMPLETED, counts)` | 별도 write |
| `onLotCompleted` ONCE→finish(`:217,492`) | run TERMINAL/CANCELED | `updateState(finalState)` (target 이미 terminal → co-write no-op) | (b) tx 원자 |
| `onLotCompleted` UNTIL_STOPPED continue(`:226-227`) | count++ 후 새 lot RUNNING | `updateTarget(...)` via startLot | 별도 write |
| `onLotCompleted` UNTIL_STOPPED stopRequested(`:223`) | count++ (RUNNING 유지) | `updateTarget(RUNNING, counts)` | 별도 write |
| `stopRun`(`:239-248`) | RUNNING→CANCELED + run CANCELED | `updateState(CANCELED)` → 비-terminal target CANCELED co-write | (b) tx 원자 (AC-O4) |
| `stopTarget`(`:273`) | 한 target CANCELED | `updateTarget(CANCELED, counts)` (형제 미변경) | 별도 write (AC-O5) |
| `stopTarget`→finish(`:284,492`) | run 종료(전부 terminal 시) | `updateState(finalState)` co-write no-op | (b) tx 원자 |
| reaper `reclaim`(`RunLeaseReaper.java:41`) | (서비스 우회) run FAILED | `reclaim`: run FAILED + 비-terminal target FAILED co-write | (b) tx 원자 (AC-O12) |

### 2.4 `updateState`/`reclaim` co-write 상세 (Jdbc)
- `updateState`: 기존 `tx.executeWithoutResult` 안, run UPDATE 뒤 `state.isTerminal()` 이면
  `UPDATE tc_sim_run_target SET state=?, updated_at=? WHERE run_id=? AND state NOT IN ('COMPLETED','CANCELED','FAILED')`.
  - RunState→TargetState 매핑(private static): `TERMINAL→COMPLETED`, `CANCELED→CANCELED`, `FAILED→FAILED`.
  - 서비스는 `updateState` 를 항상 terminal 값으로만 호출(FAILED/CANCELED/TERMINAL) — grep 확인. TERMINAL 시엔 이미 전 target COMPLETED 라 WHERE 매칭 0(무해).
- `reclaim`: 기존 tx 안, `updated>0`(활성→FAILED 성공) 일 때만 `UPDATE tc_sim_run_target SET state='FAILED', updated_at=? WHERE run_id=? AND state NOT IN (terminal)`.
- **주의:** 이로써 `TargetState.FAILED` 가 처음으로 실제 set 된다(현 `TargetState.java:13` "어떤 전이도 이 상태로 set 하지 않는다" 주석 갱신).

### 2.5 목록 쿼리 (Jdbc `listRunHistory`, D4)
```
1) SELECT run_id, mode, scenario_id, state, created_at
     FROM tc_sim_run ORDER BY created_at DESC LIMIT ?         -- ? = 200 (R7)
2) SELECT run_id, eqp_key, comm_interface, state, completed_cycle_count, errored_cycle_count
     FROM tc_sim_run_target WHERE run_id IN (<step1 run_id 들>)
```
- Java 에서 run_id 별 그룹핑(step1 순서 보존). **단일 JOIN+LIMIT 금지**(joined 행에 LIMIT → run 의 설비 일부 잘림).
- **lease 조인 불요:** `tc_sim_run_target` 가 eqp_key+comm_interface 를 보유하므로 SPEC §3(2) 괄호의 3-way(⋈lease)는 대체. run 이력=(run ⋈ target) 2-step.
- step1 이 0건이면 step2 skip(빈 IN 절 회피).

### 2.6 DTO 조립 (D5)
- `RunResponse` 최종 형상: `(runId, mode, state, targets, createdAt, scenarioId)` — createdAt/scenarioId 를 **targets 뒤에 append**.
  - `from(RunAggregate)`: `createdAt = run.getCreatedAt().toString()`(ISO-8601 UTC 'Z'), `scenarioId = run.getScenarioId()`.
  - `fromHistory(RunHistoryRecord)`: targets = record.targets() 를 `TargetResponse.fromRecord` 로, createdAt=record.createdAt().toString(), scenarioId=record.scenarioId().
- `TargetResponse.fromRecord(RunTargetRecord)`: `new TargetResponse(eqpKey, state, commInterface, completed, errored, LatencyStats.empty(), List.of())`.
- ui-backend `SimulatorRunResponse` 최종: `(runId, mode, state, targets, createdAt, scenarioId)` — String append.
- ui-front `SimulatorRunResponse`: `{ ..., createdAt: string, scenarioId: string }`.
- **정렬 근거:** `Instant.toString()` 은 항상 UTC 'Z' → ISO-8601 문자열 사전순 = 시간순. ui-front 는 문자열 desc 정렬로 안전(방어적 재정렬).

---

## 3. 단계별 실행 순서 + AC 매핑

> 원칙: DB 스키마 → orchestrator 영속 seam → 목록 API → ui-backend 프록시 → ui-front 렌더. 각 AC 당 실패 테스트 먼저(Implement).

### 단계 1 — DDL + schema.sql 미러 (AC-D1)
1. `postgres_2026_07_06_create_sim_run_target.sql` 작성(§D7 스키마).
2. `orchestrator/src/test/resources/schema.sql` 에 동일 `CREATE TABLE tc_sim_run_target`(BEGIN/COMMIT 없이, IF NOT EXISTS) 미러.
- **검증:** 미러 없으면 §3-2 이후 전 Testcontainers/@SpringBootTest 가 FK/INSERT 로 FAIL → 미러가 게이트. 라이브 psql 적용은 Validate(AC-D1).

### 단계 2 — orchestrator 영속 seam (AC-O1~O5, O8~O12, O10)
1. 레코드 3종 + `RunLeaseRepository` 3메서드 시그니처(§2.1/2.2).
2. `JdbcRunLeaseRepository`: acquire target INSERT / updateTarget / updateState·reclaim co-write / listRunHistory(§2.4/2.5).
3. `InMemoryRunLeaseRepository`: RunRow 확장 + 동일 4메서드.
4. `RunOrchestrationService`: createRun `RunTargetSeed` 조립, §2.3 각 지점 `updateTarget` 호출, `listRunHistory` 위임.
5. `RunResponse`/`TargetResponse` 필드/팩토리(§2.6).
- **검증:**
  - AC-O1(RUNNING·comm_interface)·AC-O12(reclaim→target FAILED) = **InMemory 단위 테스트**(Docker 불요).
  - AC-O2(COMPLETED,completed=1)·AC-O3(errored+1)·AC-O4(stopRun→전 RUNNING CANCELED)·AC-O5(stopTarget eqpA만) = InMemory 또는 서비스+InMemory.
  - AC-O11(acquire tx 원자 롤백: target INSERT 강제 실패 → run 행 미커밋, findState empty) = **Testcontainers(로컬 Docker 게이트)**.
  - AC-O8(context load)·AC-O9(`:orchestrator:build` 전체 PASS, Docker 필요)·AC-O10(getRun 단건 회귀 + createdAt/scenarioId 포함).

### 단계 3 — orchestrator 목록 API (AC-O6, O7)
1. `RunController` `@GetMapping`(목록) + `DEFAULT_HISTORY_LIMIT=200`. base=`/api/demo-runs`, 무-path GET 이 `@GetMapping("/{runId}")`·`getRun` 과 비충돌(경로 depth 상이).
- **검증:** AC-O6(created_at DESC + createdAt/scenarioId + per-target + evict 된 run 도 DB 출처 포함), AC-O7(limit+N 개 → ≤200). Testcontainers HTTP(MockMvc) 또는 Jdbc listRunHistory 직접.

### 단계 4 — ui-backend 프록시 (AC-B1~B5)
1. `SimulatorRunResponse` 2필드 append(6건 테스트 호출부 동반 수정 — §4).
2. `SimulatorOrchestratorClient.listRuns` + `HttpSimulatorOrchestratorClient` 구현.
3. `LiveMapSimulatorRunFacade.listRuns` + `LiveMapController` `@GetMapping("/demo-runs")`.
- **검증:** AC-B1(`HttpSimulatorOrchestratorClientTest` 패턴 = embedded `com.sun.net.httpserver.HttpServer`, GET `/api/demo-runs` 배열 역직렬화), AC-B2(controller `ApiResponse<List<>>` + createdAt/scenarioId 보존), AC-B3(`LiveMapScenarioPermissionTest` 패턴 = `UiApiPermissionCache` 직접, `LIVE_MAP_DEMO_RUN_READ` PREFIX `/api/live-map/demo-runs` GET 로 목록 커버, 미인가 차단·인가 통과), AC-B4(context load), AC-B5(`:ui:adapter:tc-ui-web-adapter:build` 전체 PASS).

### 단계 5 — ui-front 렌더 (AC-F1~F5)
1. api 타입 2필드 + `listRuns()`.
2. `useSimulatorRunList(open)` 훅.
3. 순수 함수 `buildRunHistoryGroups(runs)`.
4. `LiveMapDemoModal` 목록 렌더 전환(§3.3).
- **검증:** AC-F2/F3 = 순수 함수 node:test(mock 목록 → 세션 밖 실행중 run 포함 + createdAt/scenarioId + 최신순). AC-F5 = `tsc --noEmit` + `eslint .` + `test:unit`(출력 인용). AC-F1(§D8, SPEC 정정 반영) = 순수 변환(buildRunHistoryGroups) + wire 는 AC-F6 e2e. AC-F4(닫고 재오픈 유지)는 서버 목록 출처라 자연 성립 + e2e(AC-F6).

### 3.3 ui-front '시뮬레이션 진행 현황' 렌더 전환 상세
- **데이터 출처 전환:** `useSimulatorRun(activeRunId)` 단일 폴링 → `useSimulatorRunList(open)` 목록 폴링. `runTargets = run?.targets`(`:143`) 제거.
- **activeRunId 축소(SPEC §3.3 결정):** 목록이 실행중·과거 run 을 모두 담으므로 **데이터 바인딩 목적의 `activeRunId` + `useSimulatorRun` 폐기**. 시작 성공 메시지(`:314-319`) 용으로만 로컬 `justStartedRunId`(optional) 보존 — 진행 테이블은 목록에서 렌더. 닫기-재오픈 데이터 소실(F4) 원천 해소(로컬 state 아님).
- **렌더 구조:** run 그룹(최신순) 헤더 = runId · 연결유형(`RUN_MODE_TEST_TYPE_I18N_KEY[run.mode]`, `:56-60`) · scenario 명(scenarioId 를 `useSimulatorRunScenarios` 목록에서 조회, 미존재 시 scenarioId fallback) · 시작시각(createdAt) · 상태배지(`resolveRunStateChipClass`) · 완료/전체. 그 아래 설비별 `SelectableTable`(기존 `runTargetColumns` 재사용, `:530-563`).
- **종료 버튼:** `handleStopTarget` 이 `activeRunId`(`:327`) 대신 그룹 행의 runId 사용. disabled 조건(`:833-838`)은 그룹 run.state + target.state 로 보존(`isTerminalRunState`/`isTerminalTargetState`).
- **scenarioId 필요성(R5):** 과거/재시작 run 은 create-side `selectedScenario`(`:173-179`)로 복원 불가 — run.scenarioId(서버) 가 유일 출처.

---

## 4. 기존 테스트 영향 및 대응

### orchestrator
- **acquire 시그니처 변경 → 테스트 호출부 5곳 수정(3파일, mechanical) + main 1곳:**
  - `AtomicLeaseRejectIntegrationTest.java:80` `List.of("EQP_06")` → `List.of(new RunTargetSeed("EQP_06","SOCKET"))`
  - `OrchestratorCrashLeaseReclaimIntegrationTest.java:64,82` `List.of("EQP_08")` → seed (2곳)
  - `JdbcReleaseLeasePartialUniqueIntegrationTest.java:55,67` `List.of("EQP_A","EQP_B")`/`List.of("EQP_A")` → seed 리스트 (2곳)
  - (main `RunOrchestrationService.java:122` 는 §2.3 — 합계 테스트 5 + main 1)
- **schema.sql 미러 누락 = 전 Testcontainers FAIL**(단계 1 게이트).
- **`RunResponse` 2필드 append:** 유일 생성지 `RunResponse.from`(`:27`)만 수정. `RunResponseConcurrentSnapshotTest`(`:61,63`)는 `from()` 경유라 컴파일 유지. AC-O10 이 createdAt/scenarioId 포함 assert 추가.
- **getRun 단건/배리어/reaper 회귀:** getRun in-memory 경로 무변경(R3-주의). `reconcileAndEvictRuns` 무변경. reclaim 은 co-write 추가만(기존 run FAILED 동작 보존) → `OrchestratorCrashLeaseReclaimIntegrationTest` Part 1~3 유지.

### ui-backend
- **`SimulatorRunResponse` 4-arg → 6-arg → 6 호출부 수정:**
  `HttpSimulatorOrchestratorClientTest.java:61`, `LiveMapControllerSimulatorRunTest.java:37`, `DemoRunConnectionReaperTest.java:129`, `LiveMapSimulatorRunFacadeTest.java:234,293,373` (repo grep `new SimulatorRunResponse(` = 정확히 6곳, 전부 4-arg, 전부 test 트리).
  (내부 `SimulatorTargetResponse` 선언 = `SimulatorRunResponse.java:22-30` 은 무변경. `LiveMapSimulatorRunFacadeTest.java:237-238` 은 그 6-arg 호출부로 필드 불변이라 수정 불요.)

### ui-front
- `SimulatorRunResponse` 인터페이스에 필드 append → 위치기반 아님이라 소스 컴파일 안전. `useSimulatorRun.ts` 는 단건용이라 유지(축소 시 제거). demo-runs `page.route` mock 을 쓰는 e2e(`tests/e2e/modal/modal-visual-sweep-livemap.spec.ts`, `tests/e2e/live-map/live-map-scenario-upload.spec.ts`)는 createdAt/scenarioId 포함하도록 갱신(AC-F6). (`tests/e2e/demo-matrix.harness.spec.ts` 는 실서버 waitForResponse 라 mock 아님.)

---

## 5. 위험 + 완화

| 위험 | 영향 | 완화 |
|---|---|---|
| **R-A: acquire 시그니처 파급** | orchestrator 테스트 호출부 5곳(3파일) + main 컴파일 깨짐 | §4 목록대로 동시 수정. `RunTargetSeed` 단일 record 로 파라미터 1개 유지(가독성) |
| **R-B: InMemory 확장 누락** | AC-O1/O12 seam 검증 공허(Jdbc 만 되고 InMemory 미구현) | RunRow 에 mode/scenarioId/createdAt + TargetRow 맵 반드시 추가. 두 구현 대칭(`RunLeaseRepository:9-15` 규율) |
| **R-C: 목록 쿼리 JOIN+LIMIT 버그** | run 200번째 설비행 잘림 = torn history | D4 2-step 강제(§2.5). AC-O7 이 개수만 보므로 별도로 "설비 완전성" 회귀 테스트 추가 권장 |
| **R-D: TargetState.FAILED 첫 사용** | 다운스트림 배지/필터가 FAILED 미처리 | ui-front `TERMINAL_TARGET_STATES` 는 이미 FAILED 포함(`LiveMapDemoModal.tsx:68`)·`resolveRunStateChipClass` 확인. orchestrator ck 제약에 FAILED 포함(§D7) |
| **R-E: createdAt 직렬화** | Instant→JSON 실패/epoch | String(ISO-8601) 채택으로 원천 회피(D5). 3계층 모두 String |
| **R-F: ui-front 렌더 전환 회귀** | 종료 버튼/재부착/빈상태 깨짐 | activeRunId 데이터 바인딩만 제거(종료 mutation 은 그룹 runId 로). worst-case mock(다수 run·다수 설비·overflow) e2e — `feedback_visual_e2e_real_env_verify`/`feedback_verify_layout_edge_cases` 준수 |
| **R-G: ui-front 훅/컴포넌트 테스트 인프라 부재** | (구) AC-F1/F2/F3 훅/컴포넌트 검증 문자열은 실행 불가 | **SPEC AC-F1~F3 을 순수 함수(`buildRunHistoryGroups`) 검증으로 정정 완료**(D8). 훅/컴포넌트/wire 는 tsc·eslint(AC-F5)+e2e(AC-F6) 게이트 |
| **R-H: Docker 미가동** | AC-O9/O11 build FAIL(`@Testcontainers` disabledWithoutDocker 미설정) | 로컬 Docker 필수 게이트로 명시(§6). InMemory AC(O1~O5/O12)는 Docker 불요라 분리 |
| **R-I: 목록 무제한 성장** | tc_sim_run prune 없음 → 200 초과 다수 | R7 LIMIT 200 서버 강제(AC-O7). retention 은 Out of Scope |

---

## 6. 게이트 취급 (명시)

- **AC-O11 (Testcontainers 원자성)·AC-O9 (orchestrator build)** — 로컬 **Docker 필수**. `AbstractPostgresSpringBootTest`/`@Container` 가 `disabledWithoutDocker` 미설정이라 Docker 없으면 build FAIL(AC-O6/O8 과 동급). Docker 없는 환경에선 InMemory AC(O1~O5/O12)만 자연 PASS — 보고 시 분리 명시.
- **AC-D1 (라이브 DDL)** — patch 파일 + schema.sql 미러는 Implement 산출. 라이브 nori_tc psql 적용은 **Validate 수동 승인 게이트**(sibling `postgres_2026_06_15` 관례, Flyway 미편입 §D7). 외부 postgres DDL 은 node01 경유 임시 psql pod 가능(memory `reference_db_ddl_patch_via_transient_psql_pod`).
- **AC-F6 (실환경 e2e)** — 실 orchestrator+ui-backend+ui-front 기동 후: 데모 run 실행 → 모달 닫고 재오픈 → 실행중 run 재표시 + 자연완료 후 과거 이력 잔존. Playwright `page.on('pageerror')` error 0 게이트. worst-case mock + 실서버 스크린샷(memory `feedback_visual_e2e_real_env_verify`).
- CI(norilab 러너)는 Docker 미구성 → 백엔드 Testcontainers auto-skip(memory `project_norilab_runner_no_docker_testcontainers`) — orchestrator Testcontainers AC 는 **로컬** 게이트.

---

## 7. 롤백

- **레포별 브랜치 revert:** 4 레포 각 커밋을 독립 revert(단방향 의존 — orchestrator ← ui-backend ← ui-front, exec DDL 독립). 목록 API 미배포 상태로 되돌리면 프론트는 목록 미표시(기존 단건 동작 필요 시 useSimulatorRun 복구).
- **DDL 롤백:** `DROP TABLE IF EXISTS tc_sim_run_target;`(FK CASCADE 로 자식 없음). append-only 확장이라 기존 tc_sim_run/tc_sim_run_lease·소비자 무영향.
- **append-only 안전성:** createdAt/scenarioId 는 `@JsonIgnoreProperties(ignoreUnknown=true)` 하 필드 추가라, orchestrator 만 배포/롤백해도 ui-backend·ui-front 계약 미파손(C5).
- **부분 롤백 주의:** ui-backend 목록 엔드포인트만 롤백하고 ui-front 가 남으면 목록 404 → 프론트 빈 목록(에러 팝업 아님, normalizeError). 순서 = ui-front 먼저 revert.

---

## 8. Out of Scope 재확인 (SPEC 준수)
- 실행/종료/연결(EQP_START/END) 로직·배리어·reaper 원복 무수정(C1/C2). gateway 코드 0(C1).
- 단건 getRun 은 in-memory 유지(R3-주의). run prune/retention·per-user·페이징(R7/C7). issuedLotRunIds/latency 영속(C6).
- ui-front 신규 테스트 프레임워크(vitest/testing-library) 도입 금지(D8).

---

## 다음 단계 진입 프롬프트 (Implement)
```
@nori-tc-ui-backend/.claude/specs/live-map-demo-run-history.md
@nori-tc-ui-backend/.claude/specs/live-map-demo-run-history-plan.md 둘 다 읽고 구현.
AC-O1~O12 / AC-B1~B5 / AC-F1~F5 / AC-D1 각각에 실패 테스트 먼저(AC-F6 은 실환경 수동/e2e).
Plan 의 seam(D1~D6)·쿼리(§2.5)·UI 전환(§3.3)·테스트 수정(§4) 엄수.
gateway·실행로직 무수정(C1/C2), 신규 @Bean/@RestController 0(D6/R8),
DDL 패치 + schema.sql 미러(§D7/AC-D1), createdAt·scenarioId String append-only(D5/R5),
seam=RunLeaseRepository 확장(R6). ui-front 는 순수 함수 검증(D8) — 훅 테스트 프레임워크 발명 금지.
산출물: nori-tc-ui-backend/.claude/specs/live-map-demo-run-history-dev-report.md.
```

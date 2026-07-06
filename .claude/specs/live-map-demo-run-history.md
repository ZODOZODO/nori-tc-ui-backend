# SPEC: live-map 데모 run 완전한 이력 + 실행중 run 재부착

> SDD feature slug: `live-map-demo-run-history`
> 작성 근거: 2026-07-06. 사용자 관측 — '데모 시작' 모달의 '시뮬레이션 진행 현황' 에 (1) 과거 run 이력이 전혀
> 안 나옴, (2) 모달을 닫았다 다시 열면 실행중 run 도 사라짐. "완전한 이력을 정석적으로" 요구.
> 관련 형제 SPEC(같은 디렉터리): `demo-run-connection-restore.md`(자연완료 EQP_END), `demo-run-async-barrier.md`
> (비동기 배리어), `demo-tester-path-removal.md`. 본 SPEC 은 이들과 충돌하지 않는 append-only 확장이다.
> SDD 진행 상태: **Validate 완료** (2026-07-06). Specify→Plan→Implement→Validate 전 단계 종료.
> Validate 결과: 자동 게이트(orchestrator/ui-backend build + ui-front tsc/eslint/test:unit) 전부 PASS,
> AC-D1 라이브 nori_tc DDL 적용·\d 검증 완료. AC-F4/F6(실환경 e2e)만 [~] — 실환경 3-서비스+데모 세션
> 미확보(사용자 결정), spec 작성 완료 + 자동 테스트로 로직 검증됨.

## Why (배경 / 문제)

live-map '데모 시작' 모달의 '시뮬레이션 진행 현황' 은 **단일 실행중 run 하나의 target 만** 보여준다.

코드 사실 (nori-tc-ui-front):
- 모달이 단일 `activeRunId` state 를 들고(`LiveMapDemoModal.tsx:113`), `useSimulatorRun(activeRunId, open)` 로
  `GET /api/live-map/demo-runs/{runId}` 를 3s 폴링(terminal 도달 시 중단, `useSimulatorRun.ts:16-19`)한다.
- 진행 테이블은 `runTargets = run?.targets ?? []`(`LiveMapDemoModal.tsx:143`) — **오직 그 run 하나의 target 행**만
  `SelectableTable` 로 렌더(`:819-851`).
- 모달을 닫으면 `handleClose` 가 `setActiveRunId(null)`(`:235`) 로 초기화 → **다시 열면 실행중 run 도 화면에서 사라진다**
  (run 자체는 백그라운드에서 계속 — 모달 닫기의 stop 호출은 이미 제거됨). 재부착 경로가 없다.
- `live-map-demo-run.api.ts` 에 **run 목록/이력 조회 API 가 없다**(createRun / getRun / stopRun / stopTarget /
  scenarios 만, `:103-230`).

코드 사실 (nori-tc-ui-backend):
- `LiveMapController`(`/api/live-map`)에 목록 엔드포인트 없음 — `GET /demo-runs/{runId}` 단건만(`LiveMapController.java:65-70`).
- `SimulatorOrchestratorClient` 인터페이스에 `listRuns` 없음(`SimulatorOrchestratorClient.java:9-43`).

코드 사실 (nori-tc-simulator orchestrator):
- `RunController`(`/api/demo-runs`)에 목록 엔드포인트 없음 — create/stop/targets-stop/activate-upstream/get-by-id
  뿐(`RunController.java:38-72`).
- run 은 in-memory `ConcurrentHashMap<String, RunAggregate> runs`(`RunOrchestrationService.java:81`)에 보관되고,
  **terminal 진입 10분 후 in-memory 에서 evict** 된다(`RUN_EVICTION_TTL = Duration.ofMinutes(10)`, `:70`,
  `reconcileAndEvictRuns() :309-327`). 즉 in-memory 는 이력 저장소가 될 수 없다.

코드 사실 (DB — 운영 배선 `JdbcRunLeaseRepository`, `OrchestratorRuntimeConfiguration.java:128-133`):
- `tc_sim_run(run_id, mode, scenario_id, state, orchestrator_instance_id, last_heartbeat_at, created_at, updated_at)`
  — acquire 시 INSERT(`JdbcRunLeaseRepository.java:48-51`), 상태전이 시 UPDATE(`:82-91`, `:117-126`). **DELETE 문
  전무** → terminal run 행도 삭제되지 않고 무기한 보존(재시작·10분 evict 후에도 DB 잔존). 스키마
  `orchestrator/src/test/resources/schema.sql:3-17`.
- `tc_sim_run_lease(run_id, eqp_key, released, created_at)` — 설비 목록. FK→tc_sim_run ON DELETE CASCADE(`schema.sql:19-34`).
- **즉 run 레벨(runId/mode/scenario/state/시각) + 설비목록은 이미 영속화**되어 있다.
- **그러나 per-target 상세는 in-memory `TargetAggregate` 에만 존재하고 DB 에 없다** — `state`(PENDING/RUNNING/
  COMPLETED/CANCELED/FAILED), `completedCycleCount`, `erroredCycleCount`, `commInterface`(`TargetAggregate.java:26-29,
  45`). 10분 evict / orchestrator 재시작 시 소실된다. 진행 테이블이 표시하는 바로 그 값들이다
  (`SimulatorRunResponse.SimulatorTargetResponse`: eqpId/state/commInterface/completedCycleCount/erroredCycleCount).

정석(=완전) 설계 결론: run 레벨 이력은 이미 DB 에 있으나 **per-target 상세는 in-memory 뿐이라 이력이 불완전**하다.
"완전한 이력"(재시작·evict 후에도 과거 run 의 설비별 상태/사이클까지 확인 가능)을 위해 **per-target 상세를 DB 에
영속화**하고, 그 위에 **목록 조회 API** 를 얹어 프론트가 이력 전체 + 실행중 run 을 항상 표시(재부착 포함)하게 한다.

## 올바른 동작 (목표)

- '시뮬레이션 진행 현황' 은 **모달을 열 때마다 서버의 run 이력 목록을 조회**해, 과거·실행중 run 을 최신순으로 모두
  보여준다. 각 run 은 runId / mode / scenario / 시작시각 / 최종상태 + 설비별(state / 통신 / 완료·오류 사이클) 상세를 가진다.
- 모달을 닫았다 다시 열어도 실행중 run 이 그대로 보인다(**재부착** — 별도 상태 복원 로직 없이 목록 조회 결과에서 자연히
  나옴). 실행중 run 의 설비별 진행은 목록 폴링으로 갱신된다.
- 과거 terminal run(evict / 재시작 이후 포함)도 설비별 최종 상태·사이클 카운트까지 표시된다(DB 영속 덕분).
- per-target 종료 버튼은 **실행중(non-terminal) target 에만** 활성 — 기존 동작(`LiveMapDemoModal.tsx:833-838`) 유지.
- 데모 실행/종료/연결 관리(형제 SPEC 들의 배리어·EQP_END 원복)의 동작은 **일절 바뀌지 않는다**. 본 SPEC 은 관측/조회
  경로의 append-only 확장이다.

## Requirements

- **R1 (per-target 영속 테이블 신설).** orchestrator 소유 신규 테이블 `tc_sim_run_target` 를 만든다. 최소 컬럼:
  `run_id`(FK→tc_sim_run ON DELETE CASCADE), `eqp_key`, `comm_interface`, `state`, `completed_cycle_count`,
  `errored_cycle_count`, `created_at`, `updated_at`. (run_id, eqp_key) 유일. `tc_sim_run` 과 동일하게 raw
  JdbcTemplate 으로 소유(orchestrator 는 JPA 미사용 — `application.yaml` 에 ddl-auto/hibernate 없음).
- **R2 (write-path — 상태전이 지점마다 영속).** `RunOrchestrationService` 의 target 상태·카운트 변이 지점에서
  `tc_sim_run_target` 를 갱신한다. 모두 기존 `synchronized (run)` 블록 내부에서 수행한다:
  - `createRun`(`:113-146`): 각 target INSERT. **초기 state=PENDING**(`TargetAggregate` 초기값 —
    `TargetAggregate.java:26`)로 run INSERT(lease acquire)와 **동일 트랜잭션**(R6)에 넣고, `startLot`(`:134`)가 RUNNING
    으로 전이시킨 직후 state=RUNNING 으로 UPDATE 한다. RUNNING 은 startLot 이 서비스 계층에서 확정하므로 acquire tx
    밖이다 — "RUNNING 을 acquire 와 한 tx 로 INSERT" 는 불가하고 PENDING(INSERT)→RUNNING(UPDATE) 2-step 이다(R6).
  - `onLotCompleted`(`:172-229`): `completeLot`/`recordCompletedCycle` → completed_cycle_count, `errorLot`/
    `recordErroredCycle` → errored_cycle_count, ONCE `updateState(COMPLETED)`(`:208`) → state. UPDATE.
  - `stopRun`(`:232-251`): 각 RUNNING target `updateState(CANCELED)`(`:242`) → state=CANCELED UPDATE.
  - `stopTarget`(`:260-287`): 해당 target `updateState(CANCELED)`(`:273`) → state UPDATE(형제 target 미변경).
  - `createRun` inject 실패 보상(`:137-144`, run FAILED): 이미 INSERT 된 target 행 state=FAILED UPDATE
    (R6(b) `updateState` co-write 로 처리 — 별도 AC 없이 AC-O9 회귀 빌드로 갈음).
  - **reaper 회수 경로(서비스 우회 — 누락 시 좀비 target).** `RunLeaseReaper.reap`(`RunLeaseReaper.java:41`) →
    `RunLeaseRepository.reclaim`(`JdbcRunLeaseRepository.java:117-126`)은 `RunOrchestrationService.updateState` 를
    **거치지 않고** `tc_sim_run` 을 직접 FAILED 로 쓴다(orchestrator 재시작 등 stale lease 회수 — 본 SPEC 이 겨냥한
    "재시작 후에도 완전한 이력" 시나리오와 정면). reclaim 이 run 을 FAILED 로 전이시킬 때 **같은 tx 안에서** 그 run 의
    비-terminal `tc_sim_run_target` 행도 state=FAILED 로 UPDATE 한다. 미처리 시 run=FAILED 인데 target=RUNNING 인
    "좀비 target" 이 이력에 영구 잔존한다. (`reconcileAndEvictRuns` `:315-319` 는 in-memory run 상태만 DB 로 맞출 뿐
    target 행을 건드리지 않으므로 이 구멍을 메우지 못한다.)
- **R3 (목록 조회 API — orchestrator, DB 출처).** `GET /api/demo-runs` 를 `RunController` 에 추가한다. `tc_sim_run`
  + `tc_sim_run_target` 를 조인해 run 목록을 반환한다(재시작·evict 후에도 DB 에서 나오므로 **완전한 이력**). 정렬은
  `created_at DESC`, 개수는 R7 로 제한. 응답 형상은 기존 `RunResponse`(runId/mode/state/targets[]) 필드 골격에
  `createdAt`·`scenarioId` 를 더한 것(R5)이되, 목록 원소는 in-memory `RunResponse.from(RunAggregate)` 가 아니라
  DB 행에서 조립한다(단건 `from()` 은 aggregate 전용).
  - **R3-주의.** 단건 `getRun(runId)`(`:69-72`)의 in-memory 경로는 **그대로 둔다**(형제 SPEC 의 배리어/reaper 가
    getRun 을 사용 — `LiveMapSimulatorRunFacade`/`DemoRunConnectionReaper`). 목록만 DB 출처로 신설한다.
- **R4 (ui-backend 프록시 + ui-front 목록 소비).**
  - `SimulatorOrchestratorClient.listRuns()` + `HttpSimulatorOrchestratorClient`(GET `/api/demo-runs`,
    `sendJson`+`TypeReference` 패턴 — `HttpSimulatorOrchestratorClient.java:65-71` getScenarios 답습).
  - `LiveMapSimulatorRunFacade.listRuns()` → `LiveMapController` `@GetMapping("/demo-runs")` →
    `ApiResponse<List<SimulatorRunResponse>>`.
  - ui-front `liveMapDemoRunApi.listRuns()` + 훅 `useSimulatorRunList(open)`(모달 열림 동안 폴링). '시뮬레이션 진행
    현황' 을 단일 `activeRunId` 바인딩에서 **목록 렌더**로 전환: run 최신순 그룹(runId·mode·scenario·시작시각·상태
    배지·완료/전체) + 설비별 행. 실행중 run 의 non-terminal target 에만 종료 버튼(기존 disabled 조건 유지).
- **R5 (run 시작시각 + scenario 노출).** `RunResponse`(orchestrator) + `SimulatorRunResponse`(ui-backend) + ui-front
  타입에 `createdAt`(ISO-8601) 와 `scenarioId` 를 append 한다 — **둘 다 현재 응답 DTO 에 없는 신규 필드**다(`RunResponse`
  `RunResponse.java:12-17` = runId/mode/state/targets, `SimulatorRunResponse` `SimulatorRunResponse.java:13-18` 동일,
  ui-front `SimulatorRunResponse` 도 동일 — scenarioId·createdAt 부재). 단건 `RunResponse.from(RunAggregate)` 는
  `RunAggregate.getCreatedAt()`(`RunAggregate.java:60-62`)·`getScenarioId()`(`RunAggregate.java:52-54`)에서, 목록은
  `tc_sim_run.created_at`·`tc_sim_run.scenario_id`(`schema.sql:6,10`)에서 채운다.
  `@JsonIgnoreProperties(ignoreUnknown=true)`(`SimulatorRunResponse.java:12`)라 기존 소비자 무영향(append-only).
  **scenarioId 는 '시뮬레이션 진행 현황' run 그룹의 scenario 표시(올바른 동작·R4)의 유일한 서버 출처다** — 과거/재시작
  run 은 create-side 드롭다운 상태(`LiveMapDemoModal.tsx:797,173-179` `selectedScenario`)로는 scenario 를 복원할 수 없다.
- **R6 (트랜잭션 일관성).** target 영속 쓰기는 대응하는 run-state 쓰기와 **같은 트랜잭션**에 둔다. 원자 대상은
  세 지점이다: (a) **acquire tx** — run+target(state=PENDING) INSERT 원자, (b) **각 상태전이/reclaim tx**
  (`updateState`/`reclaim`) — run+target state UPDATE 원자, (c) **startLot 직후** — target PENDING→RUNNING UPDATE
  (별도 write). **주의(코드 사실): target 의 RUNNING 은 `startLot`(`RunOrchestrationService.java:134`)이 서비스
  계층에서 확정하므로 lease acquire tx 밖이다 — "RUNNING 을 acquire 와 한 tx 로 INSERT" 는 불가**하다(그래서 R2
  createRun 이 PENDING→RUNNING 2-step). `JdbcRunLeaseRepository` 의 `acquire`(`:42-57`)·`updateState`(`:83-90`)·
  `reclaim`(`:118-125`)은 각자 `tx.executeWithoutResult` 로 감싸므로 그 안에 target `jdbc.update` 를 얹으면 co-write
  가 성립한다. 단 `releaseLease`(`:97-99`)·`heartbeat`(`:104-106`)는 명시 tx 밖(bare `jdbc.update`)인데 이 둘은
  target 상태 write 지점이 아니므로 대상 아님. **구현 seam 은 기존 `RunLeaseRepository` 를 확장한다(신규
  `RunTargetRepository` 미신설) — `acquire`/`updateState`/`reclaim` 이 이미 `tx.executeWithoutResult` 로 tx 를 소유해
  그 안에서 target co-write 가 자연히 성립하고, AC-O1/O11/O12 의 InMemory·Testcontainers 검증도 이 seam(단일 repo
  2구현)을 전제하기 때문이다.** Plan 은 시그니처(예: `acquire` 가 target 목록/comm_interface 를 받도록 확장)와 각
  write 지점의 target co-write 위치만 구체화하되, **각 write 지점의 run↔target co-write 원자성**은 SPEC 요구다.
  테스트 결정성을 위해 기존 Jdbc↔InMemory 2구현 seam 패턴(`RunLeaseRepository` 주석 `:9-15`)을 따른다.
- **R7 (목록 개수 제한 — 기본값 명시).** 목록은 `created_at DESC` 로 **기본 200건**만 반환한다(무제한 payload 방지 —
  `tc_sim_run` 은 prune 없이 무기한 증가). 페이징/필터는 Out of Scope. 200 은 Plan/Validate 에서 조정 가능하나 무제한
  반환은 금지.
- **R8 (빈/DDL 등록 — nori-tc 컨벤션).**
  - 신규 orchestrator repo/서비스는 `OrchestratorRuntimeConfiguration` 에 `@Bean` 등록(`:127-133` 패턴). 신규
    `@RestController` 추가는 없음(기존 `RunController` 에 메서드 추가 — `OrchestratorModuleConfiguration` @Import
    재등록 불요). `LiveMapController` 도 기존 컨트롤러에 메서드 추가.
  - DDL 은 **`nori-tc-exec/docs/db_table/patch/postgres_2026_07_06_create_sim_run_target.sql`** 신규 패치 파일 +
    orchestrator `src/test/resources/schema.sql` 미러(CREATE TABLE tc_sim_run_target). sibling(sim_run
    `postgres_2026_06_15_create_sim_run_tables.sql`·scenario `postgres_2026_06_22_*`) 선례대로 `docs/db_table/patch/`
    로 관리한다. 라이브 적용은 **수동 승인 게이트** — nori-tc-exec 는 Flyway 단일 owner 이나(`build.gradle.kts:27`
    플러그인 apply, `:34-36` "라이브 migrate/baseline 은 사용자 승인 게이트"), 신규 테이블의 라이브 nori_tc 반영은
    sibling 관례대로 psql 수동으로 한다. Flyway `db/migration/V*` 편입 여부는 Plan 이 결정(baseline drift 검토).
    적용은 Validate 게이트(AC-D1).

## Constraints

- **C1 (gateway 무수정).** 데모 run 관리는 orchestrator/ui-backend/ui-front 3계층 한정. gateway 코드 변경 0.
- **C2 (기존 실행/종료/연결 동작 불변).** createRun 배리어(`LiveMapSimulatorRunFacade` ALL/EQUIPMENT_ONLY/
  UPSTREAM_ONLY), stopRun/stopTarget, `DemoRunConnectionRegistry`/`DemoRunConnectionReaper` 의 EQP_END 원복 로직은
  건드리지 않는다. 본 SPEC 은 **관측/조회 경로만 확장**. 회귀 0 이 게이트(AC-O10/AC-B5).
- **C3 (목록 = DB, 단건 = in-memory).** 목록은 DB(tc_sim_run+tc_sim_run_target)에서만 조립한다(완전한 이력·재시작
  생존). in-memory `runs` 오버레이 금지(두 출처 혼합 = torn read/복잡도). 실행중 run 의 신선도는 write-path 의 lot
  단위 cadence(카운트가 실제 바뀌는 그 순간 UPDATE) + 프론트 목록 폴링으로 충분하다. 단건 getRun 은 in-memory 유지(R3-주의).
- **C4 (권한 — 신규 매핑 0).** `GET /api/live-map/demo-runs`(목록)는 기존 `GET /demo-runs/{runId}` 와 동일한
  `LIVE_MAP_DEMO_RUN` read 권한 PREFIX(`/api/live-map/demo-runs`, GET)로 이미 커버된다 — 신규 권한/patch 불요
  (형제 R-7 "신규 매핑 0" 선례와 동일). Validate 에서 권한 테스트로 확인.
- **C5 (append-only 필드).** 응답 DTO 변경은 `createdAt`·`scenarioId` append 뿐(R5). 기존 필드 제거/의미변경 금지 —
  `@JsonIgnoreProperties(ignoreUnknown=true)` 소비 계약 보존.
- **C6 (per-target 영속 범위).** 영속 대상은 표시에 쓰이는 state / completed_cycle_count / errored_cycle_count /
  comm_interface 다. `issuedLotRunIds`(디버그용 lot 목록, 테이블 미표시)는 영속하지 않는다 — 목록 응답의 해당 필드는
  이력 run 에 대해 빈 배열. (레이턴시 표본은 ui-backend `SimulatorRunResponse.SimulatorTargetResponse` 에 필드
  자체가 없어 애초에 비대상 — `SimulatorRunResponse.java:22-29` 의 실제 필드는 eqpId/state/commInterface/
  completedCycleCount/erroredCycleCount/issuedLotRunIds 6개뿐.)
- **C7 (전역 스코프).** run 이력은 사용자 귀속 없이 전역(모든 사용자 동일 목록) — 현행 무귀속 모델 유지(tc_sim_run 에
  user 컬럼 없음). per-user 필터는 Out of Scope.

## Acceptance Criteria (기계 검증 가능, 단일 노드)

### orchestrator (nori-tc-simulator)
- [x] **AC-O1.** `createRun(ALL/EQUIPMENT_ONLY, [eqp])` 후, `tc_sim_run_target` 에 해당 run_id 의 target 행이 설비 수
  만큼 존재하고 state=RUNNING, comm_interface 가 요청 target 값과 일치한다(`RunLeaseRepository` seam 의 InMemory 구현
  단위 테스트로 검증 — `:9-15`, Docker 불요).
- [x] **AC-O2.** ONCE 자연완료(`onLotCompleted` errored=false) 후, 해당 (run_id, eqp_key) 행이 state=COMPLETED,
  completed_cycle_count=1 로 영속된다.
- [x] **AC-O3.** 오류 lot(`onLotCompleted` errored=true) 후, 해당 행의 errored_cycle_count 가 1 증가해 영속된다.
- [x] **AC-O4.** `stopRun(runId)` 후, 그 run 의 RUNNING 이던 target 행이 모두 state=CANCELED 로 영속된다.
- [x] **AC-O5.** `stopTarget(runId, eqpA)` 후, eqpA 행만 state=CANCELED, 같은 run 의 eqpB 행은 변경되지 않는다.
- [x] **AC-O6.** `GET /api/demo-runs` 가 `created_at DESC` 정렬로 run 목록을 반환하고, 각 run 이 `createdAt`
  (ISO-8601, `tc_sim_run.created_at` 출처)·`scenarioId`(`tc_sim_run.scenario_id` 출처)와 영속된 per-target
  상세(state/completed/errored/comm_interface)를 포함한다. in-memory `runs` 맵에서 evict 된(=`getRun` 이 404 인)
  run 도 목록에는 DB 출처로 포함된다.
- [x] **AC-O7.** `tc_sim_run` 에 (기본 limit)+N 개 run 이 있을 때 `GET /api/demo-runs` 응답 개수가 기본 limit(200)
  이하다(무제한 반환 아님).
- [x] **AC-O8.** orchestrator ApplicationContext 기동 테스트 PASS — 신규 target repo `@Bean` 이
  `OrchestratorRuntimeConfiguration` 에 등록되어 컨텍스트가 정상 로드된다.
- [x] **AC-O9.** `./gradlew :orchestrator:build -PuseLocalMaven` 전체 PASS(회귀 없음 — Testcontainers 포함이라 로컬 Docker 필요. `-PuseLocalMaven` 필수 — 로컬은 common 아티팩트를 mavenLocal 에서 소비, gradle.properties 명시).
- [x] **AC-O10.** 기존 `getRun(runId)` 단건 응답 회귀 없음 — 기존 orchestrator run lifecycle 테스트 전부 PASS +
  응답에 `createdAt`·`scenarioId` 필드가 포함된다(append-only).
- [x] **AC-O11.** 트랜잭션 원자성(R6) — Testcontainers PG(로컬 Docker 게이트 — orchestrator 기존 `@Testcontainers`
  는 `disabledWithoutDocker` 미설정이라 Docker 미가동 시 build FAIL, AC-6/8 과 동급)에서 `acquire` tx 내부의 target
  INSERT 를 강제 실패시키면 같은 run 의 `tc_sim_run` 행도 커밋되지 않는다(run+target INSERT 원자 롤백 — 실패 후
  `findState(runId)` 가 empty).
- [x] **AC-O12.** reaper 회수 경로(R2/F1) — 추적 중 run 의 lease 를 `reclaim`(stale 회수, run→FAILED)하면 그 run 의
  비-terminal `tc_sim_run_target` 행이 모두 state=FAILED 로 영속된다(좀비 RUNNING target 0). `RunLeaseRepository`
  seam 의 InMemory 구현 단위 테스트로 검증.

### ui-backend (nori-tc-ui-backend)
- [x] **AC-B1.** `HttpSimulatorOrchestratorClient.listRuns()` 가 `GET /api/demo-runs` 를 호출하고 응답을
  `List<SimulatorRunResponse>` 로 역직렬화한다(단위 테스트 — 기존 client 테스트 패턴).
- [x] **AC-B2.** `LiveMapController` `GET /demo-runs` 가 `ApiResponse<List<SimulatorRunResponse>>` 를 반환하고,
  각 원소가 orchestrator 응답의 `createdAt`·`scenarioId` 를 보존해 노출한다(append-only — R5).
- [x] **AC-B3.** `GET /api/live-map/demo-runs`(목록)가 기존 `LIVE_MAP_DEMO_RUN` read 권한으로 게이팅됨을 권한
  테스트로 확인한다(신규 권한 매핑 0 — C4). 미인가 시 차단, 인가 시 200.
- [x] **AC-B4.** ui-backend ApplicationContext 기동 테스트 PASS — `LiveMapSimulatorRunFacade` 시그니처 변경이
  `UiSimulatorConfiguration` 배선과 정합해 컨텍스트가 로드된다.
- [x] **AC-B5.** `./gradlew :ui:adapter:tc-ui-web-adapter:build` 전체 PASS(회귀 없음 — C2).

### ui-front (nori-tc-ui-front)
  > **ui-front 검증 방식 (코드 사실).** 이 레포 테스트 하네스는 `node --test`(package.json `test:unit`)이고
  > vitest/@testing-library/jsdom 이 미설치라(레포 전체 grep — specs 문서에만 언급, `dcop-flow-binding.md:810-811`
  > "jsdom/RTL 미설치" 명시) **훅/컴포넌트 렌더·fetch mock 단위 테스트가 불가능**하다. 따라서 AC-F1~F3 은 순수 함수
  > `buildRunHistoryGroups(runs)`(목록→run 그룹+설비행 변환) 단위 테스트로 검증하고, wire(GET 호출·역직렬화)·훅
  > 폴링·실제 렌더는 tsc(AC-F5) + 실환경 e2e(AC-F6) 게이트로 넘긴다. 신규 테스트 프레임워크 도입은 Out of Scope.
- [x] **AC-F1.** 순수 함수 `buildRunHistoryGroups(runs)` 단위 테스트 PASS(node:test) — 목록 응답을 최신순 run 그룹 +
  설비행으로 변환한다. `liveMapDemoRunApi.listRuns()` 의 `GET /live-map/demo-runs` wire·역직렬화는 tsc(AC-F5) +
  실환경 e2e(AC-F6)로 검증한다(위 박스 사유 — 하네스에 fetch mock 인프라 없음).
- [x] **AC-F2.** 재부착 — **실행중 run 이 이번 세션에서 시작하지 않았어도** '시뮬레이션 진행 현황' 에 표시된다. 순수
  함수 `buildRunHistoryGroups` 단위 테스트로 검증한다(이번 세션 밖에서 시작한 실행중 run 이 포함된 목록 mock 을 입력하면
  그 run 이 그룹 결과에 포함됨을 assert). 훅 `useSimulatorRunList` 의 폴링/enabled·모달 재오픈 동작은 tsc(AC-F5) +
  실환경 e2e(AC-F6) 게이트.
- [x] **AC-F3.** 과거 terminal run 이 최신순(`createdAt` DESC)으로 정렬되고, 각 run 그룹이 scenario·시작시각
  (`createdAt`)을, 각 설비행이 최종 state·사이클 카운트를 담는다 — 순수 함수 `buildRunHistoryGroups` 단위 테스트로
  검증한다(mock 목록 입력 → 정렬 순서 + `createdAt`·`scenarioId` + 설비행 값 assert). 실제 렌더 표시는 tsc(AC-F5, 두
  필드가 타입에 있어야 성립) + 실환경 e2e(AC-F6).
- [~] **AC-F4.** 모달을 닫았다 다시 열어도 실행중 run 이 유지 표시된다(닫기의 stop 미호출은 기 수정 — `:225-241`).
  부분 — 데이터 출처가 서버 목록(`useSimulatorRunList`)이라 로컬 activeRunId 바인딩 폐기로 설계상 재부착 성립(코드 인스펙션 확인),
  단위 테스트(AC-F2)로 목록 내 실행중 run 포함 검증. 미충족: 실렌더 닫기-재오픈 재부착의 브라우저 확인은 AC-F6 e2e 게이트(미수행).
- [x] **AC-F5.** `npx tsc --noEmit` + `eslint .` + `npm run test:unit` 전부 PASS(출력 인용).

### DB / 실환경
- [x] **AC-D1.** DDL 패치 `nori-tc-exec/docs/db_table/patch/postgres_2026_07_06_create_sim_run_target.sql` 작성 +
  orchestrator `src/test/resources/schema.sql` 에 동일 정의 미러. 라이브 DB 적용(psql)은 Validate 게이트.
  Validate 적용 완료(2026-07-06): 라이브 nori_tc(192.168.0.150:16432)에 node01 경유 임시 psql pod 로 적용 →
  `BEGIN/CREATE TABLE/CREATE INDEX/COMMIT` + `\d public.tc_sim_run_target` 로 9컬럼·PK·FK(ON DELETE CASCADE,
  →tc_sim_run)·uk(run_id,eqp_key)·CHECK(PENDING/RUNNING/COMPLETED/CANCELED/FAILED)·ix_run_id 전부 DDL 정의와 일치 확인.
- [~] **AC-F6.** 실환경 e2e — 실 orchestrator+ui-backend+ui-front 기동 상태에서 데모 run 실행 → 모달 닫고 재오픈 →
  실행중 run 재표시 확인, 자연완료 후 과거 이력으로 상세와 함께 잔존 확인(Playwright + `page.on('pageerror')` error 0).
  미수행(사용자 결정, 2026-07-06): 3-서비스 풀스택 로컬 기동 + 로그인 데모 세션 + 실 gateway/설비 연결로 자연완료
  사이클까지 재현해야 해 무겁고, 기능이 아직 미커밋이라 라이브 tc.noricloud.org 에도 미반영. e2e spec
  (`tests/e2e/live-map/live-map-demo-run-reattach.spec.ts`) 작성 완료 + 목록 로직은 자동 테스트(AC-F1~F3/O6/B1~B2)로 검증됨.
  실환경 확보 시 별도 세션에서 실행 필요.

## Out of Scope

- 데모 run 실행/종료/연결(EQP_START/END) 로직 변경 — 형제 SPEC 관할(C2). 본 SPEC 은 관측/조회만.
- run 이력 prune/retention 정책(무기한 보존 = 현행 tc_sim_run 동작 유지). 대량 누적 시 archival 은 별도 과제.
- per-user 이력 스코프/필터, 목록 페이징(C7, R7).
- `issuedLotRunIds` / latency 표본의 영속·표시(C6).
- 단건 `getRun` 을 DB 출처로 전환(in-memory 유지 — R3-주의).
- ui-front 훅/컴포넌트 렌더 단위 테스트용 신규 프레임워크(vitest/@testing-library/jsdom) 도입 — 현행 `node --test`
  하네스 유지, DOM/렌더 시나리오는 e2e(AC-F6). AC-F1~F3 은 순수 함수 검증(AC 박스).
- gateway 코드 변경(C1).

## 다음 단계 진입 프롬프트

### Plan 진입 (새 세션, Plan Mode)
```
@nori-tc-ui-backend/.claude/specs/live-map-demo-run-history.md 보고 구현 계획 수립.
영향 파일(3레포: nori-tc-simulator orchestrator / nori-tc-ui-backend / nori-tc-ui-front + nori-tc-exec DDL patch) /
단계 / 위험 / 롤백 방법 포함. 실행 / 코드 수정 금지.
설계 결정은 SPEC 에 박혀 있음(번복 금지, 구체화만): per-target 영속 테이블 tc_sim_run_target(R1),
write-path 는 synchronized(run) 내부 상태전이 지점(R2), 목록=DB 출처·단건=in-memory 유지(C3/R3),
트랜잭션 co-write + seam=RunLeaseRepository 확장(R6), 목록 기본 200건(R7), 신규 권한 0(C4),
createdAt·scenarioId append-only(R5), gateway/실행로직 무수정(C1/C2).
Plan 이 정할 것: (1) RunLeaseRepository 확장 시그니처(acquire 가 target 목록/comm_interface 를 받는 방식 — AC-O11
원자성상 target INSERT 가 acquire tx 안이어야 하므로 별도 메서드 추가 아닌 acquire 확장이 강제되고, 이는 기존 lease
acquire 테스트 호출부 수정을 수반: AtomicLeaseRejectIntegrationTest / OrchestratorCrashLeaseReclaimIntegrationTest /
JdbcReleaseLeasePartialUniqueIntegrationTest)와 각 write 지점의 target co-write 위치, (2) 목록 조회 쿼리
(tc_sim_run⋈tc_sim_run_target⋈lease)와 DB 행→RunResponse 조립 경로(단건 from() 과 분리), (3) 프론트 '시뮬레이션 진행 현황'
목록 렌더 구조(run 그룹 + 설비행)와 activeRunId 폐기/축소 여부, (4) 기존 테스트(getRun 단건, 배리어/reaper) 영향.
산출물: nori-tc-ui-backend/.claude/specs/live-map-demo-run-history-plan.md 에 저장.
```

### Implement 진입 (새 세션)
```
@nori-tc-ui-backend/.claude/specs/live-map-demo-run-history.md
@nori-tc-ui-backend/.claude/specs/live-map-demo-run-history-plan.md 둘 다 읽고 구현.
AC-O1~O12 / AC-B1~B5 / AC-F1~F5 / AC-D1 각각에 실패 테스트 먼저(AC-F6 은 실환경 수동/e2e).
Plan 의 seam/쿼리/UI 결정 엄수. gateway·실행로직 무수정(C1/C2), 신규 orchestrator repo @Bean 등록(R8),
DDL 패치 + schema.sql 미러(R8/AC-D1), createdAt·scenarioId append-only(R5), seam=RunLeaseRepository 확장(R6).
산출물: nori-tc-ui-backend/.claude/specs/live-map-demo-run-history-dev-report.md 에 변경 파일 / 테스트 결과 / 결정 기록.
```

### Validate 진입 (새 세션)
```
@nori-tc-ui-backend/.claude/specs/live-map-demo-run-history.md
@nori-tc-ui-backend/.claude/specs/live-map-demo-run-history-plan.md
@nori-tc-ui-backend/.claude/specs/live-map-demo-run-history-dev-report.md
셋 다 읽고 AC 전부 + Plan 결정 충족 검증. 라이브 DDL 적용(AC-D1)과 실환경 e2e(AC-F6) 게이트 확인.
통과 항목만 [x] 로 업데이트.
```

# DEV REPORT: live-map 데모 run 완전한 이력 + 실행중 run 재부착

> SDD feature slug: `live-map-demo-run-history` · 단계: **Implement** (2026-07-06)
> 입력: `live-map-demo-run-history.md`(SPEC) + `live-map-demo-run-history-plan.md`(Plan)
> 커밋 없음(사용자 별도 지시). 4 레포 working tree 변경만.

---

## 1. 변경 파일 목록 (레포별, 신규 N / 수정 M)

### nori-tc-exec (`main`) — DDL
| 파일 | 구분 |
|---|---|
| `docs/db_table/patch/postgres_2026_07_06_create_sim_run_target.sql` | N |

### nori-tc-simulator/orchestrator (`feature/logging-test-console-only`)
| 파일 | 구분 | 요지 |
|---|---|---|
| `.../adapter/lease/RunTargetSeed.java` | N | acquire seed(eqpKey+commInterface) |
| `.../adapter/lease/RunTargetRecord.java` | N | DB 출처 per-target 이력 레코드 |
| `.../adapter/lease/RunHistoryRecord.java` | N | DB 출처 run 이력 레코드 |
| `.../adapter/lease/RunLeaseRepository.java` | M | acquire 시그니처 `List<RunTargetSeed>` + `updateTarget`/`listRunHistory` 추가 |
| `.../adapter/lease/JdbcRunLeaseRepository.java` | M | acquire target INSERT / updateState·reclaim co-write / updateTarget / listRunHistory 2-step |
| `.../adapter/lease/InMemoryRunLeaseRepository.java` | M | RunRow mode/scenarioId/createdAt + TargetRow 맵 + 동일 4메서드 |
| `.../application/RunOrchestrationService.java` | M | createRun seed 조립 + persistTarget 호출(startLot/self-loop/ONCE/stopRequested/stopTarget) + listRunHistory 위임 |
| `.../domain/TargetState.java` | M | FAILED 주석 갱신(reclaim/보상 co-write 가 set) |
| `.../api/RunController.java` | M | `@GetMapping` 목록(DEFAULT_HISTORY_LIMIT=200) |
| `.../api/dto/RunResponse.java` | M | createdAt·scenarioId append + `fromHistory` 신설 |
| `.../api/dto/TargetResponse.java` | M | `fromRecord` factory(latency empty, issuedLotRunIds List.of()) |
| `src/test/resources/schema.sql` | M | `tc_sim_run_target` 미러 |
| `src/test/.../AtomicLeaseRejectIntegrationTest.java` | M | acquire 호출부 seed 화 + import |
| `src/test/.../OrchestratorCrashLeaseReclaimIntegrationTest.java` | M | acquire 2곳 seed 화 + TRUNCATE 에 target 추가 |
| `src/test/.../JdbcReleaseLeasePartialUniqueIntegrationTest.java` | M | acquire 2곳 seed 화 + TRUNCATE 에 target 추가 |
| `src/test/.../RunTargetPersistenceTest.java` | N | AC-O1~O5,O12 InMemory 단위 |
| `src/test/.../JdbcRunTargetHistoryIntegrationTest.java` | N | AC-O11,O6,O7 Testcontainers |
| `src/test/.../RunHistoryEndpointIntegrationTest.java` | N | AC-O6 엔드포인트·AC-O10 단건·AC-O8 context load |

### nori-tc-ui-backend (`feature/deploy-bulk-register-perf`)
| 파일 | 구분 | 요지 |
|---|---|---|
| `.../web/simulator/SimulatorRunResponse.java` | M | top-level createdAt·scenarioId append |
| `.../web/simulator/SimulatorOrchestratorClient.java` | M | `listRuns()` 추가 |
| `.../web/simulator/HttpSimulatorOrchestratorClient.java` | M | `listRuns` = GET /api/demo-runs (TypeReference 배열) |
| `.../web/simulator/LiveMapSimulatorRunFacade.java` | M | `listRuns()` 위임(생성자 무변경) |
| `.../web/controller/LiveMapController.java` | M | `@GetMapping("/demo-runs")` 목록 |
| `src/test/.../HttpSimulatorOrchestratorClientTest.java` | M | 호출부 6-arg + AC-B1 신규 테스트 |
| `src/test/.../controller/LiveMapControllerSimulatorRunTest.java` | M | 호출부 6-arg + AC-B2 신규 테스트 |
| `src/test/.../simulator/DemoRunConnectionReaperTest.java` | M | 호출부 6-arg |
| `src/test/.../simulator/LiveMapSimulatorRunFacadeTest.java` | M | 호출부 6-arg (3곳) |
| `src/test/.../security/LiveMapDemoRunListPermissionTest.java` | N | AC-B3 권한 |

### nori-tc-ui-front (`feature/deploy-bulk-register-perf`)
| 파일 | 구분 | 요지 |
|---|---|---|
| `src/features/live-map/api/live-map-demo-run.api.ts` | M | createdAt·scenarioId + `listRuns()` |
| `src/features/live-map/hooks/useSimulatorRunList.ts` | N | 목록 폴링 훅 |
| `src/features/live-map/lib/live-map-demo-run-history.ts` | N | 순수 함수 `buildRunHistoryGroups` |
| `src/features/live-map/components/LiveMapDemoModal.tsx` | M | 단건 바인딩 → 목록 렌더 전환(§3.3) |
| `tests/unit/live-map-demo-run-history.test.ts` | N | AC-F1/F2/F3 |
| `tests/e2e/live-map/live-map-demo-run-reattach.spec.ts` | N | AC-F6 재부착 |
| `tests/e2e/modal/modal-visual-sweep-livemap.spec.ts` | M | 목록 mock(배열+createdAt/scenarioId) |
| `tests/e2e/live-map/live-map-scenario-upload.spec.ts` | M | 목록 mock(배열+createdAt/scenarioId) |
| `src/i18n/locales/{ko,en,vi}.json` | M | `livemap.demo.runStartedAt` 키 추가 |

---

## 2. AC 별 테스트·결과

### orchestrator (nori-tc-simulator)
- **AC-O1** `[x]` RunTargetPersistenceTest#createRunPersistsRunningTargetsWithCommInterface — target 수=설비수, RUNNING, commInterface 일치.
- **AC-O2** `[x]` #onceNaturalCompletionPersistsCompletedWithCycleCount — COMPLETED, completed=1.
- **AC-O3** `[x]` #erroredLotPersistsErroredCycleCount — errored=1, completed=0.
- **AC-O4** `[x]` #stopRunPersistsAllRunningTargetsCanceled — 전 RUNNING CANCELED(updateState co-write).
- **AC-O5** `[x]` #stopTargetPersistsOnlyThatTargetCanceled — eqpA CANCELED, eqpB RUNNING.
- **AC-O12** `[x]` #reclaimPersistsNonTerminalTargetsFailed — 비-terminal FAILED, 기 terminal 불변(anti-vacuous).
- **AC-O11** `[x]` JdbcRunTargetHistoryIntegrationTest#acquireRollsBackRunWhenTargetInsertFails — comm_interface 40자→target INSERT 실패→run 행 미커밋(Testcontainers PG).
- **AC-O6** `[x]` #listRunHistoryReturnsCreatedAtDescWithPerTargetDetail(created_at DESC + per-target + DB 출처) + RunHistoryEndpointIntegrationTest#listEndpointIncludesDbSourcedRunNotInMemory(GET /api/demo-runs 가 in-memory 없는 run 포함, getRun 404).
- **AC-O7** `[x]` #listRunHistoryCapsAtLimit — limit+3 개→limit 반환.
- **AC-O8** `[x]` RunHistoryEndpointIntegrationTest @SpringBootTest context load(신규 @Bean 0 — 컨텍스트 정상 기동).
- **AC-O10** `[x]` #getRunSingleIncludesCreatedAtAndScenarioId — POST 후 GET 단건에 createdAt/scenarioId(append-only).
- **AC-O9** `[x]` `./gradlew :orchestrator:build -PuseLocalMaven` 전체 PASS(Docker 가동, Testcontainers 포함). `-PuseLocalMaven` 필수 — 로컬에선 common 아티팩트(tc-common-simulator-contract)를 mavenLocal 에서 소비하므로 플래그 없이는 `:runtime` 의존성 해결 실패(gradle.properties 명시). 신규+영향 테스트 6클래스 강제 재실행(`--rerun-tasks`) 관측:
```
> Task :orchestrator:test
BUILD SUCCESSFUL in 1m 49s
13 actionable tasks: 13 executed
```

### ui-backend (nori-tc-ui-backend)
- **AC-B1** `[x]` HttpSimulatorOrchestratorClientTest#listRunsGetsDemoRunsPathAsArray — GET /api/demo-runs 배열 역직렬화 + createdAt/scenarioId/per-target.
- **AC-B2** `[x]` LiveMapControllerSimulatorRunTest#listSimulatorRunsReturnsApiResponseListPreservingCreatedAtAndScenarioId — ApiResponse<List> + 필드 보존.
- **AC-B3** `[x]` LiveMapDemoRunListPermissionTest — LIVE_MAP_DEMO_RUN_READ(PREFIX /api/live-map/demo-runs GET)로 목록 GET 커버(미보유 차단·보유 통과, 신규 매핑 0).
- **AC-B4** `[x]` (인스펙션+build) facade 생성자 무변경(메서드만 추가) → UiSimulatorConfiguration 배선 무영향. adapter build 의 테스트 스위트 PASS.
- **AC-B5** `[x]` `./gradlew :ui:adapter:tc-ui-web-adapter:build -PuseLocalMaven` 전체 PASS:
```
> Task :ui:adapter:tc-ui-web-adapter:check
> Task :ui:adapter:tc-ui-web-adapter:build
BUILD SUCCESSFUL in 47s
15 actionable tasks: 5 executed, 10 up-to-date
```

### ui-front (nori-tc-ui-front)
- **AC-F1** `[x]` live-map-demo-run-history.test.ts(변환 단위 — 빈 목록/targets 누락 방어). wire 는 AC-F6.
- **AC-F2** `[x]` #세션 밖 실행중 run 이 그룹 결과에 포함(재부착).
- **AC-F3** `[x]` #createdAt DESC 정렬 + createdAt/scenarioId + 설비행 값·완료/전체.
- **AC-F4** `[~]` 목록 출처 렌더로 자연 성립(로컬 activeRunId 데이터 바인딩 폐기). 실렌더 확인은 AC-F6 e2e(Validate).
- **AC-F5** `[x]` `npx tsc --noEmit`(무출력 PASS) + `eslint .`(0 errors, 20 pre-existing warnings — 무관 e2e 스펙의 unused eslint-disable) + `npm run test:unit`:
```
1..170
# tests 170
# pass 170
# fail 0
# duration_ms 1880.974
```
- **AC-F6** `[~]` e2e spec 작성 완료(live-map-demo-run-reattach.spec.ts + 기존 2 spec mock 을 목록 배열 형태로 갱신). 실환경 실행(page.on('pageerror') error 0)은 Validate 게이트.

### DB / 실환경
- **AC-D1** `[~]` DDL patch 파일 + schema.sql 미러 작성 완료(Testcontainers 가 미러로 전 통합테스트 통과 → 스키마 유효성 검증). 라이브 nori_tc psql 적용은 Validate 수동 승인 게이트.

---

## 3. 구현 결정·근거 (Plan 과 달라진 점 포함)

- **persistTarget 헬퍼 도입.** Plan D2 의 updateTarget 호출을 5 지점(startLot / self-loop / ONCE / stopRequested / stopTarget)에서 반복하므로 서비스 private 헬퍼 `persistTarget(run, target)` 로 추출(현재 in-memory state/counts 를 읽어 leaseRepository.updateTarget 위임). CLAUDE.md 11(3회+ 반복 시 추출) 부합. Plan 결정 번복 아님(구체화).
- **stopRun 은 updateTarget 직접 호출 안 함.** Plan D3 의 updateState co-write(비-terminal→CANCELED)로 AC-O4 충족. 중복 write 회피. finishRunIfAllTargetsTerminal 의 updateState 는 target 이 이미 terminal 이라 co-write no-op.
- **AC-O11 강제 실패 수단** = comm_interface VARCHAR(20) 초과(40자). DuplicateKeyException 이 아닌 DataIntegrityViolation 이라 acquire 밖으로 전파 → tx 롤백 → run 행 미커밋을 결정적으로 재현.
- **기존 테스트 TRUNCATE 확장.** 신규 FK(tc_sim_run_target→tc_sim_run)로 인해 `TRUNCATE tc_sim_run` 이 FK 참조 오류 → 2 개 기존 lease 테스트 + 신규 2 테스트의 TRUNCATE 에 `tc_sim_run_target` 추가. 내 FK 가 강제한 필수 수정(surgical).
- **ui-front runTargetColumns → 팩토리.** 연결유형이 run 그룹 단위라 `buildRunTargetColumns(connectionTypeLabel)` 로 파라미터화(컬럼 구성 동일 재사용). activeRunId 데이터 바인딩 폐기, handleStopTarget(runId, eqpId) 로 그룹 runId 사용. Plan §3.3 준수.
- **i18n 키 1개 신설** `livemap.demo.runStartedAt`(ko/en/vi) — 그룹 헤더 시작시각 라벨. 기존 `colScenario` 재사용(scenario 라벨). i18n-vi-parity 테스트 PASS.
- **부수 관찰(무관):** ui-front working tree 의 LiveMapBoard.tsx / LiveMapCommLogPanel.tsx 는 세션 시작 전부터 M 상태(이번 작업 무관) — 건드리지 않음.

---

## 4. 미완료 / Validate 게이트

- **AC-D1 라이브 DDL** — `postgres_2026_07_06_create_sim_run_target.sql` 을 라이브 nori_tc 에 psql 적용(node01 경유 임시 psql pod, memory `reference_db_ddl_patch_via_transient_psql_pod`). Flyway 미편입(D7).
- **AC-F6 실환경 e2e** — 실 orchestrator+ui-backend+ui-front 기동 후 reattach 스펙 실행 + page.on('pageerror') error 0. worst-case mock + 실서버 스크린샷(memory `feedback_visual_e2e_real_env_verify`).
- **AC-F4** — 실렌더 재부착 확인은 AC-F6 e2e 로 최종 검증(순수 함수·설계상 성립은 확인됨).

---

## 5. Validate 결과 (2026-07-06, 독립 재검증)

> Dev agent 자기보고를 도구(Read/Grep/Bash 빌드·테스트·psql)로 직접 재확인. 모든 PASS 는 출력 인용 근거 보유.

### 코드 정합성 (§1 인스펙션)
- D1~D8 전부 실제 코드 반영 확인: acquire(List<RunTargetSeed>) 원자 INSERT / updateTarget bare UPDATE /
  updateState·reclaim co-write(TERMINAL→COMPLETED·CANCELED→CANCELED·FAILED→FAILED, `toTargetState` switch) /
  listRunHistory 2-step(JOIN+LIMIT 금지 주석) / RunResponse createdAt(String)·scenarioId append + fromHistory /
  신규 @Bean·@RestController 0(RunController·LiveMapController 메서드 추가) / DDL patch=schema.sql 미러 동일 /
  ui-front 순수함수(buildRunHistoryGroups)·훅 프레임워크 미도입.
- MUST NOT: C1(gateway repo clean) / C2(실행·종료·연결 로직 불변 — persistTarget·co-write 관측 write만 추가,
  배리어/EQP_END/abort 무변경) / C4(신규 권한 매핑 0 — 기존 PREFIX GET 커버, 권한 테스트 통과) / C5(append-only).
- acquire 호출부 6곳(main 1 + 테스트 5파일) 전부 신규 시그니처 반영(grep 확인).

### 자동 테스트 (§2 게이트 — 재실행)
- **orchestrator** `./gradlew :orchestrator:build -PuseLocalMaven --rerun-tasks` → `BUILD SUCCESSFUL in 1m 27s`
  (16 tasks executed, Testcontainers PG 기동). 테스트 결과 XML: RunTargetPersistenceTest 6/0/0(AC-O1~O5,O12),
  JdbcRunTargetHistoryIntegrationTest 3/0/0(AC-O11,O6,O7), RunHistoryEndpointIntegrationTest 2/0/0(AC-O6,O8,O10),
  회귀 AtomicLeaseReject/OrchestratorCrashLeaseReclaim/JdbcReleaseLeasePartialUnique 각 1/0/0. → AC-O1~O12 PASS.
- **ui-backend** `./gradlew :ui:adapter:tc-ui-web-adapter:build -PuseLocalMaven` → `BUILD SUCCESSFUL in 27s`.
  HttpSimulatorOrchestratorClientTest 13/0/0(listRunsGetsDemoRunsPathAsArray), LiveMapControllerSimulatorRunTest 2/0/0,
  LiveMapDemoRunListPermissionTest 2/0/0(with/without demoRunRead). → AC-B1~B5 PASS.
- **ui-front** `npx tsc --noEmit`(exit 0) + `eslint .`(0 errors, 20 pre-existing warnings 무관 e2e 스펙) +
  `npm run test:unit`(`# pass 170 # fail 0`). → AC-F1~F3/F5 PASS.

### 라이브 게이트 (§3/§4)
- **AC-D1 [x]** 라이브 nori_tc(192.168.0.150:16432) 에 node01 경유 임시 psql pod 로 DDL 적용 →
  `BEGIN/CREATE TABLE/CREATE INDEX/COMMIT`, `\d public.tc_sim_run_target` 로 9컬럼·PK·FK(ON DELETE CASCADE)·
  uk(run_id,eqp_key)·CHECK(5상태)·ix_run_id 전부 정의 일치 확인.
- **AC-F4 [~] / AC-F6 [~]** 실환경 3-서비스 풀스택 e2e 미수행(사용자 결정). spec 작성 완료 + 목록/재부착 로직은
  자동 테스트로 검증. 실환경 확보 시 별도 세션 실행 필요.

### 판정
- **[x] 24개 중 22개** (AC-O1~O12, AC-B1~B5, AC-F1~F3/F5, AC-D1) — 자동 게이트·라이브 DDL 모두 통과.
- **[~] 2개** (AC-F4, AC-F6) — 실환경 e2e 게이트 미수행. 억지 [x] 없음.
- 누락/위반 발견 0. SPEC 의 SDD 진행 상태 = Validate 완료.

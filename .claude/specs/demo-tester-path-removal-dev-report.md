# DEV REPORT: demo-tester-path-removal (M7 simulator cutover)

> 입력: `.claude/specs/demo-tester-path-removal.md` + `.claude/specs/demo-tester-path-removal-plan.md`
> 단계: Implement. 3개 레포(ui-backend / ui-front / nori-tc-exec) cutover 구현.
> 모든 file:line 은 편집 직전 Read 로 재확인했고, Plan 작성 시점과 동일했다.

---

## 0. Implement 세션에서 새로 발견한 결함 (Plan §0 D1~D5 외 추가)

Plan 이 잡은 D1~D5(고아 DTO / facade properties / 주석 매칭 F-D·F-E)는 모두 적용했다. 그 위에
**편집 직전 grep 으로 AC5/AC6 가 작성 그대로는 달성 불가**임을 확인하고, AC6 처리는 사용자 결정을 받았다.

| # | 발견 | 근거(file:line) | 처리 |
|---|---|---|---|
| **A1** | **AC6 `/api/demo-runs` grep 0건 달성 불가** — 보존 대상 신규 orchestrator 클라이언트가 통합 simulator(:18090)의 실제 REST 엔드포인트 `/api/demo-runs` 로 **아웃바운드 호출**한다. SPEC 괄호("신규 /demo-runs는 /api/live-map prefix라 미매칭")는 이 아웃바운드 URL 을 놓친 오류 | `HttpSimulatorOrchestratorClient.java:42,47,52,57,62`(실제 sendJson 코드) + `SimulatorOrchestratorClient.java:11-24`·`SimulatorCreateRunRequest.java:6`(javadoc) | **사용자 결정: AC6 패턴 좁힘**. AC6 의 의도("레거시 Spring 매핑 제거")는 `@RequestMapping("/api/demo-runs")` 로 좁힌 grep 으로 검증(0건 PASS). literal `/api/demo-runs` 는 보존 코드라 잔존 — 제거 시 회귀 |
| **A2** | **AC5 가 보존 simulator 파일의 stale javadoc 을 매칭** — 삭제될 클래스(`DemoTesterClient`/`HttpDemoTesterClient`/`UiDemoTesterConfiguration`)를 `{@code …}` 로 참조하는 주석 3곳 | `SimulatorOrchestratorClient.java:6`, `HttpSimulatorOrchestratorClient.java:20`, `UiSimulatorConfiguration.java:21` | D3 와 동일 성격(주석 매칭) → 3개 주석 정리(삭제될 클래스 참조 제거). `{@code}` 라 컴파일 의존은 아니나 정리해야 AC5 0건 |
| **A3** | **AC7 가 보존 ui-front 파일의 stale 주석을 매칭** — `live-map-demo-run.api.ts:7` 주석이 `live-map-demo.api.ts` 를 언급 → AC7 의 `live-map-demo\.api` 매칭 | `live-map-demo-run.api.ts:7` | D3/F-E 와 동일 성격 → 주석 정리(cutover 완료 반영). AC7 0건 달성 |
| **A4** | **AC3 toolchain 표기 불일치** — SPEC 은 `pnpm build` 라 하나 ui-front 실제 락파일은 `package-lock.json`(npm). corepack pnpm 은 사전 `pnpm install` 재귀 호출에서 PATH pnpm 미존재로 실패 | `package-lock.json` 존재, `pnpm-lock.yaml` 부재 | 동일 build 스크립트(`tsc -b && vite build`)를 `npm run build` 로 실행. AC3 의 검증 대상(타입체크+vite 빌드 통과)은 동일하게 충족 |

---

## 1. 변경 파일 목록 (레포별)

### 1-A. nori-tc-ui-backend (`ui/adapter/tc-ui-web-adapter`) — 1 커밋

#### 삭제 (git rm)
- `controller/DemoController.java`
- `controller/DemoInternalController.java`
- `config/UiDemoTesterConfiguration.java`
- `config/UiDemoTesterProperties.java`
- `config/UiInternalApiProperties.java`
- `dto/request/DemoRunCreateRequest.java` (D1/F-A)
- `dto/request/DemoTargetStartRequest.java` (D1/F-A)
- `dto/request/LiveMapDemoJobCreateRequest.java` (D1/F-B)
- `demo/` 패키지 13파일: `DemoTesterClient`, `HttpDemoTesterClient`, `DemoTesterCreateRunRequest`, `DemoTesterCreateJobsRequest`, `DemoTesterTargetStartRequest`, `DemoRunResponse`, `DemoTargetResponse`, `DemoJobCreateResponse`, `DemoJobDetailResponse`, `DemoJobSummaryResponse`, `DemoJobEventResponse`, `DemoJobRejectedEquipmentResponse`, `DemoScenarioCatalogItemResponse`
- `src/test/.../controller/DemoControllerTest.java`
- `src/test/.../demo/HttpDemoTesterClientTest.java`

#### 외과적 편집
- `controller/LiveMapController.java` — 레거시 메서드 5종(getDemoScenarios/createDemoJobs/getDemoJobs/getDemoJob/cancelDemoJob) + `DemoTesterClient` 필드/ctor 파라미터/null체크 + demo/ DTO·LiveMapDemoJobCreateRequest import + `HttpStatus` import + isEnabled 분기 7곳 + `simulatorDisabled()` 헬퍼 제거. 스테일 주석 헤더(:108) 갱신. **`/demo-runs*` 6 엔드포인트 보존**.
- `config/UiSimulatorConfiguration.java` — `liveMapSimulatorRunFacade` @Bean 의 `UiSimulatorProperties properties` 파라미터/arg 제거(D2 연쇄). 클래스 javadoc 의 `tc.ui.simulator.enabled`·`UiDemoTesterConfiguration` 참조 제거(D3/F-D + A2). `simulatorOrchestratorClient` @Bean·`@EnableConfigurationProperties(UiSimulatorProperties.class)` 보존.
- `simulator/LiveMapSimulatorRunFacade.java` — `isEnabled()` + `properties` 필드/ctor 파라미터/null체크/import 제거(D2). 클래스 javadoc 토글 서술 제거.
- `config/UiSimulatorProperties.java` — `enabled` 필드/`isEnabled()`/`setEnabled()` 제거 + javadoc 정리(D5). `baseUrl/connectTimeoutMs/readTimeoutMs/reaperIntervalMs` 보존.
- `config/UiWebAdapterModuleConfiguration.java` — import `DemoController`(:12)·`DemoInternalController`(:13) 제거. @Import 배열 `UiDemoTesterConfiguration.class`·`DemoController.class`·`DemoInternalController.class` 제거. `UiSimulatorConfiguration`/`LiveMapController` 보존.
- `security/UiSecurityConfig.java` — CSRF `.ignoringRequestMatchers("/api/internal/demo/**")` + 주석, authorize permitAll GET/POST `/api/internal/demo/**` + 주석 제거.
- `simulator/HttpSimulatorOrchestratorClient.java` — javadoc 의 `{@code HttpDemoTesterClient}` 참조 제거(A2/AC5).
- `simulator/SimulatorOrchestratorClient.java` — javadoc 의 `{@code DemoTesterClient}` 참조 제거(A2/AC5).
- `src/test/.../controller/LiveMapControllerSimulatorRunTest.java` — `DemoTesterClient` mock/import/ctor arg + isEnabled stub + verifyNoInteractions + 미사용 import(`any`/`never`/`verifyNoInteractions`) 제거. 503 테스트(`disabledToggleReturns503AndSkipsOrchestrator`) 삭제. 라우팅 테스트는 보존(메서드명 `startSimulatorRunRoutesToOrchestrator` 로 갱신, `verify(simulatorRunFacade).startRun(...)` 단언 유지).
- `src/test/.../simulator/LiveMapSimulatorRunFacadeTest.java` — ctor `new UiSimulatorProperties(),` arg + import 제거(D2). 나머지 11 케이스 무수정.

#### @Import 두 줄 동반 제거 (헥사고날 컨벤션)
| 제거 빈 | import 문 | @Import 배열 |
|---|---|---|
| `DemoController` | :12 제거 | 제거 |
| `DemoInternalController` | :13 제거 | 제거 |
| `UiDemoTesterConfiguration` | (동일 package, import 없음) | 제거 |

`UiDemoTesterProperties`/`UiInternalApiProperties` 는 삭제된 `UiDemoTesterConfiguration` 의 `@EnableConfigurationProperties` 로만 등록됐던 것 → config 삭제로 자동 해소. demo/ 빈은 `UiDemoTesterConfiguration` @Bean 으로만 등록 → 해소.

### 1-B. nori-tc-ui-front (`src/features/live-map`) — 1 커밋

#### 삭제 (git rm)
- `api/live-map-demo.api.ts`
- `hooks/useLiveMapDemoJobs.ts`
- `hooks/useCancelLiveMapDemoJob.ts`
- `hooks/useLiveMapDemoScenarios.ts`
- `hooks/useCreateLiveMapDemoJobs.ts`

#### 외과적 편집
- `lib/live-map-demo.ts` — `buildLiveMapDemoRunRows` + `DEMO_ROW_STAGGER_TICKS` 상수 + import `LiveMapDemoRunRow`·`LiveMapDemoScenarioOption` 제거. 트리/타겟 빌더(`buildLiveMapDemoTree`/`collect*`/`filter*`/`merge*`) 보존.
- `types/live-map.types.ts` — `LiveMapDemoScenarioOption`·`LiveMapDemoRunRow` 제거. `LiveMapDemoTreeNode`/`LiveMapDemoTargetEquipment`/`LiveMapDemoTestType`/`LiveMapEquipmentNode` 보존.
- `mocks/live-map.mock.ts` — `LIVE_MAP_DEMO_SCENARIO_OPTIONS` + import `LiveMapDemoScenarioOption` 제거. `LIVE_MAP_EQUIPMENT_NODES` 보존.
- `hooks/useSimulatorRunScenarios.ts` — 주석의 `useLiveMapDemoScenarios` 참조 제거(D3/F-E).
- `api/live-map-demo-run.api.ts` — 주석의 `live-map-demo.api.ts` 참조·병행/cutover 서술 제거(A3/AC7).
- `tests/live-map-demo.test.ts` (루트) — run-row 케이스(`demo run row…`) + import `buildLiveMapDemoRunRows`·`LIVE_MAP_DEMO_SCENARIO_OPTIONS` 제거. 트리/타겟 7케이스 보존.

### 1-C. nori-tc-exec (config only) — 1 커밋

- `apps/tc-ui-backend-app/config/tc-ui-backend.properties` (tracked) — `tc.ui.backend.internal-api.header-name`·`header-value` + 주석, `tc.ui.demo-tester.base-url` + 주석 제거.
- `apps/tc-ui-backend-app/config/tc-ui-backend.local.properties` (**git-ignored**, 로컬만) — `tc.ui.backend.internal-api.header-value` override + `tc.ui.simulator.enabled` + 주석 제거. `tc.ui.simulator.base-url` 보존.

---

## 2. 결정 적용 결과 (Plan §7 D1~D5)

| ID | 결정 | 적용 결과 |
|---|---|---|
| D1 | 고아 DTO 3개 삭제 | DemoRunCreateRequest / DemoTargetStartRequest / LiveMapDemoJobCreateRequest 삭제 완료. src 참조 0 확인 |
| D2 | facade `properties` 제거 + 호출 2곳 동기 | facade 필드/ctor/import + UiSimulatorConfiguration @Bean + LiveMapSimulatorRunFacadeTest ctor arg 동기 편집. 빌드/테스트 PASS |
| D3 | F-D/F-E 주석 편집 | UiSimulatorConfiguration:24 + useSimulatorRunScenarios.ts:4 정리. **추가로 A2(simulator javadoc 3곳)·A3(run.api 주석) 동일 처리** |
| D4 | local.properties 제거 | header-value override + simulator.enabled 제거, base-url 보존. git-ignored 라 로컬만 — 팀 공유 안 됨 |
| D5 | UiSimulatorProperties javadoc | enabled/cutover/`{@link #enabled}` 제거, 클래스 역할 1줄 축약 |

---

## 3. 검증 — AC 12개 결과

| AC | 명령/grep | 결과 |
|---|---|---|
| AC1 | `./gradlew :ui:adapter:tc-ui-web-adapter:test -PuseLocalMaven` | **PASS** `BUILD SUCCESSFUL in 34s` (context-load test 포함) |
| AC2 | ui-backend `./gradlew build -PuseLocalMaven` | **PASS** `BUILD SUCCESSFUL in 55s` |
| AC3 | `tsc -b && vite build` (npm run build — A4 참조) | **PASS** `✓ built in 19.69s` (타입체크 통과, 청크 경고는 기존 무관) |
| AC4 | `node --import ./tests/alias-hooks.mjs --test --experimental-strip-types tests/live-map-demo.test.ts` | **PASS** `# pass 7 # fail 0` (보존 트리/타겟 7케이스) |
| AC5 | ui-backend src/main 심볼 grep | **PASS** 0건 (A2 주석 정리 포함) |
| AC6 | 레거시 매핑 grep `"/demo-jobs"\|"/demo-scenarios"\|@RequestMapping("/api/demo-runs")\|/api/internal/demo` (좁힘 — A1 사용자 결정) | **PASS** 0건. literal `/api/demo-runs` 5+6곳은 보존 orchestrator 아웃바운드 코드/javadoc (회귀 0 대상) |
| AC7 | ui-front src 심볼 grep | **PASS** 0건 (A3 주석 정리 포함) |
| AC8 | ui-backend src `tc.ui.simulator.enabled\|setEnabled\|isEnabled` grep | **PASS** 0건 |
| AC9 | exec config `tc.ui.demo-tester` grep | **PASS** 0건 |
| AC10 | exec config `internal-api\|TC_UI_BACKEND_INTERNAL_API_TOKEN` grep | **PASS** 0건 |
| AC11 | validate-module-imports | **PASS** — 스킬 절차 수동 수행(Glob ModuleConfiguration + Grep 36 컴포넌트 비교): 신규 컴포넌트 0 추가, 삭제 빈의 @Import 항목 동반 제거 → MISSING/STALE 0. AC1 의 `UiSimulatorConfigurationContextTest`(Spring context load)가 빈 그래프 정합을 런타임 확증 |
| AC12 | 신규 경로 보존 grep | **PASS** — `UiSimulatorConfiguration`/`HttpSimulatorOrchestratorClient` 존재, LiveMapController `/demo-runs` 3 매핑 보존 |

> **AC6 좁힘 근거**: SPEC Out of Scope("simulator 내부 보존") + Constraint("신규 경로 회귀 0"). literal grep 은 보존 코드를 과매칭하므로 의도("레거시 Spring 매핑 제거")로 좁힘. 사용자 승인 받음(2026-06-24). Validate 단계는 좁힌 grep 기준으로 AC6 [x] 판정.

---

## 4. 위험/롤백 (Plan §4 재확인)

- **[최상] 폴백 소멸** — 토글 제거로 ui-backend 가 도는 모든 환경에서 orchestrator(:18090) 상시 도달 필요. tracked `tc-ui-backend.properties` 에 `tc.ui.simulator.*` 없음 → 운영은 base-url 기본값 `http://127.0.0.1:18090` 사용. **배포 전 운영/k8s 에 orchestrator 가 떠 있고 base-url 이 맞는지 확인 필수**(미확인 머지 시 데모 Run 전면 실패).
- **롤백**: 레포별 3 커밋 분리. always-on 은 런타임 플래그 복구 불가 → ui-backend 커밋 `git revert` 가 유일 수단. exec local.properties 는 git-ignored 라 수동 복원.

---

## 5. 커밋 계획 (사용자 승인 후)

3 커밋 분리(레포별 독립 롤백):
1. **nori-tc-ui-backend**: demo-tester 경로 제거 + always-on cutover (코드/테스트)
2. **nori-tc-ui-front**: 레거시 demo job api/훅/타입 제거 (코드/테스트)
3. **nori-tc-exec**: tc-ui-backend.properties 에서 demo-tester/internal-api 설정 제거 (config)
   - local.properties 는 git-ignored 라 커밋 대상 아님(로컬 적용 완료)

> 커밋/푸시는 사용자 승인 후 진행.

# SPEC: demo-tester-path-removal (simulator cutover)

> 구 `nori-tc-demo-tester`(및 구 `nori-tc-simulator-mes/socket/plc`) 로 흐르던 경로를
> ui-front / ui-backend 에서 제거하고, 통합본 `nori-tc-simulator` orchestrator(:18090)
> 경로만 남긴다. `UiSimulatorProperties` 주석이 명시한 **M7 cutover** 에 해당.

## Why

시뮬레이터 4개 레포(`nori-tc-demo-tester`, `nori-tc-simulator-mes/socket/plc`)는
`nori-tc-simulator` 한 곳으로 통합 완료됐다(검증: [settings.gradle.kts](../../../nori-tc-simulator/settings.gradle.kts)
"통합 시뮬레이션 테스트 도구" — orchestrator/worker-equipment/worker-upstream 5모듈이 구 4레포를 흡수).

그럼에도 ui-front / ui-backend 에는 구 demo-tester 로 흐르던 경로가 **신규 simulator 경로와
병행**하며 잔존한다:

- ui-backend `UiSimulatorProperties.java:9-10` 가 직접 "신규 orchestrator 와 **병행**하는 전이용
  토글. `enabled=false` 면 503 → 프론트가 demo-tester 로 폴백. **9칸 e2e 전부 PASS 후 M7
  cutover 에서 이 토글 + demo-tester 경로를 제거하고 orchestrator 상시(always-on)로 고정한다**"
  고 cutover 계획을 명시.
- 런타임 설정은 이미 `tc.ui.simulator.enabled=true` ([tc-ui-backend.local.properties:11](../../../nori-tc-exec/apps/tc-ui-backend-app/config/tc-ui-backend.local.properties))
  로 신규 경로가 활성. 현재 활성 모달 `LiveMapDemoModal.tsx` 는 신규 run 훅만 사용.

> **실측 정정(검증 결과 반영).** 프론트가 쓰는 **활성** 레거시 job 경로는 `DemoController`(`/api/demo-runs`)가
> 아니라 `LiveMapController` 의 demo-tester 메서드다 — [live-map-demo.api.ts](../../../nori-tc-ui-front/src/features/live-map/api/live-map-demo.api.ts)
> 가 `/live-map/demo-scenarios`·`/live-map/demo-jobs` 를 호출하고, 그 핸들러가 [LiveMapController.java:65-106](../../ui/adapter/tc-ui-web-adapter/src/main/java/com/nori/tc/ui/adapters/web/controller/LiveMapController.java)
> 에서 `demoTesterClient` 를 호출한다. `DemoController`(`/api/demo-runs`)는 프론트 호출자 0(데드)이나 demo-tester
> 결합이라 함께 제거한다. 따라서 demo/ 패키지를 제거하려면 `LiveMapController` 의 레거시 메서드 + `DemoTesterClient`
> 의존도 **반드시 함께 제거**해야 한다(아니면 컴파일/런타임 실패).

문제: 구 경로(코드/설정/테스트)가 죽은 채로 남아 (1) 폐기 레포로의 흐름을 코드에 박제하고,
(2) 신규 개발자에게 두 경로의 혼동을 유발하며, (3) demo-tester 베이스 URL/내부토큰 설정이
stale 한 채 보안 표면에 잔존한다. 통합이 끝났으므로 cutover 를 확정해 단일 경로로 고정한다.

## Requirements

### ui-front (구 demo-tester job 경로 제거)
- [ ] `src/features/live-map/api/live-map-demo.api.ts`(`liveMapDemoApi`) 제거
- [ ] 레거시 훅 4종 제거: `useLiveMapDemoJobs`, `useCancelLiveMapDemoJob`, `useLiveMapDemoScenarios`, `useCreateLiveMapDemoJobs`
- [ ] 위 api/훅만 참조하던 i18n 키 / 타입 / mock 중 **신규 run 경로가 쓰지 않는 것**만 선별 제거. 구체:
  - 타입 `LiveMapDemoRunRow`([live-map.types.ts:72](../../../nori-tc-ui-front/src/features/live-map/types/live-map.types.ts)), `LiveMapDemoScenarioOption`([live-map.types.ts:54](../../../nori-tc-ui-front/src/features/live-map/types/live-map.types.ts)) — job-row 빌더 전용. mock `LIVE_MAP_DEMO_SCENARIO_OPTIONS`([live-map.mock.ts:15](../../../nori-tc-ui-front/src/features/live-map/mocks/live-map.mock.ts)) 동반 제거
  - **주의: `error.livemap.demo*` i18n 키는 신규 모달과 공유**(예 `error.livemap.demoStart` — [LiveMapDemoModal.tsx:327](../../../nori-tc-ui-front/src/features/live-map/components/LiveMapDemoModal.tsx)). 공유 키 제거 금지. `LiveMapDemoTargetEquipment`/`LiveMapDemoTreeNode`/`LiveMapDemoTestType` 타입도 신규 모달 사용 → 보존
- [ ] `src/features/live-map/lib/live-map-demo.ts` 의 **job-row 빌더(`buildLiveMapDemoRunRows`, [:172](../../../nori-tc-ui-front/src/features/live-map/lib/live-map-demo.ts))만** 제거 + 그로 인해 미사용되는 `LiveMapDemoRunRow`/`LiveMapDemoScenarioOption` import(`:2-3`) 정리. 트리/타겟 빌더(`buildLiveMapDemoTree`/`collect*`/`filter*`/`merge*`, `:27-170`)는 신규 모달이 import 하므로 **보존**
- [ ] 위 제거로 깨지는 테스트([tests/live-map-demo.test.ts](../../../nori-tc-ui-front/tests/live-map-demo.test.ts), ui-front 루트 `tests/`)에서 job-row 케이스(`:103-117` "demo run row")만 제거 + import 라인(`:4` buildLiveMapDemoRunRows, `:13` LIVE_MAP_DEMO_SCENARIO_OPTIONS) 정리. 트리/타겟 케이스(`:17-101`, 7개)는 보존

### ui-backend (구 demo-tester 경로 제거)
- [ ] `controller/DemoController.java`(`/api/demo-runs`) 제거 (프론트 호출자 0 = 데드, 그러나 demo-tester 결합이라 함께 제거)
- [ ] **`controller/LiveMapController.java` 외과적 편집(클래스 삭제 아님, 수정)** — 레거시 demo-tester 메서드 5종 제거: `getDemoScenarios`(`GET /demo-scenarios`), `createDemoJobs`(`POST /demo-jobs`), `getDemoJobs`(`GET /demo-jobs`), `getDemoJob`(`GET /demo-jobs/{jobId}`), `cancelDemoJob`(`POST /demo-jobs/{jobId}/cancel`) ([:65-106](../../ui/adapter/tc-ui-web-adapter/src/main/java/com/nori/tc/ui/adapters/web/controller/LiveMapController.java)). 생성자 `DemoTesterClient demoTesterClient` 파라미터 + 필드(`:45,50,54`) + demo/ 패키지·`LiveMapDemoJobCreateRequest` import(`:4-10`) 제거. **신규 simulator Run 경로(`/demo-runs*`, `:108-189`)는 보존.** (이 항목이 빠지면 demo/ 패키지 제거 시 LiveMapController 컴파일 실패 — C1)
- [ ] `demo/` 패키지 전체 제거: `DemoTesterClient`, `HttpDemoTesterClient`, 요청 DTO(`DemoTesterCreateRunRequest`, `DemoTesterCreateJobsRequest`, `DemoTesterTargetStartRequest`) + 응답 DTO(`DemoRunResponse`, `DemoTargetResponse`, `DemoJobCreateResponse`, `DemoJobDetailResponse`, `DemoJobSummaryResponse`, `DemoScenarioCatalogItemResponse` 등 demo/ 내 전부)
- [ ] `config/UiDemoTesterConfiguration.java`, `config/UiDemoTesterProperties.java` 제거. **`config/UiInternalApiProperties.java` 도 제거** — `UiDemoTesterConfiguration.java:14` 의 `@EnableConfigurationProperties` + `DemoInternalController` 가 유일 사용처라 둘을 지우면 고아(G3)
- [ ] `controller/DemoInternalController.java`(`/api/internal/demo/eqp`) 제거 — **단, 워크스페이스 전체 grep 으로 `nori-tc-demo-tester` 가 유일 호출자(통합 simulator·다른 nori-tc-* 호출 0)임을 확정한 뒤** 제거 (검증 완료: `nori-tc-demo-tester/.../client/HttpUiBackendClient.java:45,61,73` 만 호출, 그 외 0건)
- [ ] 테스트 정리: `DemoControllerTest`, `HttpDemoTesterClientTest` 제거. **`LiveMapControllerSimulatorRunTest` 는 503/`isEnabled()` 단언 케이스([:43,59](../../ui/adapter/tc-ui-web-adapter/src/test/java/com/nori/tc/ui/adapters/web/controller/LiveMapControllerSimulatorRunTest.java))와 `DemoTesterClient` mock 을 토글·레거시 메서드 제거에 맞춰 편집**(삭제 아닌 수정). `LiveMapSimulatorRunFacadeTest`·`HttpSimulatorOrchestratorClientTest` 의 `new UiSimulatorProperties()` 는 `enabled` 미참조면 무수정
- [ ] `UiWebAdapterModuleConfiguration` 의 `@Import` 에서 제거된 빈(`UiDemoTesterConfiguration`, `DemoController`, `DemoInternalController`) 항목([:46,58,59](../../ui/adapter/tc-ui-web-adapter/src/main/java/com/nori/tc/ui/adapters/web/config/UiWebAdapterModuleConfiguration.java)) + import 문(`:12,13`) 제거. `UiSimulatorConfiguration`(`:47`), `LiveMapController`(`:68`) 은 보존
- [ ] `UiSecurityConfig` 의 demo-tester/internal-demo 라인 제거 — CSRF `.ignoringRequestMatchers("/api/internal/demo/**")`([:100-101](../../ui/adapter/tc-ui-web-adapter/src/main/java/com/nori/tc/ui/adapters/web/security/UiSecurityConfig.java)), `permitAll` GET/POST `/api/internal/demo/**`(`:129,131`). (`/api/internal/demo/**` 가 유일한 `/api/internal/**` 매처 + DemoInternalController 전용이라 동반 제거)
- [ ] `UiSimulatorProperties.enabled` 토글 제거 → orchestrator 경로 always-on 고정. **제거 대상 분기 전부 열거**: `LiveMapController` 7곳(`:114,126,136,147,157,171,184`)의 `if (!simulatorRunFacade.isEnabled())` + `simulatorDisabled()` 헬퍼(`:203-206`), `LiveMapSimulatorRunFacade.isEnabled()`([:63-64](../../ui/adapter/tc-ui-web-adapter/src/main/java/com/nori/tc/ui/adapters/web/simulator/LiveMapSimulatorRunFacade.java)), `UiSimulatorProperties` 의 `enabled`/`isEnabled()`/`setEnabled()`([:15,21,25](../../ui/adapter/tc-ui-web-adapter/src/main/java/com/nori/tc/ui/adapters/web/config/UiSimulatorProperties.java)). 분기 제거 후 facade 메서드는 무조건 실행. `baseUrl`/`connectTimeoutMs`/`readTimeoutMs`/`reaperIntervalMs` 는 신규 경로 공유라 **보존**

### nori-tc-exec (config 동기화)
- [ ] `apps/tc-ui-backend-app/config/tc-ui-backend.properties`:
  - `tc.ui.demo-tester.base-url` 라인(`:159-160`) 제거
  - **demo-tester 내부토큰 라인 제거 — 실제 키는 `tc.ui.backend.internal-api.*`** (`:155-157`: 주석 + `header-name` + `header-value=${TC_UI_BACKEND_INTERNAL_API_TOKEN:}`). prefix 가 `tc.ui.demo-tester` 가 아님에 주의 (DemoInternalController·UiInternalApiProperties 제거에 맞춤)
- [ ] `apps/tc-ui-backend-app/config/tc-ui-backend.local.properties` (git-ignored):
  - `tc.ui.simulator.enabled` 라인(`:11`) 제거(always-on 고정에 맞춤). `tc.ui.simulator.base-url`(`:12`)은 신규 경로용 → **보존**
  - **`tc.ui.backend.internal-api.header-value` 오버라이드(`:3`) 제거** (DemoInternalController 제거로 고아)

## Constraints

- **헥사고날 @Import 컨벤션 준수** — 빈 제거 시 해당 `*ModuleConfiguration` 의 `import` + `@Import` 두 줄을 함께 제거(누락 시 컴파일 성공·런타임 실패). 신규 simulator 경로 빈(`UiSimulatorConfiguration`, `simulator/*`)은 **무손상 보존**.
- **`LiveMapController` 는 클래스 보존하되 외과적 편집** — 레거시 demo-tester 메서드(`/demo-jobs`·`/demo-scenarios`) + `DemoTesterClient` 의존은 제거하고, 신규 `/demo-runs*` (simulator) 메서드만 남긴다. "무손상 보존"이 아니라 "**부분 보존**".
- **단방향 의존** — common / 다른 nori-tc-* 레포 코드에 손대지 않는다. exec 는 config 파일만.
- **신규 경로 회귀 0** — `simulator/` 패키지, `LiveMapController` 의 `/live-map/demo-runs*` 엔드포인트(`:108-189`), `live-map-demo-run.api.ts`, `LiveMapDemoModal.tsx` 동작은 변경 전후 동일.
- **공유 코드 보존** — `live-map-demo.ts` 의 트리/타겟 빌더는 신규 모달 의존이므로 통째 삭제 금지. 선별 제거만.
- **삭제 전 dead path 4곳 검증**(글로벌 룰): UI 진입점 / 라우팅 / store·호출처 / 다른 레포·테스트. 특히 DemoInternalController 는 워크스페이스 전체 grep 으로 호출자 0 확정 후 제거.

## Acceptance Criteria

> 각 항목 단일 명령 / 단일 grep / 단일 빌드. 자연어 OR("또는"/"혹은") 금지.
> grep 의 `\|` 는 합집합-0 판정(모든 대안이 0이어야 PASS) = 단일 명령 AND 이므로 OR 표현 아님.

- [x] `./gradlew :ui:adapter:tc-ui-web-adapter:test` PASS (출력 마지막 5~10줄 인용) — Validate 재실행 `--rerun-tasks` 강제 실행: `> Task :ui:adapter:tc-ui-web-adapter:test` / `BUILD SUCCESSFUL in 33s` / `13 actionable tasks: 13 executed`
- [x] `./gradlew build` (ui-backend 전체) PASS (출력 마지막 5~10줄 인용) — `> Task :ui:starter:tc-ui-backend-starter:build` / `BUILD SUCCESSFUL in 14s` / `34 actionable tasks: 3 executed, 31 up-to-date`
- [x] ui-front 루트에서 `pnpm build` PASS (출력 마지막 5~10줄 인용) — `package.json` `"build": "tsc -b && vite build"` (실제 toolchain npm — A4. `npm run build` 동일 스크립트 실행): `✓ built in 21.40s`, 청크 경고는 기존 무관
- [x] ui-front 루트에서 `node --import ./tests/alias-hooks.mjs --test --experimental-strip-types tests/live-map-demo.test.ts` PASS (보존된 트리/타겟 케이스 7개 전부 통과) — `# tests 7 # pass 7 # fail 0`
- [x] ui-backend `src/main` 에서 `grep -rn "DemoTesterClient\|HttpDemoTesterClient\|UiDemoTesterProperties\|UiDemoTesterConfiguration\|UiInternalApiProperties\|DemoController\|DemoInternalController"` 결과 0건 (exit=1, 매치 0)
- [x] ui-backend `src/main` 엔드포인트 매핑 grep 0건 — **AC6 좁힘(A1, 사용자 결정 2026-06-24)**: 작성된 literal `/api/demo-runs` 는 보존 orchestrator 클라이언트의 아웃바운드 호출(HttpSimulatorOrchestratorClient :42,47,52,57,62)+javadoc(SimulatorOrchestratorClient/SimulatorCreateRunRequest)을 과매칭해 0건 불가. "레거시 Spring 매핑 제거"로 좁힌 `grep -rn '"/demo-jobs"\|"/demo-scenarios"\|@RequestMapping("/api/demo-runs")\|/api/internal/demo'` 결과 0건(exit=1). 잔존 literal 11곳 전부 보존 orchestrator 코드/javadoc 확인 → 회귀 아님
- [x] ui-front `src` 에서 `grep -rn "live-map-demo\.api\|liveMapDemoApi\|useLiveMapDemoJobs\|useCancelLiveMapDemoJob\|useLiveMapDemoScenarios\|useCreateLiveMapDemoJobs\|buildLiveMapDemoRunRows"` 결과 0건 (exit=1, A3 주석 정리 포함)
- [x] ui-backend `src` 에서 `grep -rn "tc\.ui\.simulator\.enabled\|setEnabled\|isEnabled"` 결과 0건 (exit=1, F-D 주석 포함)
- [x] nori-tc-exec `apps/tc-ui-backend-app/config` 에서 `grep -rn "tc\.ui\.demo-tester"` 결과 0건 (exit=1)
- [x] nori-tc-exec `apps/tc-ui-backend-app/config` 에서 `grep -rn "tc\.ui\.backend\.internal-api\|TC_UI_BACKEND_INTERNAL_API_TOKEN"` 결과 0건 (exit=1)
- [x] `validate-module-imports` 위반 0건 (@Import 그래프 정합) — 수동 절차: 컴포넌트 38개 중 실제 빈 전부 `UiWebAdapterModuleConfiguration` @Import 에 존재, `UiDualRequestProperties`/`UiSimulatorProperties`(@ConfigurationProperties)는 @EnableConfigurationProperties 등록, 삭제 빈 STALE 항목 0. AC1 의 Spring context-load 테스트가 런타임 확증
- [x] 신규 경로 회귀 검증: `grep -rn "UiSimulatorConfiguration\|HttpSimulatorOrchestratorClient"`(ui-backend src/main 3파일 존재) + `grep -c "/demo-runs" LiveMapController.java`(9건 존재). simulator/ 패키지 12파일 + UiDualRequestProperties + live-map-demo-run.api + LiveMapDemoModal + i18n demo* 키 무손상

## Out of Scope

- 폐기 레포(`nori-tc-demo-tester`, `nori-tc-simulator-mes/socket/plc`) 자체의 삭제/아카이브 — 별도 레포 lifecycle 작업
- `nori-tc-simulator`(통합본) 내부 코드 변경 — 이번 작업과 무관, 동작 유지
- gateway / business 등 다른 다운스트림 레포 — demo-tester 흐름과 무관
- 신규 simulator 경로의 기능 추가/개선 — cutover(제거)만 다룸
- **`DemoJobCleanupPort` / `DemoJobJdbcCleanupPort`** (+ `EqpDirectScenarioService`·`JpaEqpCrudPort` 의 호출) — demo-tester 의 `tc_demo_job` 테이블 FK 무결성 방어용 DB 정리 경로(요청 경로 아님). 제거하면 demo-tester 무관 클래스를 건드려야 하고 `tc_demo_job` 테이블 lifecycle(폐기 레포 작업, 위 항목)과 묶이므로 이번 cutover 대상 아님. `DemoJobCleanupPort.java:8` javadoc 의 "nori-tc-demo-tester" 문자열은 잔존 → AC grep 패턴이 이를 매칭하지 않도록 심볼 한정(위 AC 참조)

## SDD 진행 상태

- [x] **Specify** — 이 SPEC.md 작성 완료 (산출물: `.claude/specs/demo-tester-path-removal.md`)
- [x] **Plan** — Plan Mode 로 구현 계획 수립 완료 (산출물: `.claude/specs/demo-tester-path-removal-plan.md`, 결정 D1~D5 + F-A~F-E)
- [x] **Implement** — Acceptance Criteria 만족하는 구현 완료 (산출물: `.claude/specs/demo-tester-path-removal-dev-report.md`, 추가 발견 A1~A4)
- [x] **Validate** — SPEC + Plan + Dev report 셋 입력으로 독립 재실행 검증 완료(2026-06-24). AC 12개 전부 직접 grep/빌드/테스트 재실행 PASS, D1~D5·A1 위반 0, 보존 항목 무손상. AC6 는 좁힌 기준(A1)으로 [x]

## 다음 단계 진입 프롬프트

> 새 세션 진입 시 아래 블록을 그대로 복붙. `<feature>` = `demo-tester-path-removal`.

**Plan 진입 (새 세션 + Plan Mode)**:
```
@.claude/specs/demo-tester-path-removal.md 보고 구현 계획 수립.
영향 파일 / 단계 / 위험 / 롤백 방법 포함.
ui-front / ui-backend / nori-tc-exec 3곳 모두 매핑.
DemoInternalController 유일 호출자 확정 grep 계획과 live-map-demo.ts 선별 제거 경계 명시.
C1(LiveMapController 레거시 메서드 + DemoTesterClient 의존 제거) 단계와 enabled 토글 분기 7곳 제거를 계획에 포함.
실행 / 코드 수정 금지.
산출물: .claude/specs/demo-tester-path-removal-plan.md 에 저장.
```

**Implement 진입 (새 세션)**:
```
@.claude/specs/demo-tester-path-removal.md @.claude/specs/demo-tester-path-removal-plan.md 둘 다 읽고 구현.
SPEC 의 Acceptance Criteria 모두 만족하도록 각 AC 당 검증(grep/test) 먼저.
Plan 의 결정 (DemoInternalController 호출자 0 확정 / live-map-demo.ts 선별 / enabled always-on) 엄수.
@Import 두 줄 동반 제거. LiveMapController 는 레거시 메서드 제거 후 /demo-runs* 만 부분 보존, simulator/* 패키지는 무손상 보존.
산출물: .claude/specs/demo-tester-path-removal-dev-report.md 에 변경 파일 / 테스트 결과 / 결정 기록.
```

**Validate 진입 (새 세션)**:
```
@.claude/specs/demo-tester-path-removal.md @.claude/specs/demo-tester-path-removal-plan.md @.claude/specs/demo-tester-path-removal-dev-report.md
셋 다 읽고, 구현이 SPEC 의 모든 Acceptance Criteria 와 Plan 의 결정을 만족하는지 검증.
누락 / 위반 발견 시 보고. 통과 항목은 SPEC 의 AC 체크박스 + SDD 진행 상태를 [x] 로 업데이트.
```

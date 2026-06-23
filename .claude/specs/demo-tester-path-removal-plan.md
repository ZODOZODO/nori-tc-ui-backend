# PLAN: demo-tester-path-removal (M7 simulator cutover)

> 입력 SPEC: `.claude/specs/demo-tester-path-removal.md`
> 산출물(본 파일): `.claude/specs/demo-tester-path-removal-plan.md`
> 작성 단계: Plan (코드 수정·빌드·테스트 실행 없음 — grep/Read 로 file:line 만 확정)
> 대상 레포 3개: nori-tc-ui-front / nori-tc-ui-backend / nori-tc-exec(config only)

이 계획은 SPEC 의 결함 검증을 신뢰하되, Plan 세션에서 모든 file:line 을 도구로 재확인했다.
재확인 과정에서 **SPEC 이 누락한 3개 결함**을 발견했다(§7 결정사항 D1/D2/D3). 모두 기본값 제시 +
Validate 게이트 바인딩.

---

## 0. Plan 세션에서 새로 발견한 사실 (SPEC 미반영 — silent death 방지)

| # | 발견 | 근거(file:line) | 영향 |
|---|---|---|---|
| F-A | `DemoController` 전용 요청 DTO `DemoRunCreateRequest`·`DemoTargetStartRequest` 는 DemoController(+DemoControllerTest)에서만 사용 → DemoController 제거 시 **고아** | DemoController.java:8,9,46,70 / DemoControllerTest.java:8,9,57,81 (그 외 src 참조 0) | SPEC 제거 목록 누락. AC grep 엔 안 잡히나 rule 4 위반(죽은 코드 잔존) |
| F-B | `LiveMapDemoJobCreateRequest` DTO 는 `LiveMapController.createDemoJobs` 에서만 사용 → 레거시 메서드 제거 시 **고아** | LiveMapController.java:10,74 / LiveMapDemoJobCreateRequest.java:9 (그 외 src 참조 0) | 동상 |
| F-C | `LiveMapSimulatorRunFacade` 의 `properties`(UiSimulatorProperties) 필드는 **오직 `isEnabled()`(:63-64)에서만** 참조 → `isEnabled()` 제거 시 필드·생성자 파라미터·import 가 고아. 생성자 시그니처 변경이 `UiSimulatorConfiguration`(:72-89)·`LiveMapSimulatorRunFacadeTest`(:65-72)로 **연쇄** | LiveMapSimulatorRunFacade.java 전체 정독(properties 참조는 :54 저장 + :64 사용뿐) | **SPEC 의 "`LiveMapSimulatorRunFacadeTest` 무수정" 주장은 오류** — 최소 1줄(ctor arg)+import 편집 필요 |
| F-D | AC #8(SPEC:85) grep `tc\.ui\.simulator\.enabled` 는 **주석**도 매칭. `UiSimulatorConfiguration.java:24` 주석에 해당 리터럴 잔존 → 코드만 고치면 AC FAIL | UiSimulatorConfiguration.java:24 `토글({@code tc.ui.simulator.enabled})은 ...` | AC 통과에 주석 편집 필수 |
| F-E | AC #7(SPEC:84) grep `useLiveMapDemoScenarios` 는 주석도 매칭. `useSimulatorRunScenarios.ts:4` 주석에 잔존 → 파일 삭제만으론 AC FAIL | useSimulatorRunScenarios.ts:4 `job 경로(useLiveMapDemoScenarios)와 달리 ...` | AC 통과에 주석 편집 필수 |

> AC 번호: SPEC 의 AC 체크박스는 실제 **12개**(SPEC:78~89). 진입 프롬프트의 "11개"는 부정확 — 본 계획은 12개 전부를 §3 에 매핑.

---

## 1. 영향 파일 전체 목록 (레포별 · 삭제/외과적편집/보존)

### 1-A. nori-tc-ui-backend (`ui/adapter/tc-ui-web-adapter`)

#### 삭제 (파일 통째)
| 파일 | 근거 |
|---|---|
| `.../controller/DemoController.java` | `/api/demo-runs` 데드 + demo-tester 결합 (SPEC §ui-backend, DemoController.java:30-31) |
| `.../controller/DemoInternalController.java` | `/api/internal/demo` 유일 호출자=폐기 레포 nori-tc-demo-tester (SPEC:50) |
| `.../config/UiDemoTesterConfiguration.java` | demo-tester 빈 조립 (UiDemoTesterConfiguration.java:13-15) |
| `.../config/UiDemoTesterProperties.java` | `tc.ui.demo-tester.*` 바인딩, HttpDemoTesterClient 전용 |
| `.../config/UiInternalApiProperties.java` | 유일 사용처=DemoInternalController + UiDemoTesterConfiguration:14 (그 외 참조 0 — grep 확인) |
| `.../demo/` 패키지 13파일 전부 | `DemoTesterClient, HttpDemoTesterClient, DemoTesterCreateRunRequest, DemoTesterCreateJobsRequest, DemoTesterTargetStartRequest, DemoRunResponse, DemoTargetResponse, DemoJobCreateResponse, DemoJobDetailResponse, DemoJobSummaryResponse, DemoJobEventResponse, DemoJobRejectedEquipmentResponse, DemoScenarioCatalogItemResponse` (glob 확인) |
| `.../dto/request/DemoRunCreateRequest.java` | **F-A** 고아 DTO |
| `.../dto/request/DemoTargetStartRequest.java` | **F-A** 고아 DTO |
| `.../dto/request/LiveMapDemoJobCreateRequest.java` | **F-B** 고아 DTO |
| `src/test/.../controller/DemoControllerTest.java` | DemoController 제거에 동반 (DemoControllerTest.java:39 demoTesterClient mock) |
| `src/test/.../demo/HttpDemoTesterClientTest.java` | HttpDemoTesterClient 제거에 동반 (HttpDemoTesterClientTest.java:24) |

#### 외과적 편집 (파일 보존, 부분 수정)
| 파일 | 편집 내용 | 근거(file:line) |
|---|---|---|
| `.../controller/LiveMapController.java` | (1) 레거시 메서드 5종 제거: getDemoScenarios/createDemoJobs/getDemoJobs/getDemoJob/cancelDemoJob (:65-106) (2) ctor 의 `DemoTesterClient demoTesterClient` 파라미터·필드·null체크 제거 (:45,50,54) (3) demo/ DTO + LiveMapDemoJobCreateRequest import 제거 (:4-10) (4) `/demo-runs*` 의 `if(!simulatorRunFacade.isEnabled())` 7곳 제거 (:114,126,136,147,157,171,184) + `simulatorDisabled()` 헬퍼 제거 (:203-206) (5) 그로 인해 미사용된 `import org.springframework.http.HttpStatus`(:20) 제거 — simulatorDisabled 전용 | LiveMapController.java |
| `.../config/UiSimulatorConfiguration.java` | (1) `liveMapSimulatorRunFacade` @Bean 의 `UiSimulatorProperties properties` 파라미터(:73)·생성자 전달 arg(:82) 제거 — **F-C 연쇄** (2) 클래스 주석 :23-25 의 토글 서술 + `tc.ui.simulator.enabled` 리터럴(:24) 제거 — **F-D** | UiSimulatorConfiguration.java. ⚠ `simulatorOrchestratorClient` @Bean(:31-40)이 properties 계속 사용 → `@EnableConfigurationProperties(UiSimulatorProperties.class)`(:28)·import 보존 |
| `.../simulator/LiveMapSimulatorRunFacade.java` | (1) `isEnabled()` 제거 (:63-65) (2) **F-C**: `properties` 필드(:37)·ctor 파라미터(:46)·`Objects.requireNonNull` 저장(:54)·import(:4) 제거 | properties 는 isEnabled 외 미사용(정독 확인) |
| `.../config/UiSimulatorProperties.java` | `enabled` 필드(:15)·`isEnabled()`(:21-23)·`setEnabled()`(:25-27) 제거. 클래스 javadoc(:5-11)에서 enabled 토글/`{@link #enabled}` 깨진 링크/cutover 서술 정리. `baseUrl/connectTimeoutMs/readTimeoutMs/reaperIntervalMs` 접근자 **보존** | UiSimulatorProperties.java |
| `.../config/UiWebAdapterModuleConfiguration.java` | import 제거: `DemoController`(:12), `DemoInternalController`(:13). @Import 배열 항목 제거: `UiDemoTesterConfiguration.class`(:46), `DemoController.class`(:58), `DemoInternalController.class`(:59). `UiSimulatorConfiguration.class`(:47)·`LiveMapController.class`(:68) **보존**. (UiDemoTesterConfiguration 는 동일 package 라 import 문 없음 — 배열 항목만) | UiWebAdapterModuleConfiguration.java |
| `.../security/UiSecurityConfig.java` | CSRF `.ignoringRequestMatchers("/api/internal/demo/**")` + 주석(:100-101) 제거. authorize 의 GET/POST permitAll `/api/internal/demo/**` + 주석(:129-131) 제거 | UiSecurityConfig.java |
| `src/test/.../controller/LiveMapControllerSimulatorRunTest.java` | (1) `@Mock DemoTesterClient`(:35-36)·import(:3) 제거 (2) ctor 호출에서 demoTesterClient arg 제거(:46,60) (3) `isEnabled()` stub(:43,59) 제거 (4) `verifyNoInteractions(demoTesterClient)`(:54,67) 제거 (5) 503 테스트 `disabledToggleReturns503AndSkipsOrchestrator`(:57-68) **삭제**(always-on 으로 503 경로 소멸). 라우팅 테스트(:41-55)는 stub/arg 정리 후 보존 | LiveMapControllerSimulatorRunTest.java |
| `src/test/.../simulator/LiveMapSimulatorRunFacadeTest.java` | **F-C**: ctor 호출의 `new UiSimulatorProperties(),`(:66) 제거 + import(:4) 제거. 그 외 케이스 무수정 | SPEC 의 "무수정" 주장 정정 |

#### 보존 (무손상 — 회귀 0 대상)
- `.../simulator/` 신규 경로 전부: `HttpSimulatorOrchestratorClient`, `SimulatorOrchestratorClient`, `SimulatorModeMapper`, `SimulatorTargetAssembler`, `DemoRunConnectionRegistry`, `DemoRunConnectionReaper`, `SimulatorRunResponse`, `SimulatorScenarioCatalogItem`, `SimulatorCreateRunRequest` 등
- `LiveMapController` 의 `/demo-runs*` 6 엔드포인트(:108-189) — 분기만 제거, 매핑/동작 보존
- `UiDualRequestProperties` — EqpController.java:62 `@EnableConfigurationProperties` 로 등록, DemoInternalController 외 다수 사용처(EqpController/Facade/Reaper) → **보존**
- `DemoJobCleanupPort`/`DemoJobJdbcCleanupPort` — SPEC Out of Scope
- 테스트: `LiveMapSimulatorRunFacadeTest`(F-C 1줄 외), `HttpSimulatorOrchestratorClientTest`(:43 `new UiSimulatorProperties()` 는 orchestrator client 용 — enabled 미참조, 무수정), `DemoRunConnectionReaperTest`, `UiSimulatorConfigurationContextTest`, `SimulatorModeMapperTest`, `SimulatorTargetAssemblerTest`

### 1-B. nori-tc-ui-front (`src/features/live-map`)

#### 삭제 (파일 통째)
| 파일 | 근거(importer 0 확인) |
|---|---|
| `api/live-map-demo.api.ts` | `liveMapDemoApi` + 레거시 타입. 활성 모달은 `live-map-demo-run.api` 사용 |
| `hooks/useLiveMapDemoJobs.ts` | 컴포넌트 importer 0 (grep: 정의 외 참조 없음) |
| `hooks/useCancelLiveMapDemoJob.ts` | 동상 |
| `hooks/useLiveMapDemoScenarios.ts` | 동상 |
| `hooks/useCreateLiveMapDemoJobs.ts` | 동상 |

> dead path 4곳 검증(글로벌 룰): (1) UI 진입점=활성 모달 `LiveMapDemoModal.tsx` 는 run 훅만 import(:17-23,33) (2) 라우팅=없음(모달 내부) (3) 호출처=4 훅의 importer 0(grep) (4) 외부=`live-map-demo.api`/4훅 의 import 는 자기 4파일뿐. → 안전.

#### 외과적 편집
| 파일 | 편집 내용 | 근거(file:line) |
|---|---|---|
| `lib/live-map-demo.ts` | `buildLiveMapDemoRunRows`(:172-199) 제거 + 전용 상수 `DEMO_ROW_STAGGER_TICKS`(:9) 제거 + import 에서 `LiveMapDemoRunRow`(:2)·`LiveMapDemoScenarioOption`(:3) 제거. 트리/타겟 빌더(:11-170) **보존**(LiveMapDemoModal.tsx:25,147 import) | live-map-demo.ts |
| `types/live-map.types.ts` | `LiveMapDemoScenarioOption`(:54-60)·`LiveMapDemoRunRow`(:72-77) 제거. `LiveMapDemoTreeNode`(:43-52)·`LiveMapDemoTargetEquipment`(:62-70)·`LiveMapDemoTestType`·`LiveMapEquipmentNode` **보존** | grep: 두 타입은 mock+lib 의 제거 대상에서만 사용 |
| `mocks/live-map.mock.ts` | `LIVE_MAP_DEMO_SCENARIO_OPTIONS`(:15~) 상수 + import 의 `LiveMapDemoScenarioOption`(:2) 제거. `LIVE_MAP_EQUIPMENT_NODES` **보존** | grep: 해당 상수 사용처=제거될 test + lib func 뿐 |
| `hooks/useSimulatorRunScenarios.ts` | 주석(:4)의 `useLiveMapDemoScenarios` 참조 어구 정리 — **F-E**(AC #7/SPEC:84 통과 필수) | useSimulatorRunScenarios.ts:4 |
| `tests/live-map-demo.test.ts` (ui-front 루트) | run-row 케이스(:103-117) 제거 + import 의 `buildLiveMapDemoRunRows`(:4)·`LIVE_MAP_DEMO_SCENARIO_OPTIONS`(:13) 제거. 트리/타겟 7케이스(:17-101) + `LIVE_MAP_EQUIPMENT_NODES`(:14) **보존** | live-map-demo.test.ts |

#### 보존
- `api/live-map-demo-run.api.ts`, `hooks/useCreateSimulatorRun`·`useSimulatorRun`·`useSimulatorRunScenarios`(파일 자체)·`useStopSimulatorRun`·`useStopSimulatorTarget`, `components/LiveMapDemoModal.tsx`
- i18n `error.livemap.demo*` 키 (LiveMapDemoModal.tsx:327 `error.livemap.demoStart` 공유) — **제거 금지**

### 1-C. nori-tc-exec (config only)

| 파일 | 편집 내용 | 근거(file:line) | git |
|---|---|---|---|
| `apps/tc-ui-backend-app/config/tc-ui-backend.properties` | (1) `tc.ui.demo-tester.base-url` + 주석(:159-160) 제거 (2) `tc.ui.backend.internal-api.header-name`·`header-value` + 주석(:155-157) 제거 | 확인됨 | tracked → 커밋 대상 |
| `apps/tc-ui-backend-app/config/tc-ui-backend.local.properties` | (1) `tc.ui.backend.internal-api.header-value` override(:3) 제거 (2) `tc.ui.simulator.enabled` + 주석(:9-11) 제거. `tc.ui.simulator.base-url`(:12) **보존** | 확인됨 | **git-ignored** → 커밋 안 됨, 로컬만. 롤백=수동 |

---

## 2. 구현 순서 (의존 역순 — 중간 컴파일 깨짐 회피)

> 핵심 제약: **소비자(LiveMapController/DemoController) 편집을 demo/ 패키지 삭제보다 먼저**. **@Import 배열 항목 제거를 빈 클래스 삭제와 같은 배치로**(삭제했는데 @Import 에 `.class` 잔존 → "symbol not found"). 빌드는 모든 편집 완료 후 1회.

### ui-backend (한 커밋)
1. **LiveMapController 외과적 편집** (C1) — 레거시 메서드 5종 + DemoTesterClient 의존 + isEnabled 분기 7곳 + simulatorDisabled + HttpStatus import 제거. → demo/ DTO·DemoTesterClient·facade.isEnabled 역참조 해소.
2. **DemoController + F-A DTO 삭제** — DemoController.java, DemoRunCreateRequest.java, DemoTargetStartRequest.java. → DemoTesterClient + demo/ run DTO 역참조 해소.
3. **LiveMapDemoJobCreateRequest 삭제** (F-B). (1 에서 이미 역참조 해소됨)
4. **facade.isEnabled + properties 제거** (F-C) → 즉시 **UiSimulatorConfiguration 의 facade @Bean** properties 파라미터/arg 제거(같은 배치라야 ctor 시그니처 정합).
5. **UiSimulatorProperties.enabled/isEnabled/setEnabled 제거** + javadoc 정리 + **UiSimulatorConfiguration:24 주석** 정리(F-D).
6. **DemoInternalController + UiInternalApiProperties 삭제**. (UiInternalApiProperties 역참조: DemoInternalController + UiDemoTesterConfiguration 뿐 → 둘 다 이 배치에서 사라짐)
7. **UiDemoTesterConfiguration + UiDemoTesterProperties + demo/ 패키지 13파일 삭제**. (1·2 에서 모든 소비자 제거 완료 후)
8. **UiWebAdapterModuleConfiguration**: import(:12,13) + @Import(:46,58,59) 제거 — **6·7 과 같은 배치**.
9. **UiSecurityConfig**: :100-101, :129-131 제거.
10. **테스트**: DemoControllerTest·HttpDemoTesterClientTest 삭제 / LiveMapControllerSimulatorRunTest·LiveMapSimulatorRunFacadeTest 편집.
11. `validate-module-imports` 스킬 실행 → `./gradlew :ui:adapter:tc-ui-web-adapter:test` → `./gradlew build`.

### ui-front (한 커밋)
12. `lib/live-map-demo.ts` 의 buildLiveMapDemoRunRows + 상수 + import 정리 → `types`·`mocks` 의 두 타입/상수 제거 → 4 훅 + api 파일 삭제 → `useSimulatorRunScenarios.ts:4` 주석 정리(F-E).
13. `tests/live-map-demo.test.ts` 편집.
14. `pnpm build` → `node --import ./tests/alias-hooks.mjs --test --experimental-strip-types tests/live-map-demo.test.ts`.

### nori-tc-exec (한 커밋 — config)
15. `tc-ui-backend.properties` 편집(tracked). `tc-ui-backend.local.properties` 편집(ignored, 로컬).

> 레포 간 순서: 독립적. 배포 시 §4 위험 참조.

---

## 3. AC ↔ 변경 추적표 (SPEC AC 12개 전부)

| AC (SPEC line) | 검증 | PASS 시키는 변경 |
|---|---|---|
| AC1 (78) `:ui:adapter:tc-ui-web-adapter:test` PASS | gradle | ui-backend 단계 1~10 전부 |
| AC2 (79) ui-backend `./gradlew build` PASS | gradle | ui-backend 단계 1~10 전부 |
| AC3 (80) ui-front `pnpm build` PASS | tsc+vite | ui-front 단계 12~13 (타입 제거 후 tsc 정합) |
| AC4 (81) `node ... live-map-demo.test.ts` PASS(7케이스) | node test | 단계 12·13 (run-row 케이스+import 정리, 트리/타겟 보존) |
| AC5 (82) ui-backend src/main 심볼 grep 0 | grep | 단계 1·2·6·7·8 (DemoTesterClient/HttpDemoTesterClient/UiDemoTesterProperties/UiDemoTesterConfiguration/UiInternalApiProperties/DemoController/DemoInternalController 전부 제거) |
| AC6 (83) ui-backend src/main 엔드포인트 grep 0 | grep | 단계 1(`/demo-jobs`·`/demo-scenarios`)·2(`/api/demo-runs`)·6(`/api/internal/demo`) |
| AC7 (84) ui-front src 심볼 grep 0 | grep | 단계 12 (api+4훅 삭제, buildLiveMapDemoRunRows 제거, **F-E 주석 정리**) |
| AC8 (85) ui-backend src `isEnabled\|setEnabled\|tc.ui.simulator.enabled` grep 0 | grep | 단계 1(분기)·4(facade)·5(properties+**F-D 주석**)·10(테스트 stub) |
| AC9 (86) exec config `tc.ui.demo-tester` grep 0 | grep | 단계 15 (properties:159-160) |
| AC10 (87) exec config `internal-api\|TC_UI_BACKEND_INTERNAL_API_TOKEN` grep 0 | grep | 단계 15 (properties:155-157 + local:3) |
| AC11 (88) `validate-module-imports` 위반 0 | skill | 단계 8 (@Import 그래프 정합) |
| AC12 (89) 신규 경로 보존 grep 존재 | grep | 보존 항목(UiSimulatorConfiguration/HttpSimulatorOrchestratorClient + LiveMapController `/demo-runs`) 무손상 |

> ⚠ F-D·F-E 를 빠뜨리면 코드는 다 고쳐도 **AC8·AC7 이 주석 매칭으로 FAIL**. 이 둘이 본 계획의 가장 미묘한 지점.

---

## 4. 위험 + 롤백

### 위험
1. **[최상] 폴백 소멸 — orchestrator 의존성 고정.** 토글(503→demo-tester 폴백)이 사라지면 ui-backend 가 도는 **모든 환경에서 nori-tc-simulator orchestrator(:18090)가 상시 도달 가능**해야 한다. tracked `tc-ui-backend.properties` 엔 `tc.ui.simulator.*` 가 없어 운영 기본값은 `enabled=false`(=현재 503)였다 → cutover 후 운영은 무조건 orchestrator 호출. **배포 전 운영/k8s 에 orchestrator 가 떠 있고 base-url 이 맞는지 확인 필수**(이 확인 없이 머지하면 운영 데모 Run 전면 503/실패 위험).
2. **facade ctor 시그니처 변경(F-C)** — properties 파라미터 제거가 @Bean·테스트 2곳과 동기화 안 되면 컴파일/컨텍스트 로드 실패. 단계 4 에서 3곳 동시 편집으로 차단. LiveMapSimulatorRunFacadeTest 가 회귀 감지.
3. **AC 주석 매칭(F-D/F-E)** — §3 경고 참조.
4. **공유 코드 오삭제** — live-map-demo.ts 트리 빌더 / i18n demo* 키 / UiDualRequestProperties 를 잘못 지우면 신규 모달 깨짐. §1 보존 목록 엄수.

### 롤백
- **레포별 커밋 분리**(3 커밋): ui-backend(코드) / ui-front(코드) / nori-tc-exec(config). 각 `git revert <sha>` 로 독립 롤백.
- **always-on 롤백은 git revert 외 우회 없음** — 토글이 제거되므로 런타임 플래그로 demo-tester 폴백 복구 불가. 운영 문제 시 ui-backend 커밋 revert 가 유일 수단. (배포 전 위험1 확인이 사실상 1차 방어선)
- **exec local.properties 는 git-ignored** → revert 대상 아님. 로컬 머신에서 수동 복원(이전 :3,:9-11 라인 재기입).
- **부분 적용 안전성**: ui-front 만 먼저 배포 → 활성 모달은 이미 run 경로만 쓰므로 영향 0. ui-backend 만 먼저 배포 → 레거시 엔드포인트 404 이나 활성 프론트가 호출 안 함. 단 위험1(orchestrator 도달성)은 ui-backend 배포 시점에 성립해야 함.

---

## 5. 헥사고날 @Import 컨벤션 점검

제거 빈마다 `UiWebAdapterModuleConfiguration` 의 (import 문 + @Import 배열 항목) 두 줄 동반 제거:

| 제거 빈 | import 문 | @Import 배열 |
|---|---|---|
| `DemoController` | :12 제거 | :58 제거 |
| `DemoInternalController` | :13 제거 | :59 제거 |
| `UiDemoTesterConfiguration` | (동일 package, import 문 없음) | :46 제거 |

- `UiDemoTesterProperties`/`UiInternalApiProperties` 는 `@EnableConfigurationProperties`(UiDemoTesterConfiguration:14)로 등록됐던 것 → UiDemoTesterConfiguration 삭제로 자동 해소(별도 @Import 없음).
- `demo/` 패키지 빈(DemoTesterClient 등)은 `UiDemoTesterConfiguration` 의 `@Bean` 메서드로만 등록 → 해당 config 삭제로 해소.
- **보존 확인**: `UiSimulatorConfiguration.class`(:47)·`LiveMapController.class`(:68) 잔존. `UiSimulatorProperties` 는 UiSimulatorConfiguration:28 `@EnableConfigurationProperties` 로 등록 유지(enabled 필드만 제거).
- **validate-module-imports 스킬 적용 시점**: ui-backend 편집 완료 직후, `./gradlew :ui:adapter:tc-ui-web-adapter:test` **이전**(AC11). 위반 0 확인 후 빌드.

---

## 6. 테스트 영향

### 삭제 테스트
- `DemoControllerTest` (DemoController 동반)
- `HttpDemoTesterClientTest` (HttpDemoTesterClient 동반, :71/:128 `/api/demo-jobs` mock 서버)

### 편집 테스트
- `LiveMapControllerSimulatorRunTest` — demoTesterClient mock + isEnabled stub 제거, 503 테스트 메서드(:57-68) 삭제, 라우팅 테스트 보존. **무력 mock 회피 확인**: 보존된 라우팅 테스트는 `verify(simulatorRunFacade).startRun(...)` 로 신규 경로 호출을 실제 단언 → 여전히 의미 있는 실패 재현(facade 미호출 시 FAIL).
- `LiveMapSimulatorRunFacadeTest` — ctor `new UiSimulatorProperties()` arg + import 제거(F-C). 나머지 연결-배리어 검증 케이스 무수정 → 신규 경로 회귀 감지 유지.

### 신규 경로 회귀 0 보장
- AC1/AC2(ui-backend 빌드+테스트) + AC3(ui-front 빌드) + AC4(보존 7케이스) + AC12(보존 grep) 동시 PASS 로 보장.
- `HttpSimulatorOrchestratorClientTest`·`DemoRunConnectionReaperTest`·`UiSimulatorConfigurationContextTest` 무수정 통과가 신규 경로 무손상 증거.

---

## 7. 미해결/결정 필요 (기본값 + Validate 게이트 바인딩)

silent death 방지를 위해 모두 **기본값을 정하고** Validate 단계에서 확인하도록 바인딩한다.

| ID | 결정 | 기본값(권장) | 근거 | Validate 확인 |
|---|---|---|---|---|
| D1 | F-A/F-B 고아 DTO(DemoRunCreateRequest, DemoTargetStartRequest, LiveMapDemoJobCreateRequest) 처리 | **삭제** | 제거 mandated 변경이 만든 죽은 코드(글로벌 rule 4 "내 변경이 만든 미사용 제거"). AC grep 엔 안 잡혀 잔존해도 PASS 하나 AI Debt | 세 DTO src 참조 0 확인 후 삭제 반영 |
| D2 | F-C facade `properties` 필드/ctor 파라미터 | **제거 + 호출 2곳(UiSimulatorConfiguration @Bean, LiveMapSimulatorRunFacadeTest) 동기 편집** | isEnabled() 제거 시 유일 사용처 소멸(정독 확인). 남기면 죽은 필드. **SPEC 의 "FacadeTest 무수정" 정정** | facade 컴파일 + FacadeTest PASS, src 에 isEnabled 잔존 0(AC8) |
| D3 | F-D/F-E 주석(UiSimulatorConfiguration:24, useSimulatorRunScenarios.ts:4) | **편집(스테일 어구·리터럴 제거)** | AC8·AC7 grep 이 주석도 매칭 → 편집 없으면 AC FAIL. 즉 선택이 아닌 필수 | AC7·AC8 grep 0 |
| D4 | local.properties(git-ignored) `tc.ui.simulator.enabled`·`internal-api.header-value` 제거 여부 | **SPEC 대로 제거** | always-on 정합 + 토큰 표면 축소. 단 ignored 라 팀 공유 안 됨 → 각자 로컬 수동 | AC10(internal-api 0)은 config 디렉터리 대상이라 local 포함 |
| D5 | UiSimulatorProperties 클래스 javadoc(:5-11) 정리 범위 | **enabled/cutover/`{@link #enabled}` 깨진 링크 제거, 클래스 역할 1줄로 축약** | enabled 필드 삭제로 javadoc 스테일 + 깨진 @link 경고 | javadoc 빌드 경고 0(있다면) |

> D1/D2 는 "SPEC 에 없지만 SPEC 의 mandated 제거가 강제하는" 변경 — Implement 가 임의 누락하면 죽은 코드 잔존. Validate 가 반드시 체크.

---

## 다음 단계 (Implement 진입)

```
@.claude/specs/demo-tester-path-removal.md @.claude/specs/demo-tester-path-removal-plan.md 둘 다 읽고 구현.
SPEC 의 Acceptance Criteria 12개 모두 만족하도록 각 AC 당 검증(grep/test) 먼저.
Plan 의 결정 D1(고아 DTO 삭제)/D2(facade properties 제거+호출2곳)/D3(F-D·F-E 주석 편집)/D4/D5 엄수.
구현 순서 §2(의존 역순) 준수 — demo/ 삭제 전 LiveMapController·DemoController 편집, @Import 항목과 빈 삭제 동일 배치.
@Import 두 줄 동반 제거 + validate-module-imports 스킬을 build 전 실행.
산출물: .claude/specs/demo-tester-path-removal-dev-report.md 에 변경 파일 / 테스트 결과 / 결정 기록.
```

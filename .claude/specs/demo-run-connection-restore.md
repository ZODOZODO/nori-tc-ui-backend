# SPEC: 데모 run 연결 상태 원복 (ALL 자연완료 시 EQP_END 누락 수정)

> SDD feature slug: `demo-run-connection-restore`
> 작성 근거: 2026-06-18 live-map 데모 9-매트릭스 실측 (테스트 4·7 ALL 자연완료 후 설비 STARTED 방치 재현)
> SDD 진행 상태: **Validate 완료** (2026-06-19) — Specify→Plan→Implement→Validate. AC1~AC13 [x] (단위/빌드 자연 실행 PASS + 독립 검증), AC14 [~] (실환경 게이트 — 실 gateway/orchestrator 미가동).

## Why (배경 / 문제)

live-map '데모 시작' 모달에서 **ALL(모두 연결)** 테스트를 active 게이트웨이 설비(DIFF_001 / PLC_MBT_001)로 실행하면,
시나리오 1 lot 완주 후 orchestrator run 이 **자연 종료(RunState.TERMINAL)** 된다. 이때 ui-backend 는 EQP_END 를
발행하지 않아 **설비가 STARTED(연결) 상태로 방치**된다. 테스트는 설비 연결 상태에 투명해야(테스트 전 상태로 원복)
하는데 그렇지 못하다.

실측 증거 (9-매트릭스, 2026-06-18):
- 테스트 4(DIFF ALL)·7(PLC ALL) 자연완료 후 설비 desired_state=STARTED 잔존.
- 부작용 1: 게이트웨이가 사라진 데모 설비로 재연결 3회 시도 후 `Outbound reconnect stopped after consecutive failures` ERROR 로그.
- 부작용 2: 같은 설비로 후속 테스트(5·8) 시작 시 `DemoRunConnectionGate` 가 "이미 STARTED" 로 판단해 EQP_START 를
  skip → 실제 연결선은 죽어 있어 데이터 평면 미동작(설비 bind 만 하고 idle; gateway 종료 시 `ALREADY_DISCONNECTED`).

근본 원인 (코드, nori-tc-ui-backend):
- `LiveMapSimulatorRunFacade.startRun` 이 `connectionGate.ensureActiveTargetsConnected(...)` 의 **반환값(=이번 run 이
  EQP_START 로 새로 연결시킨 설비 ID 집합)을 버린다** — `LiveMapSimulatorRunFacade.java:80, 87`. 이 반환 집합이
  "원복 대상"의 유일한 정확한 출처인데(아래 참조) 폐기되어 종료 시 무엇을 끊을지 알 길이 없다.
- ui-backend 에 **자연완료(TERMINAL) 감지 → EQP_END 경로가 전무**하다. EQP_END 는 `rollbackRun`(시작 실패 보상,
  `LiveMapSimulatorRunFacade.java:111`)·`stopRun`(사용자 "종료" 버튼, `:128`) 두 곳에만 존재한다(워크스페이스 grep
  확인: 자연완료 EQP_END 0건. `releaseActiveTargets` 호출처 전수 = :111, :128).

원복 대상 판정 — 왜 "시작 시점 상태 기억"이 필수인가 (코드 사실):
- "이 run 이 연결한 설비"와 "테스트 전부터 연결돼 있던 설비"는 **종료 시점엔 둘 다 `desired_state=STARTED`** 라 현재
  상태만으로는 구분 불가능하다. 따라서 **시작 시점**의 연결 의도(`desired_state`)를 기억해 둬야 한다.
- 시작 판정은 이미 코드가 한다: `ensureActiveTargetsConnected` 가 `isStarted`(=`runtimeState().desiredState()=="STARTED"`,
  `DemoRunConnectionGate.java:246-252`)인 ACTIVE 를 skip 하고(`:68`), 실제 EQP_START 한 집합만 `Set<String>` 으로 반환한다
  (`:58`, `:114`). 즉 **이 반환 집합 = "시작 시 ENDED 였던, 이 run 이 연결한 설비" = 원복 대상**. R1 은 이 집합을 보존한다.
- 현행 `releaseActiveTargets(eqpIds, ...)` 는 인자로 받은 목록 중 **"현재 STARTED 인 ACTIVE"** 를 END 하는 *현재상태
  재해석* 방식이다(`DemoRunConnectionGate.java:195-208`). stopRun(:128)·rollback(:111)이 run 의 **전체 target 목록**을
  넘기므로, **시작 전부터 STARTED 였던(수동 연결 등) 설비도 종료 시 END** 된다 → "테스트 전 상태로 원복" 위반.

기존 결정 번복 (형제 SPEC 명시):
- 형제 `sim-demo-eqp-connect-desired-state.md` 의 Plan 결정 **D-A("explicit stop 만 release")** 와 **D4("stop END =
  현재상태(run ACTIVE STARTED) 재해석")** 를 본 SPEC 이 의도적으로 번복한다. 이유: (1) D-A 는 자연완료(TERMINAL) 경로의
  연결을 영구 방치 — 바로 이 버그. (2) D4 의 현재상태 재해석은 위 사유로 "사전 연결 설비를 끊는" 오동작. 본 SPEC 은
  **모든 종료 경로(자연완료·orchestrator 실패·사용자 stop·시작 실패 rollback)에서 "이 run 이 연결한 설비(R1 집합)"에만
  EQP_END** 하도록 통일한다.

## 올바른 동작 (목표 — 연결/테스트 분리, 상태 투명)

연결(EQP_START/게이트웨이↔설비 링크)과 테스트(연결된 설비 위 시나리오)는 별개이며, 테스트는 설비 연결 상태에
**멱등/투명**해야 한다. 판정 기준은 **테스트 시작 시점**의 ACTIVE 설비 `tc_eqp_state.desired_state`:

| 시작 시 desired_state | 데모 동작 | 테스트 종료 후 |
|---|---|---|
| ENDED (미연결) | 연결(EQP_START) → 시나리오 | 연결 해제(EQP_END)로 **원복**(이 run 이 켰으니 끈다) |
| STARTED (이미 연결) | 연결 그대로, 시나리오만 | 연결 **유지**(건드리지 않음 — 이 run 이 켠 게 아니다) |

- "테스트 종료" 는 사용자 stop 뿐 아니라 **자연완료(TERMINAL) · orchestrator 실패(FAILED) · 시작 실패 rollback** 을
  포함하며, **모든 종료 경로가 같은 규칙**(시작 시 ENDED 였던, 이 run 이 연결한 설비만 END)을 따른다. 즉 원복 대상은
  종료 경로와 무관하게 동일하다.
- PASSIVE 는 본 표의 대상이 아니다(상시 listener, per-run 연결/해제 없음 — 형제 SPEC 으로 생성 시 STARTED 고정).

## Requirements

- **R1 (시작 시 연결 집합 기억).** `startRun` 이 `ensureActiveTargetsConnected` 의 **반환 집합**(=시작 시 desired_state≠STARTED
  였던, 이 run 이 EQP_START 한 ACTIVE 설비)을 `runId → Set<eqpId>` 레지스트리에 기록한다. 빈 집합도 키는 기록한다(추적 대상).
  기록은 `ensureActiveTargetsConnected` 가 **정상 반환한 직후**(ALL: `LiveMapSimulatorRunFacade.java:80` 직후,
  EQUIPMENT_ONLY: `:87` 직후) 수행한다 — `ensureActiveTargetsConnected` 자체가 throw 한 경우엔 레지스트리에 엔트리가
  없어 rollback 의 원자적 remove-and-return 이 빈 결과를 받는다(R5 rollback 의 이중 END/거짓 END 회피 전제).
  - **R1-주의(코드 사실).** `startActiveTargetsBestEffort` 는 **반환값이 void** 이고(`DemoRunConnectionGate.java:165`) 연결을
    보장하지 않는 best-effort 라 captured-set 의 출처가 될 수 없다. 따라서 **UPSTREAM_ONLY 는 레지스트리에 추적하지 않는다**
    (C6). 추적·원복 대상 모드는 `ensureActiveTargetsConnected` 를 쓰는 **ALL · EQUIPMENT_ONLY** 뿐이다.
- **R2 (서버측 reaper — 자연완료/실패 원복).** ui-backend 가 추적 중인 run 의 orchestrator 상태를 주기 폴링
  (`SimulatorOrchestratorClient.getRun`)하여 종료(C2 의 terminal predicate)에 도달하면, **R1 레지스트리의 captured-set 으로만**
  `connectionGate.releaseActiveTargets(capturedSet, ...)`(EQP_END)를 발행하고 추적에서 제거한다. **모달/브라우저 생존과
  무관**하게 서버에서 동작한다.
  - **R2-주의.** reaper 는 **반드시 레지스트리 captured-set** 을 인자로 쓴다. `getRun().targets()`(run 전체 목록)를 쓰면
    사전 연결 설비까지 END 되어 R3 위반 → 금지.
- **R3 (원복 대상 = 이 run 이 연결한 설비만, 모든 종료 경로 공통).** 자연완료·실패·사용자 stop 어느 경로든 EQP_END 는
  R1 집합에만 적용한다. 시작 시 이미 STARTED 였던 설비(=R1 집합 미포함)는 **어느 경로로도 END 하지 않는다**.
- **R4 (이중 EQP_END 방지 — 원자적 claim).** stop·rollback 경로와 reaper 가 동일 run 을 이중 END 하지 않는다. 레지스트리
  제거를 **원자적 remove-and-return**(`remove(runId)` 가 captured-set 을 단 한 호출자에게만 반환)으로 구현하고, **set 을
  받은 쪽만** EQP_END 를 발행한다(remove 를 END 발행보다 먼저). 멱등은 "추적 해제"가 아니라 "원자적 claim"으로 보장된다.
- **R5 (stop·rollback 도 captured-set 으로 통일).** **추적 모드(ALL · EQUIPMENT_ONLY)** 의 stop·rollback 모두 EQP_END
  대상을 run 전체 target 목록(현재상태 재해석)이 아니라 **R1 레지스트리 captured-set** 으로 좁힌다(형제 D4 번복, Why 명시).
  세 발행처(stop·rollback·reaper)는 R4 의 동일한 원자적 remove-and-return 으로 추적 해제 후 set 을 받은 경우에만 END 한다.
  - **stop**: `LiveMapSimulatorRunFacade.stopRun` 의 현행 `releaseActiveTargets(eqpIds, ...)`(`:128`, 인자=run 전체 target)
    을, 원자적 remove-and-return 으로 얻은 **captured-set 인자**로 바꾼다. 판별자는 레지스트리 엔트리 유무다 — 추적된 run
    (ALL · EQUIPMENT_ONLY)은 captured-set 으로 END 하고, 미추적 run(UPSTREAM_ONLY)은 remove 가 빈 결과를 반환하므로
    **R5-주의대로 현행 현재상태 재해석 동작(run 전체 target)으로 폴백**한다(no-op 로 바꾸지 않음).
  - **rollback**: `LiveMapSimulatorRunFacade.rollbackRun`(시작 실패 보상) 의 현행 전체목록 `releaseActiveTargets(eqpIds, ...)`
    (`:111`) 도 **captured-set 인자**로 바꾼다(초기 오안인 "release 호출 제거" 는 채택 금지 — 아래 사유). catch 블록
    (`startRun :93-96`)이 switch 전체(`:78-92`)를 감싸므로 **ALL 모드에서 `ensureActiveTargetsConnected` 가 성공
    (ACTIVE STARTED, R1 기록됨)한 뒤 `awaitPassiveTargetsConnected`(`:81`) 또는 `client.activateUpstream`(`:83`) 이
    실패**하는 경로가 실재한다. 이때 `ensureActiveTargetsConnected` 내부 보상(`DemoRunConnectionGate.java:108 releaseIssued`)
    은 ensureActive 자체 실패 시에만 돌므로 호출되지 않는다 → release 를 그냥 제거하면 이 run 이 켠 ACTIVE 설비가 STARTED
    로 **누수**(본 SPEC 이 고치려는 바로 그 버그가 rollback 경로로 재유입)하고, 직후 `client.stopRun`→CANCELED 이라 reaper
    (CANCELED 제외, C2)도 못 줍는다. 반대로 전체목록을 그대로 두면 사전 연결 설비를 끊는다. captured-set 으로 좁히면 둘 다
    해소된다. `ensureActiveTargetsConnected` 자체가 throw 한 경우엔 R1 기록이 ensureActive 반환 직후라 레지스트리에 엔트리가
    없어 remove-and-return 이 빈 결과 → END 0건(내부 releaseIssued 가 이미 보상)이라 이중 END 도 없다.
  - **R5-주의(미추적 모드).** UPSTREAM_ONLY 는 captured-set 이 없으므로(C6) stop 시 **기존 현재상태 재해석 동작을 보존**
    한다(no-op 로 조용히 바꾸지 않음 — 회귀 방지). UPSTREAM_ONLY 의 완전한 "원복"은 Out of Scope.
- **R6 (빈 등록).** 신규 컴포넌트(추적 레지스트리 / reaper)는 `UiSimulatorConfiguration` 에 `@Bean` 으로 명시 등록한다
  (nori-tc ComponentScan 미사용 컨벤션 — `UiSimulatorConfiguration.java:29~66` 패턴 답습). `@Scheduled` 는 starter 의
  `@EnableScheduling`(`TcUiBackendAutoConfiguration.java:58`)로 활성화돼 있고, web-adapter 내 @Scheduled 선례 존재
  (`UiApiPermissionCache.java:87`).

## Constraints

- **C1.** gateway / orchestrator 코드는 건드리지 않는다 — 데모 연결 관리는 ui-backend 계층에 한정. reaper 는 기존
  ui-backend 빈(`SimulatorOrchestratorClient.getRun` + `DemoRunConnectionGate.releaseActiveTargets`)만 호출하므로
  gateway/orchestrator 무수정으로 EQP_END 발행이 성립한다(타당성 확인됨).
- **C2 (reaper terminal predicate — 단일 정의).** reaper 가 "종료"로 판정하는 orchestrator state(=`getRun` 응답의
  `state` 문자열, `SimulatorRunResponse.state`) 는 **정확히 `TERMINAL` 과 `FAILED` 두 개**다.
  - `TERMINAL` = ALL=ONCE 자연완료(`RunOrchestrationService.java:384`).
  - `FAILED` = run 오류(`:129`) + orchestrator 자체 lease reaper 의 stale 회수(`InMemoryRunLeaseRepository.java:113`,
    `RunLeaseReaper`). 후자가 비정상 중단/일부 abandon 을 FAILED 로 끌어와 본 reaper 가 정리할 수 있게 한다.
  - **`CANCELED` 는 제외**한다. `CANCELED` 는 오직 `stopRun`(`RunOrchestrationService.java:228`)에서만 생성되고, 데모
    경로의 stop 은 `facade.stopRun` 이 동기적으로 captured-set END + 원자적 추적 해제(R4)한다. reaper 가 `CANCELED` 를
    또 잡으면 stop 과 race 로 이중 END 가 되므로, 제외가 곧 race 회피책이다. (참고: `RunState.isTerminal()` 은
    CANCELED 를 포함하지만 `RunState` 는 orchestrator enum 이고 ui-backend 는 문자열만 보므로, reaper predicate 를
    별도로 좁게 정의한다.)
- **C3 (getRun 오류 분기 — 단일 기본값).** reaper 의 `getRun` 호출이 던지는 오류는 분기 처리한다(비우면 누수↔거짓원복
  으로 샌다):
  - **run-not-found(HTTP 404)** → run 이 orchestrator 에 더는 없음(재시작/미지 runId; orchestrator 는 run 을 삭제하지
    않으므로 정상 운영 중엔 거의 재시작 케이스). **종료로 간주** → captured-set 원복 + 추적 해제(누수 방지).
  - **5xx / 연결·타임아웃(IO)** → 일시 오류. **종료 아님** → 추적 유지, 로그, 다음 tick 재시도(거짓 원복 금지).
  - **코드 사실/요구.** 현재 `HttpSimulatorOrchestratorClient` 는 404 와 IO 오류를 **둘 다 `UiBadRequestException`**
    으로 뭉뚱그리고(`:97` IO, `:109` 4xx) 5xx 만 `IllegalStateException`(`:103`)으로 구분한다 → reaper 가 404 를 식별
    불가. 따라서 **`validateResponseStatus`(`:101`)의 4xx 분기에서 404 를 먼저 분기해 ui-backend 신규 전용 예외
    `SimulatorRunNotFoundException` 로 던지도록** 보강한다(단일 메커니즘 — "예외 또는 식별자" 식 OR 금지, C1 위반 아님
    — ui-backend 코드). 5xx(`IllegalStateException`)·기타 4xx/IO(`UiBadRequestException`)는 그대로 둔다. `getRun` 의
    정상 반환 계약(기존 `facade.stopRun:123` 사용)은 보존한다.
- **C4 (reaper 주기/타임아웃 — 기본값 명시).** reaper 폴링 주기는 `UiSimulatorProperties`(prefix `tc.ui.simulator`,
  `UiSimulatorProperties.java:12`)에 `reaperIntervalMs` 필드를 추가해 둔다 — 기본값 **5000ms**(orchestrator
  `RunHeartbeatScheduler`(5s)·`RunLeaseReaper`(10s)와 동급 cadence, 자연완료 후 수 초 내 원복). `@Scheduled` 는
  `fixedDelayString = "${tc.ui.simulator.reaper-interval-ms:5000}"` 로 바인딩(`UiApiPermissionCache.java:87` 패턴 답습).
  getRun 호출 타임아웃은 기존 `readTimeoutMs`(`UiSimulatorProperties.java:18`, 기본 5000ms)를 재사용한다(신규 프로퍼티 불요).
- **C5.** PASSIVE 설비(상시 listener, per-run 동작 없음)는 본 변경 대상이 아니다 — `ensureActiveTargetsConnected`
  가 이미 PASSIVE 를 skip 하므로 R1 집합에 포함되지 않는다.
- **C6 (추적 대상 모드).** 레지스트리/reaper 추적 대상은 `ensureActiveTargetsConnected` 를 쓰는 **ALL · EQUIPMENT_ONLY**
  뿐이다. **UPSTREAM_ONLY 는 추적하지 않으며**(R1-주의 — best-effort void) stop 은 기존 동작 보존(R5-주의), reaper
  원복은 비대상(Out of Scope). EQUIPMENT_ONLY 는 자연완료가 없어 reaper 는 그 run 의 stop(facade) 또는 FAILED/404 시에만
  정리한다.

## Acceptance Criteria (기계 검증 가능, 단일 노드)

- [x] **AC1.** 단위 테스트 PASS — `startRun(ALL, [시작 시 ENDED 인 active eqp])` 호출 시, 추적 레지스트리의 runId 엔트리가
  `ensureActiveTargetsConnected` 반환 집합과 동일하게 기록된다.
- [x] **AC2.** 단위 테스트 PASS — 추적된 run 의 `getRun.state()=="TERMINAL"` 이면, reaper 가 **레지스트리 captured-set 으로**
  `releaseActiveTargets` 를 **정확히 1회** 호출한다(mock client TERMINAL, mock gate argument-captor verify).
- [x] **AC3.** 단위 테스트 PASS — 추적된 run 의 `getRun.state()=="FAILED"` 이면, reaper 가 `releaseActiveTargets` 를 1회
  호출하고 레지스트리에서 해당 runId 를 제거한다.
- [x] **AC4.** 단위 테스트 PASS — 추적된 run 의 `getRun.state()=="CANCELED"` 이면, reaper 가 `releaseActiveTargets` 를
  **호출하지 않는다**(stop 경로 전담 — C2 race 회피).
- [x] **AC5.** 단위 테스트 PASS — 시작 시 이미 STARTED 였던 설비(= `ensureActiveTargetsConnected` 반환 미포함)는 **stop·
  rollback·reaper 어느 경로에서도** `releaseActiveTargets` 인자에 포함되지 않는다(테스트 전 상태 원복 — R3).
- [x] **AC6.** 단위 테스트 PASS — `startRun` 으로 캡처된 captured-set 이 비어 있지 않은 run 을 `stopRun(runId)` 하면,
  `releaseActiveTargets` 가 **레지스트리 captured-set 인자로** 호출되고(전체 target 목록 아님), 동일 runId 가 레지스트리
  에서 제거되어 이후 reaper tick 이 해당 run 에 대해 `releaseActiveTargets` 를 호출하지 않는다(이중 END 0회 — R4/R5).
- [x] **AC7.** 단위 테스트 PASS — ALL 모드에서 `ensureActiveTargetsConnected` 가 성공(captured-set 비어있지 않음)한 뒤
  `awaitPassiveTargetsConnected` 가 throw 하면, `rollbackRun` 이 `releaseActiveTargets` 를 **레지스트리 captured-set
  인자로** 정확히 1회 호출하고 동일 runId 를 레지스트리에서 제거한다(시작 실패 rollback 누수 0 — R5).
- [x] **AC8.** 단위 테스트 PASS — reaper 의 `getRun` 이 `SimulatorRunNotFoundException`(404 미존재 run)을 던지면, reaper 가
  captured-set 으로 `releaseActiveTargets` 를 정확히 1회 호출하고 레지스트리에서 해당 runId 를 제거한다(C3 404→종료).
- [x] **AC9.** 단위 테스트 PASS — reaper 의 `getRun` 이 **HTTP 5xx**(`IllegalStateException`)를 던지면, reaper 가
  `releaseActiveTargets` 를 호출하지 않고 해당 runId 를 레지스트리에 **유지**한다(C3 일시오류→재시도, 거짓 원복 금지).
- [x] **AC10.** 단위 테스트 PASS — reaper 의 `getRun` 이 **IO/타임아웃**(`UiBadRequestException`, 404 아님)을 던지면, reaper 가
  `releaseActiveTargets` 를 호출하지 않고 해당 runId 를 레지스트리에 **유지**한다(C3 일시오류→재시도, 거짓 원복 금지).
- [x] **AC11.** 단위 테스트 PASS — `startRun(UPSTREAM_ONLY, ...)` 는 추적 레지스트리에 runId 엔트리를 **기록하지 않는다**
  (C6 — best-effort void 라 captured-set 없음, reaper 비대상).
- [x] **AC12.** `./gradlew :ui:adapter:tc-ui-web-adapter:build` 전체 PASS(회귀 없음).
- [x] **AC13.** ui-backend ApplicationContext 기동 테스트 PASS — 신규 레지스트리 / reaper 빈이 `UiSimulatorConfiguration`
  `@Bean` 으로 등록되어 컨텍스트가 정상 로드된다.
- [x] **AC14.** 실환경 e2e 검증 완료 (2026-06-19, 로컬 데모 환경 gateway+business+simulator+ui-backend 기동).
  run `run-82328ff50714`(testType=E2E→ALL, scenarioId=normal-flow, eqpId=DIFF_001) 자연완료
  (orchestrator `state=TERMINAL`, target COMPLETED, completedCycleCount=1, erroredCycleCount=0) 직후:
  ui-backend reaper(@Scheduled `scheduling-1` 스레드)가 `EQP_END 완료. eqpId=DIFF_001` 를 **정확히 1회** 발행하고
  (5s 폴링이나 claim 후 추적 해제로 반복 END 0건), gateway 가 `LIFECYCLE_TRANSITION_APPLIED ... transition=END,
  result=DISCONNECTED`(END APPLIED 1회) 로 적용해 DIFF_001 이 `connectionState=ENDED / controlState=OFFLINE` 로 원복됨.
  gateway 로그 전체에서 `Outbound reconnect stopped after consecutive failures` ERROR **0건**.
  DIFF_001 ALL 테스트 자연완료 직후, gateway 로그에 해당
  설비 EQP_END(`endAsync`) 발행이 정확히 1회 관측되고, 동일 설비에 대한 `Outbound reconnect stopped after consecutive
  failures` ERROR 가 미발생한다.

## Out of Scope

- 공유 MES 토픽(`dev.tc-evt` / `dev.mat-evt`) 의 진짜 MES ↔ 시뮬레이터 중복/모순 응답(별개 환경 이슈, 수용으로 종결).
- gateway / orchestrator 측 코드 변경.
- PASSIVE 설비 연결 정책 변경.
- UPSTREAM_ONLY run 의 종료 시 연결 정리(C6 — best-effort void 라 captured-set 없음).
- **모달을 stop 없이 닫은 UNTIL_STOPPED(EQUIPMENT_ONLY) run 의 abandon 정리.** ⚠️ 정정: "기존 orchestrator lease 가
  처리"한다는 전제는 **거짓**이다 — orchestrator 는 `RunHeartbeatScheduler`(@Scheduled 5s)가 모든 active run 을 **자체
  heartbeat**(`RunOrchestrationService.java:239-246`)하므로, 시나리오가 계속 도는 abandon UNTIL_STOPPED run 은 stale 가
  되지 않아 FAILED/terminal 로 자동 전이되지 않는다. 따라서 본 reaper 도 이를 정리하지 못하고 **설비가 STARTED 로 잔존
  (잔여 누수)한다 — 본 SPEC 은 이를 수용**한다. 추적 TTL/강제 정리는 도입하지 않는다(의도적 장기 UNTIL_STOPPED run 과
  abandon 을 구분할 신호가 없어 정상 run 을 끊을 위험). orchestrator 재시작 시엔 getRun 404→C3 경로로 정리된다.

## 다음 단계 진입 프롬프트

### Plan 진입 (새 세션, Plan Mode)
```
@nori-tc-ui-backend/.claude/specs/demo-run-connection-restore.md 보고 구현 계획 수립.
영향 파일 / 단계 / 위험 / 롤백 방법 포함. 실행 / 코드 수정 금지.
설계 결정은 SPEC 에 이미 박혀 있음(번복 금지, 구체화만): captured-set 은 모든 종료 경로 공통(stop·rollback·reaper,
R3/R5), terminal predicate={TERMINAL,FAILED} CANCELED 제외(C2), getRun 404→종료·5xx·IO→재시도(C3),
UPSTREAM_ONLY 미추적(C6). 계획에 구체화할 것: (1) 레지스트리 자료구조와 원자적 remove-and-return(R4)
구현 + 등록/해제 지점(startRun 기록=ensureActive 반환 직후 / stopRun·rollback·reaper 원자 claim), (2) client.getRun 의
404→`SimulatorRunNotFoundException` 분기 보강(C3 — 기존 반환 계약 보존), (3) @Bean 배선 + @Scheduled
`reaper-interval-ms:5000` 프로퍼티(C4), (4) stopRun·rollback 의 captured-set 전환(R5)이 기존 테스트
(형제 SPEC `releaseEndsOnlyActiveStartedTargets` 등 현재상태 재해석 가정)에 주는 영향.
산출물: nori-tc-ui-backend/.claude/specs/demo-run-connection-restore-plan.md 에 저장.
```

### Implement 진입 (새 세션)
```
@nori-tc-ui-backend/.claude/specs/demo-run-connection-restore.md
@nori-tc-ui-backend/.claude/specs/demo-run-connection-restore-plan.md 둘 다 읽고 구현.
SPEC 의 AC1~AC13 각각에 대해 실패 테스트 먼저(AC14 는 실환경 수동/e2e). Plan 의 결정 엄수.
gateway/orchestrator 무수정(C1), 신규 빈 @Bean 등록(R6), 모든 종료 경로 captured-set 통일(stop·rollback·reaper, R3/R5).
산출물: nori-tc-ui-backend/.claude/specs/demo-run-connection-restore-dev-report.md 에
변경 파일 / 테스트 결과 / 결정 기록.
```

### Validate 진입 (새 세션)
```
@nori-tc-ui-backend/.claude/specs/demo-run-connection-restore.md
@nori-tc-ui-backend/.claude/specs/demo-run-connection-restore-plan.md
@nori-tc-ui-backend/.claude/specs/demo-run-connection-restore-dev-report.md
셋 다 읽고 AC1~AC14 + Plan 결정 충족 검증. 통과 항목만 [x] 로 업데이트.
```

# SPEC — demo-run 연결-후-MES 배리어 비동기화

## Why
데모-run(ALL/EQUIPMENT_ONLY) 시작이 `LiveMapSimulatorRunFacade.startRun` 안에서 **단일 HTTP 요청 동기**로
createRun → 연결확인(gate) → activateUpstream(MES) 을 수행한다. 연결확인 gate(`DemoRunConnectionGate`)는 설비 수에
비례해 시간이 늘고(`dualRequestTimeoutMs`=10s/phase), 대량 설비(예 54 socket / 100 PLC) 시 요청 전체가 외부
프록시 HTTP 타임아웃(~15-20s)을 초과해 **504** 가 난다. 504 로 요청이 끊기면 `activateUpstream` 에 도달하지 못해
MES 가 영영 주입되지 않고, 설비는 `EQP_CONDITION_CHECK` 를 기다리다 WAIT timeout → 채널 close/flap, business 0.

### 측정 근거 (2026-06-25 라이브)
- E2E(24 PASSIVE socket) = 11.0s, HTTP 200
- EQUIPMENT_ONLY(동일 24, gate 가 PASSIVE 즉시 skip) = **1.33s** → createRun 단독 = 1.3s (저렴)
- ∴ gate 연결확인(awaitPassive)이 ~9.7s 로 지배적이고 설비수 비례. 54 = 504.

## Requirements
- R1. `POST /api/live-map/demo-runs` 는 orchestrator `createRun`(저렴, ~1-6s)까지만 동기로 하고 **즉시 200+run 반환**.
- R2. 연결확인(ensureActive/awaitPassive) + activateUpstream + 실패 시 rollback 은 **주입 Executor 백그라운드**에서 수행.
- R3. 대량 설비 연결확인이 10s 를 초과하므로 백그라운드 gate 는 별도 **넉넉한 연결-확인 타임아웃**(기본 120s, 설정
  가능)을 쓴다. rollback EQP_END 대기는 기존 `dualRequestTimeoutMs`(10s) 유지.
- R4. **race-safety**: 백그라운드 activation 진행 중 사용자가 `stopRun`/`stopTarget` 을 호출해도 누수 0 —
  백그라운드가 EQP_START 한 captured 설비는 반드시 release(EQP_END)된다(stop 이 가져갔든 백그라운드가 self-release).
- R5. 실패 표면화: 백그라운드 연결/활성 실패 시 `client.stopRun`(run → CANCELED)으로 표시 → 프론트 `getRun` 폴링이
  terminal 로 관측(동기 예외 팝업 대체). 조용한 부분 시작 금지(기존 invariant 보존).
- R6. captured-set 규율(R5 기존) 보존: 이 run 이 EQP_START 한 설비만 release, 사전연결/PASSIVE 는 보존.
- R7. 프론트(`live-map-demo-run.api.ts`) 무변경 — 이미 createRun 후 `getRun` 폴링.

## Constraints
- ui-backend 단일 레포. orchestrator/gateway/common 코드 변경 없음(createRun 저렴해 불요).
- 기존 비동기 컨벤션 답습: `ThreadPoolTaskExecutor` + `copyMdcAndContextClassLoaderTaskDecorator()`(MDC/traceId 전파),
  `@EnableScheduling`(reaper) 와 동거.
- ModuleConfiguration: 기존 `@Configuration`(UiSimulatorConfiguration / TcUiBackendAutoConfiguration)에 @Bean 추가 →
  신규 @Import 불요. 새 @Component 미도입.
- orchestrator stopRun 이 `pendingUpstreamByRun.remove`(238줄) → activateUpstream 멱등 no-op 으로 stop-중 MES 안전(검증됨).

## Acceptance Criteria
- [x] AC1. `startRun(ALL)` 은 gate 완료를 **기다리지 않고** createRun 응답을 즉시 반환한다(capturing executor 검증
  — `startRunReturnsImmediatelyAndActivatesInBackground` PASS).
- [x] AC2. 백그라운드 작업 실행 시 createRun→ensureActive→awaitPassive→activateUpstream 순서로 호출된다
  (`allModeCreatesRunThenConnectsThenActivatesUpstreamInOrder` PASS, inline executor).
- [x] AC3. 백그라운드 gate 실패 시 `activateUpstream` 미호출 + `stopRun` 호출 + 예외 요청스레드 미전파
  (`startRunRolledBackWhenConnectionGateFails` PASS).
- [x] AC4. ALL captured-set 추적: ensureActive 반환 집합만 commit, 사전 STARTED 미포함(`startRunTracksCapturedSetForAll` PASS).
- [x] AC5. race: stop 이 백그라운드 commit 보다 먼저 claim 하면 백그라운드가 captured self-release + activate 중단
  (`backgroundSelfReleasesCapturedWhenStopClaimsFirst` PASS).
- [x] AC6. EQUIPMENT_ONLY/UPSTREAM_ONLY 모드별 호출 불변(`equipmentOnly...`/`upstreamOnly...` PASS).
- [x] AC7. `./gradlew :ui:adapter:tc-ui-web-adapter:test :ui:starter:tc-ui-backend-starter:test -PuseLocalMaven` PASS
  (LiveMapSimulatorRunFacadeTest tests=14/0F, UiSimulatorConfigurationContextTest tests=1/0F, BUILD SUCCESSFUL 46s).
- [ ] AC8. 배포 후 라이브: 54대 E2E 가 504 없이 200, 백그라운드에서 business message_processing>0, flap 소멸. **사용자 push/deploy 필요.**

## Out of Scope
- orchestrator createRun 의 inject 병렬화(저렴해 불요).
- 프론트 변경 / 새 status 엔드포인트(getRun 폴링으로 충분).
- gate 의 연결 판정 로직 자체 변경(control_state=REMOTE 판정 보존).

## 다음 단계 진입 프롬프트
- Implement: @.claude/specs/demo-run-async-barrier.md @.claude/specs/demo-run-async-barrier-plan.md 읽고 각 AC 당 실패 테스트 먼저.

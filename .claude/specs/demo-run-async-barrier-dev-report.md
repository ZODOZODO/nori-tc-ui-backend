# DEV REPORT — demo-run 연결-후-MES 배리어 비동기화

## 변경 파일 (nori-tc-ui-backend, 단일 레포)
- `ui/adapter/.../config/UiSimulatorProperties.java` — `connectionConfirmTimeoutMs`(기본 120000L) + getter/setter.
- `ui/adapter/.../simulator/DemoRunConnectionRegistry.java` — `markPending`(=track(emptySet)) + `commitCaptured`
  (computeIfPresent 기반 원자 commit, 키 없으면 false → 백그라운드 self-release 신호).
- `ui/starter/.../TcUiBackendAutoConfiguration.java` — `demoRunActivationExecutor`(Executor, ThreadPoolTaskExecutor
  core2/max8/queue64 + `copyMdcAndContextClassLoaderTaskDecorator()`) 빈 + 상수 + import. 빈 이름 =
  `LiveMapSimulatorRunFacade.ACTIVATION_EXECUTOR_BEAN_NAME`.
- `ui/adapter/.../simulator/LiveMapSimulatorRunFacade.java` — 생성자에 `Executor activationExecutor` +
  `long connectionConfirmTimeoutMs`. `startRun`: mode 검증(createRun 전) → createRun 동기 → 모드별 백그라운드 제출
  → 즉시 반환. 신규 private: `activateAllInBackground` / `activateEquipmentOnlyInBackground` /
  `activateUpstreamOnlyInBackground`. `rollbackRun`: claim 후 captured 비면 release skip(불필요 호출 회피).
- `ui/adapter/.../config/UiSimulatorConfiguration.java` — facade 빈에 `@Qualifier(ACTIVATION_EXECUTOR_BEAN_NAME)
  Executor` + `UiSimulatorProperties.getConnectionConfirmTimeoutMs()` 주입.
- (테스트) `LiveMapSimulatorRunFacadeTest` — INLINE_EXECUTOR(Runnable::run)로 기존 순서/rollback 검증 유지,
  assertThrows 2건 제거(비동기 흡수), 신규 AC1/AC5 테스트(capturing executor) 추가.
- (테스트) `UiSimulatorConfigurationContextTest` — 활성화 executor 빈 제공(@Qualifier 충족).

## 설계 결정
- **스코프 = ui-backend 단일 레포**. 측정으로 확정: E2E(24)=11.0s vs EQUIPMENT_ONLY(동일 24, gate skip)=1.33s →
  createRun 저렴, gate 연결확인이 지배·설비수 비례. ∴ gate+activate 만 백그라운드로. orchestrator 변경 불요.
- **race-safety(R4)**: markPending 으로 추적을 요청 스레드에서 먼저 열어 stop 이 항상 tracked run claim. 백그라운드
  commitCaptured 가 atomic — stop 이 선점하면 false → 백그라운드가 captured self-release(EQP_END). orchestrator
  stopRun 이 pendingUpstreamByRun.remove → activateUpstream 멱등 no-op(검증됨)이라 MES 레벨도 안전.
- **실패 표면화(R5)**: 백그라운드 실패 → rollbackRun(stopRun → run CANCELED) → 프론트 getRun 폴링이 terminal 관측.
  예외는 요청 스레드로 전파 안 함(이미 200 반환). 조용한 부분 시작 금지 invariant 보존.
- **타임아웃 분리**: 연결확인 = connectionConfirmTimeoutMs(120s, 백그라운드라 프록시 압박 無). rollback EQP_END /
  UPSTREAM best-effort = 기존 dualRequestTimeoutMs(10s).
- 컨벤션 답습: ThreadPoolTaskExecutor + MDC/classloader TaskDecorator(백그라운드 startAsync→Kafka EQP_START 안전).

## 테스트 결과 (`-PuseLocalMaven`)
```
LiveMapSimulatorRunFacadeTest        tests="14" skipped="0" failures="0" errors="0"
UiSimulatorConfigurationContextTest  tests="1"  skipped="0" failures="0" errors="0"
:ui:adapter:tc-ui-web-adapter:test / :ui:starter:tc-ui-backend-starter:test  BUILD SUCCESSFUL in 46s
```
- AC1 `startRunReturnsImmediatelyAndActivatesInBackground` PASS — createRun 후 즉시 반환, 작업 실행 전 gate/activate 미호출, 작업 실행 후 호출.
- AC2 `allModeCreatesRunThenConnectsThenActivatesUpstreamInOrder` PASS — 순서 보존.
- AC3 `startRunRolledBackWhenConnectionGateFails` PASS — 예외 미전파 + stopRun + release 0.
- AC4 `startRunTracksCapturedSetForAll` PASS — captured commit, 사전STARTED 제외.
- AC5 `backgroundSelfReleasesCapturedWhenStopClaimsFirst` PASS — stop 선점 시 백그라운드 self-release, activate 미호출.
- AC6 EQUIPMENT_ONLY/UPSTREAM_ONLY 모드별 호출 불변 테스트 PASS.
- AC7 빌드/테스트 PASS(위).

## 미완 (live-gate)
- AC8: 배포(사용자 push) 후 라이브 54대(또는 154) E2E 200 + business message_processing>0 + flap 0. **사용자 push/deploy 필요**.

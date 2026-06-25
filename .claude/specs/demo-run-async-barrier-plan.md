# PLAN — demo-run 연결-후-MES 배리어 비동기화

## 변경 파일
1. `ui/adapter/.../config/UiSimulatorProperties.java` — `connectionConfirmTimeoutMs`(기본 120000L) 추가 + getter/setter.
2. `ui/starter/.../TcUiBackendAutoConfiguration.java` — `demoRunActivationExecutor`(Executor) 빈 추가
   (uiEqpManagementExecutor 미러: corePool/maxPool/queue 상수 + `copyMdcAndContextClassLoaderTaskDecorator()` +
   waitForTasksToCompleteOnShutdown). bean name 상수.
3. `ui/adapter/.../simulator/DemoRunConnectionRegistry.java` — `markPending(runId)` + `commitCaptured(runId, captured)`
   (computeIfPresent 기반: 존재하면 교체 후 true, 없으면 false) 추가. 기존 track/claim/release/trackedRunIds 보존.
4. `ui/adapter/.../simulator/LiveMapSimulatorRunFacade.java` — 생성자에 `Executor activationExecutor` +
   `long connectionConfirmTimeoutMs` 추가. `startRun`: createRun 후 모드별 백그라운드 작업 제출하고 즉시 반환.
   백그라운드 메서드(runAllActivation/runEquipmentActivation/runUpstreamActivation)가 gate+activate+rollback 수행.
5. `ui/adapter/.../config/UiSimulatorConfiguration.java` — facade 빈에 `@Qualifier(...) Executor` +
   `UiSimulatorProperties` 주입해 생성자 전달.
6. (테스트) `LiveMapSimulatorRunFacadeTest.java` — Executor 주입(inline/capturing), 동기→비동기 계약 반영.

## 설계 — 백그라운드 흐름 (ALL)
```
startRun(ALL):
  response = client.createRun(...)
  registry.markPending(runId)                 // 추적 시작(빈 set) — race 기준점
  activationExecutor.execute(() -> runAllActivation(runId, eqpIds))
  return response                              // 즉시
runAllActivation(runId, eqpIds):
  try:
    captured = gate.ensureActiveTargetsConnected(eqpIds, connectionConfirmTimeoutMs)
    if !registry.commitCaptured(runId, captured):   // stop 이 먼저 claim → 취소됨
        gate.releaseActiveTargets(captured, dualTimeoutMs)   // self-release(누수 0)
        return
    gate.awaitPassiveTargetsConnected(eqpIds, connectionConfirmTimeoutMs)
    client.activateUpstream(runId)
  catch RuntimeException e:
    log.error(...); rollbackRun(runId, dualTimeoutMs, e)   // 예외 흡수(백그라운드)
```
- EQUIPMENT_ONLY: markPending → ensureActive → commitCaptured(false 면 self-release) → 끝(awaitPassive/activate 없음).
- UPSTREAM_ONLY: 추적 안 함 → startActiveTargetsBestEffort 만 백그라운드(현행 best-effort 유지).
- `rollbackRun` 기존 로직 재사용(claim 기반). markPending 으로 ensureActive throw 시에도 claim 대상 존재(빈 set) →
  release 0(기존과 동일, 이중 END 0).

## race 처리 (R4/AC5)
- `markPending`(요청 스레드, 반환 전) → stop 은 항상 tracked run 을 claim.
- 백그라운드 `commitCaptured` 가 atomic: stop 의 claim 이 먼저면 entry 제거됨 → commitCaptured=false → 백그라운드가
  captured self-release. stop 의 claim 이 빈 set 을 받았어도(release 0) 설비는 백그라운드가 책임지고 END.
- 결과: 어느 인터리빙이든 EQP_START 된 설비는 정확히 한 번 END(누수/이중 0).

## 위험 / 롤백
- 위험: executor 포화(동시 다수 run) → 풀 max + queue 로 bound, 초과는 CallerRuns 또는 reject 로그. reaper 가 안전망.
- 위험: 백그라운드 traceId 로그 단절 → TaskDecorator 로 MDC 전파(uiEqpManagementExecutor 동일).
- 롤백: 변경 전부 ui-backend 한정. 빈/생성자 추가라 revert 단순. 배포 전 단위테스트 게이트.

## 검증
- 단위: `./gradlew :ui:adapter:tc-ui-web-adapter:test -PuseLocalMaven` (정확 경로는 settings.gradle 확인).
- 라이브(배포 후, 사용자 push): 54대 E2E 200 + business>0 + flap 0.

# PLAN: 데모 run 연결 상태 원복 (ALL 자연완료 시 EQP_END 누락 수정)

> SDD feature slug: `demo-run-connection-restore`
> 입력 SPEC: [demo-run-connection-restore.md](demo-run-connection-restore.md)
> 작성: 2026-06-18 / Plan Mode (코드 수정·빌드·테스트 실행 없음)

SPEC 의 설계 결정(captured-set 모든 종료 경로 통일 R3/R5, predicate={TERMINAL,FAILED} C2, getRun 404→종료·5xx/IO→재시도 C3, UPSTREAM_ONLY 미추적 C6, gateway/orchestrator 무수정 C1)은 **확정**이며 본 계획은 그것을 번복하지 않고 "어떻게 구현할지"만 구체화한다.

---

## 0. 코드 사실 확인 (Plan 근거)

읽고 확인한 파일 (라인은 현재 HEAD 기준):

| 파일 | 핵심 사실 |
|---|---|
| [LiveMapSimulatorRunFacade.java](../../ui/adapter/tc-ui-web-adapter/src/main/java/com/nori/tc/ui/adapters/web/simulator/LiveMapSimulatorRunFacade.java) | `ensureActiveTargetsConnected` 반환을 버림(:80, :87). rollback(:104-115) `releaseActiveTargets(eqpIds,...)`(:111). stopRun(:121-130) getRun→stopRun→`releaseActiveTargets`(:128). catch(:93-96)가 switch 전체(:78-92)를 감쌈. |
| [DemoRunConnectionGate.java](../../ui/tc-ui-core/src/main/java/com/nori/tc/ui/core/service/DemoRunConnectionGate.java) | `ensureActiveTargetsConnected` 가 started 집합 반환(:114). `releaseActiveTargets`(:195-208)는 인자 중 ACTIVE&&STARTED&&!PASSIVE 만 END(현재상태 재해석). `startActiveTargetsBestEffort`(:165) void. 내부 보상 `releaseIssued`(:108). |
| [SimulatorRunResponse.java](../../ui/adapter/tc-ui-web-adapter/src/main/java/com/nori/tc/ui/adapters/web/simulator/SimulatorRunResponse.java) | `state` 는 String. |
| [HttpSimulatorOrchestratorClient.java](../../ui/adapter/tc-ui-web-adapter/src/main/java/com/nori/tc/ui/adapters/web/simulator/HttpSimulatorOrchestratorClient.java) | `validateResponseStatus`(:101): ≥500→`IllegalStateException`(:103), 409→`UiConflictException`(:107), ≥400→`UiBadRequestException`(:110). IO/Interrupt→`UiBadRequestException`(:97). getRun timeout=`readTimeoutMs`(exchange :81). |
| [UiSimulatorProperties.java](../../ui/adapter/tc-ui-web-adapter/src/main/java/com/nori/tc/ui/adapters/web/config/UiSimulatorProperties.java) | prefix `tc.ui.simulator`. `readTimeoutMs`=5000(:18). `reaperIntervalMs` 없음(추가 대상). |
| [UiSimulatorConfiguration.java](../../ui/adapter/tc-ui-web-adapter/src/main/java/com/nori/tc/ui/adapters/web/config/UiSimulatorConfiguration.java) | `@Configuration`. client/modeMapper/targetAssembler/facade @Bean. `DemoRunConnectionGate`·`UiDualRequestProperties` 빈 주입 가능. |
| [UiWebAdapterModuleConfiguration.java](../../ui/adapter/tc-ui-web-adapter/src/main/java/com/nori/tc/ui/adapters/web/config/UiWebAdapterModuleConfiguration.java) | `UiSimulatorConfiguration.class` 이미 `@Import`(:47). → **그 안에 @Bean 추가만으로 등록 완료**(신규 @Import 라인 불요). |
| [TcUiBackendAutoConfiguration.java](../../ui/starter/tc-ui-backend-starter/src/main/java/com/nori/tc/ui/starter/TcUiBackendAutoConfiguration.java) | `@EnableScheduling`(:58) — 컨텍스트 전역. @Bean 인스턴스의 `@Scheduled` 도 동작. |
| [UiApiPermissionCache.java](../../ui/adapter/tc-ui-web-adapter/src/main/java/com/nori/tc/ui/adapters/web/security/UiApiPermissionCache.java) | `@Scheduled(fixedDelayString="${...:300000}")`(:87) 선례. |
| [UiApiExceptionHandler.java](../../ui/adapter/tc-ui-web-adapter/src/main/java/com/nori/tc/ui/adapters/web/controller/support/UiApiExceptionHandler.java) | `UiNotFoundException`→404(:104), `UiBadRequestException`→400(:68), 미분류→500(:205). |
| [build.gradle.kts](../../ui/adapter/tc-ui-web-adapter/build.gradle.kts) | `testImplementation(libs.spring.boot.starter.test)`(:27) → `ApplicationContextRunner` 사용 가능. |
| 기존 테스트 | [LiveMapSimulatorRunFacadeTest.java](../../ui/adapter/tc-ui-web-adapter/src/test/java/com/nori/tc/ui/adapters/web/simulator/LiveMapSimulatorRunFacadeTest.java), [DemoRunConnectionGateTest.java](../../ui/tc-ui-core/src/test/java/com/nori/tc/ui/core/service/DemoRunConnectionGateTest.java) |

**핵심 발견 (변경 범위 축소에 결정적):** `releaseActiveTargets` 메서드 자체는 **수정하지 않는다**. "현재상태 재해석"(ACTIVE&&STARTED 필터)은 gate 메서드 안에 그대로 두고(=untracked 폴백이 사용), **바뀌는 것은 facade/reaper 가 인자로 무엇을 넘기느냐(captured-set vs 전체목록)뿐**이다. 따라서 `DemoRunConnectionGateTest.releaseEndsOnlyActiveStartedTargets` 는 **그대로 통과**(item 7 참조).

---

## 1. 영향 파일 목록

### 신규 (3)
| 파일 | 모듈/패키지 | 역할 |
|---|---|---|
| `DemoRunConnectionRegistry.java` | web-adapter / `…adapters.web.simulator` | `runId → Set<eqpId>` 추적. track / 원자적 claim / 키 스냅샷. facade·reaper 공유. |
| `DemoRunConnectionReaper.java` | web-adapter / `…adapters.web.simulator` | `@Scheduled` 폴링: 추적 run getRun→predicate/오류 분기→release+claim. |
| `SimulatorRunNotFoundException.java` | web-adapter / `…adapters.web.simulator` | 404 전용 신호. `extends UiNotFoundException`(→404 매핑·distinct catch). |

### 수정 (4)
| 파일 | 변경 |
|---|---|
| `HttpSimulatorOrchestratorClient.java` | `validateResponseStatus` 에 404 분기 추가(409 분기 뒤, 일반 4xx 앞). |
| `UiSimulatorProperties.java` | `reaperIntervalMs`(기본 5000) 필드 + getter/setter. |
| `LiveMapSimulatorRunFacade.java` | 생성자에 registry 주입. startRun=캡처 기록. stopRun/rollback=원자 claim+captured-set. |
| `UiSimulatorConfiguration.java` | registry / reaper @Bean 2개 추가. |

### 테스트 (신규 2 + 수정 1)
| 파일 | 변경 |
|---|---|
| `DemoRunConnectionReaperTest.java` (신규) | AC2/3/4/8/9/10. |
| `UiSimulatorConfigurationContextTest.java` (신규) | AC13 (ApplicationContextRunner 슬라이스). |
| `LiveMapSimulatorRunFacadeTest.java` (수정) | 생성자 시그니처 + AC1/5/6/7/11 + rollback 단언 갱신. |
| `HttpSimulatorOrchestratorClientTest.java` (신규 or 기존 보강) | 404→`SimulatorRunNotFoundException`, 5xx/409/기타4xx 보존(AC8 지지). |

`DemoRunConnectionGateTest.java` 는 **무변경**(gate 메서드 불변).

---

## 2. 상세 설계 (SPEC 요구 1~8 구체화)

### (1) 추적 레지스트리 — 자료구조 + 원자적 remove-and-return

```java
// web-adapter / com.nori.tc.ui.adapters.web.simulator
public final class DemoRunConnectionRegistry {
    private final ConcurrentMap<String, Set<String>> capturedByRunId = new ConcurrentHashMap<>();

    /** R1: 빈 집합도 키 기록(추적 대상). 불변 스냅샷 저장. */
    public void track(String runId, Set<String> capturedEqpIds) {
        capturedByRunId.put(runId, Set.copyOf(capturedEqpIds));
    }

    /** R4: 원자적 claim — 단일 호출자만 set 수령, 나머지는 empty. */
    public Optional<Set<String>> claim(String runId) {
        return Optional.ofNullable(capturedByRunId.remove(runId));
    }

    /** reaper 순회용 키 스냅샷(CME 회피). */
    public Set<String> trackedRunIds() {
        return Set.copyOf(capturedByRunId.keySet());
    }
}
```

- **원자성 근거:** `ConcurrentHashMap.remove(key)` 는 직전 값을 원자적으로 반환하고 키를 제거한다. 동시 호출 시 **정확히 한 호출자만** non-null 을 받고 나머지는 null → R4 "set 을 받은 쪽만 END" 가 자료구조 레벨에서 보장. remove(=claim)가 END 발행보다 먼저 일어남.
- **thread-safety:** reaper 스레드(scheduled) vs 요청 스레드(startRun/stopRun) 가 같은 맵을 친다. CHM 이 동시성 보장. 값은 `Set.copyOf` 불변 스냅샷이라 값 자체 변이 레이스 없음.
- **모듈 경계:** facade(web-adapter)와 reaper(web-adapter) 둘 다 web-adapter → 레지스트리는 **web-adapter** 에 둔다(core 로 올릴 이유 없음. core 의 gate 는 레지스트리를 모름).

### (2) startRun 기록 지점

`ensureActiveTargetsConnected` 반환을 지역 변수로 캡처 → **정상 반환 직후** track. throw 시엔 track 미실행(레지스트리 무엔트리) → rollback claim 빈 결과(R5 전제).

```java
switch (mode) {
    case MODE_ALL -> {
        Set<String> captured = connectionGate.ensureActiveTargetsConnected(eqpIds, timeoutMs); // :80
        runRegistry.track(response.runId(), captured);                                          // 기록(빈 집합도)
        connectionGate.awaitPassiveTargetsConnected(eqpIds, timeoutMs);
        client.activateUpstream(response.runId());
    }
    case MODE_EQUIPMENT_ONLY -> {
        Set<String> captured = connectionGate.ensureActiveTargetsConnected(eqpIds, timeoutMs); // :87
        runRegistry.track(response.runId(), captured);
    }
    case MODE_UPSTREAM_ONLY ->
        connectionGate.startActiveTargetsBestEffort(eqpIds, timeoutMs); // 미추적(C6)
    default -> throw new UiBadRequestException("지원하지 않는 mode입니다: " + mode);
}
```

UPSTREAM_ONLY 는 track 호출 없음 → AC11.

### (3) stopRun / rollbackRun 전환 + 누수 경로 그림

**stopRun** — 원자 claim 으로 추적 여부 판별. 추적이면 captured-set, 미추적이면 현행 폴백(byte-for-byte 보존: getRun→stop→release 전체목록).

```java
public SimulatorRunResponse stopRun(String runId) {
    long timeoutMs = dualRequestProperties.getDualRequestTimeoutMs();
    Optional<Set<String>> captured = runRegistry.claim(runId);   // 원자 claim 먼저
    if (captured.isPresent()) {                                  // 추적 run(ALL·EQUIPMENT_ONLY)
        SimulatorRunResponse response = client.stopRun(runId);
        connectionGate.releaseActiveTargets(List.copyOf(captured.get()), timeoutMs);
        return response;
    }
    // 미추적 run(UPSTREAM_ONLY 등) — R5-주의: 현행 현재상태 재해석 동작 보존
    List<String> eqpIds = client.getRun(runId).targets().stream()
            .map(SimulatorRunResponse.SimulatorTargetResponse::eqpId).toList();
    SimulatorRunResponse response = client.stopRun(runId);
    connectionGate.releaseActiveTargets(eqpIds, timeoutMs);
    return response;
}
```

**rollbackRun** — claim 후 set 받은 경우에만 release(captured). claim 빈 결과면 END 0건.

```java
private void rollbackRun(String runId, long timeoutMs, RuntimeException cause) {
    try { client.stopRun(runId); } catch (RuntimeException e) { cause.addSuppressed(e); }
    runRegistry.claim(runId).ifPresent(captured -> {
        try { connectionGate.releaseActiveTargets(List.copyOf(captured), timeoutMs); }
        catch (RuntimeException e) { cause.addSuppressed(e); }
    });
}
```
> `eqpIds` 파라미터는 rollback 에서 더 쓰지 않으므로 시그니처에서 제거(미사용 인자 정리, rule 4). startRun catch 의 호출부도 갱신.

**누수/이중-END 경로 폐쇄 그림** (catch = switch 전체 :78-92 를 감쌈):

```
startRun
 └─ createRun  (try 밖 — 실패해도 rollback 안 탐, run 생성 자체 실패라 정리할 연결 없음)
 └─ try {
      ALL:
        ① ensureActive ──throw──┐         track 미실행 → 레지스트리 무엔트리
                                 │
        ② track(captured)        │  (성공 경로: captured 기록)
        ③ awaitPassive ──throw──┤
        ④ activateUpstream ─throw┤
      EQUIPMENT_ONLY:            │
        ① ensureActive ──throw──┤         track 미실행
        ② track(captured)        │
    } catch → rollbackRun(runId) │
                                 ▼
              claim(runId):
                ├─ ①에서 throw  → empty  → release 0건
                │                          (gate 내부 releaseIssued 가 이미 보상 / 직후 stopRun→CANCELED,
                │                           reaper 는 CANCELED 제외 C2 → 이중/누수 모두 없음)
                └─ ③/④에서 throw → captured set → release(captured) 1회 + 엔트리 제거
                                            (이 run 이 켠 ACTIVE 만 END → 누수 0, 사전연결 미손상)
```
→ 모든 throw 분기에서 **누수 0 / 이중 END 0** 가 닫힌다(AC7 = ③ awaitPassive throw 케이스).

### (4) client.getRun 404 보강 (C3)

[HttpSimulatorOrchestratorClient.java](../../ui/adapter/tc-ui-web-adapter/src/main/java/com/nori/tc/ui/adapters/web/simulator/HttpSimulatorOrchestratorClient.java) `validateResponseStatus`(:101) 에 **409 분기 뒤, 일반 4xx(:109) 앞** 단일 404 분기 삽입:

```java
if (statusCode >= 500) throw new IllegalStateException(...);     // 기존
if (statusCode == 409) throw new UiConflictException(...);       // 기존
if (statusCode == 404) throw new SimulatorRunNotFoundException(  // 신규 (단일 노드)
        "simulator orchestrator Run 미존재. method=" + method + ", path=" + path);
if (statusCode >= 400) throw new UiBadRequestException(...);     // 기존 (기타 4xx)
```

**`SimulatorRunNotFoundException` 위치·상속 결정 (권장):**
- 위치: web-adapter `…adapters.web.simulator` (thrower·catcher 와 co-located, simulator 클라이언트 계열).
- 상속: **`extends UiNotFoundException`**(core). 근거 — (a) reaper 가 distinct 타입으로 catch 가능, (b) controller 경로(`facade.getRun` :117 폴링, stopRun 미추적 폴백 getRun)로 전파돼도 [UiApiExceptionHandler:104](../../ui/adapter/tc-ui-web-adapter/src/main/java/com/nori/tc/ui/adapters/web/controller/support/UiApiExceptionHandler.java) 가 **404** 로 매핑(미분류 RuntimeException → 500 회귀 회피), (c) `UiBadRequestException` 과 형제라 reaper catch 의 subclass-순서 함정 없음.
- **행위 변화(저위험·더 정확):** getRun 이 404 받을 때 기존 400(UiBadRequestException) → 404. 폴링/미추적-stop 경로에 한정. 워크스페이스 grep 상 getRun 404→400 단언 테스트 부재(DemoControllerTest 는 레거시 demo-tester 사용). 위험 §4 에 기재.
- `getRun` 정상(200) 반환 계약·5xx/409/기타4xx/IO 매핑은 **보존**.

### (5) reaper 빈

```java
// web-adapter / com.nori.tc.ui.adapters.web.simulator
public final class DemoRunConnectionReaper {
    private final SimulatorOrchestratorClient client;       // web-adapter
    private final DemoRunConnectionGate connectionGate;     // core
    private final DemoRunConnectionRegistry runRegistry;    // web-adapter
    private final UiDualRequestProperties dualRequestProperties; // web-adapter (EQP_END timeout)

    @Scheduled(fixedDelayString = "${tc.ui.simulator.reaper-interval-ms:5000}")
    public void reapTerminatedRuns() {
        for (String runId : runRegistry.trackedRunIds()) {
            try {
                SimulatorRunResponse run = client.getRun(runId);   // timeout=readTimeoutMs(기존)
                if (isTerminal(run.state())) {                     // C2: TERMINAL or FAILED
                    releaseAndUntrack(runId);
                }
                // 그 외 상태(RUNNING 등) → 추적 유지
            } catch (SimulatorRunNotFoundException notFound) {      // C3: 404 → 종료 간주
                releaseAndUntrack(runId);
            } catch (IllegalStateException serverError) {           // C3: 5xx → 일시오류, 유지·재시도
                log.warn("reaper getRun 5xx — 추적 유지. runId={}", runId, serverError);
            } catch (UiBadRequestException ioOrOther4xx) {          // C3: IO/타임아웃/기타4xx → 유지·재시도
                log.warn("reaper getRun 일시오류 — 추적 유지. runId={}", runId, ioOrOther4xx);
            }
        }
    }

    private static boolean isTerminal(String state) {
        return "TERMINAL".equals(state) || "FAILED".equals(state);   // CANCELED 제외(C2)
    }

    private void releaseAndUntrack(String runId) {
        runRegistry.claim(runId).ifPresent(captured ->               // R4: claim 한 쪽만 END
            connectionGate.releaseActiveTargets(List.copyOf(captured),
                    dualRequestProperties.getDualRequestTimeoutMs()));
    }
}
```

- **predicate** 정확히 `{TERMINAL, FAILED}` (문자열). CANCELED 제외 = stop 과의 race 회피(C2).
- **catch 정밀화 결정 (권장):** getRun 이 던지는 타입은 §0 표대로 `SimulatorRunNotFoundException`(404)/`IllegalStateException`(5xx)/`UiBadRequestException`(IO·기타4xx)/`UiConflictException`(409) **뿐**. 409 는 getRun 경로에서 사실상 발생 불가하나 발생 시 전파되어 다음 tick 재시도(거짓원복 금지에 부합). **trailing `catch(RuntimeException)` catch-all 은 두지 않는다**(rule 7/11 dead fallback 금지; release 는 best-effort 라 throw 안 함, claim 도 throw 안 함). 예기치 못한 예외는 전파 → Spring scheduled executor 가 로깅 후 다음 tick 정상 재개(한 tick 의 잔여 runId 는 다음 tick 처리).
- **모듈 경계:** 의존 4개 모두 web-adapter 에서 접근 가능(gate 만 core, 단방향 의존 OK).
- **EQP_END timeout:** facade 와 동일하게 `dualRequestProperties.getDualRequestTimeoutMs()` 사용(일관성). getRun timeout 은 client 내부 `readTimeoutMs` 재사용(C4, 신규 프로퍼티 불요).

`UiSimulatorProperties` 에 `reaperIntervalMs`(기본 5000) 필드 추가 — `@Scheduled` 는 환경 프로퍼티 `tc.ui.simulator.reaper-interval-ms` 를 직접 읽지만, 필드는 (a) 설정 키의 명시적 문서화, (b) relaxed-binding 일관 기본값 제공. `${...:5000}` 기본과 필드 기본 5000 일치.

### (6) @Bean 배선 (R6)

[UiSimulatorConfiguration.java](../../ui/adapter/tc-ui-web-adapter/src/main/java/com/nori/tc/ui/adapters/web/config/UiSimulatorConfiguration.java) 에 @Bean 2개 추가 + facade @Bean 에 registry 인자 추가:

```java
@Bean
public DemoRunConnectionRegistry demoRunConnectionRegistry() {
    return new DemoRunConnectionRegistry();
}

@Bean
public DemoRunConnectionReaper demoRunConnectionReaper(
        final SimulatorOrchestratorClient simulatorOrchestratorClient,
        final DemoRunConnectionGate demoRunConnectionGate,
        final DemoRunConnectionRegistry demoRunConnectionRegistry,
        final UiDualRequestProperties dualRequestProperties) {
    return new DemoRunConnectionReaper(
            simulatorOrchestratorClient, demoRunConnectionGate,
            demoRunConnectionRegistry, dualRequestProperties);
}

@Bean
public LiveMapSimulatorRunFacade liveMapSimulatorRunFacade(
        /* 기존 인자… */, final DemoRunConnectionRegistry demoRunConnectionRegistry) {
    return new LiveMapSimulatorRunFacade(/* … */, demoRunConnectionRegistry);
}
```

- `UiSimulatorConfiguration` 는 이미 [UiWebAdapterModuleConfiguration:47](../../ui/adapter/tc-ui-web-adapter/src/main/java/com/nori/tc/ui/adapters/web/config/UiWebAdapterModuleConfiguration.java) 에서 @Import → **@Bean 추가만으로 등록 완료**. ComponentScan 미사용 컨벤션 준수(신규 @Import 라인 불요).
- `@Scheduled` 는 starter `@EnableScheduling`(컨텍스트 전역)로 활성. @Bean 인스턴스에도 적용됨.

### (7) 기존 테스트 영향 + 갱신 방향

| 테스트 | 영향 | 갱신 방향 |
|---|---|---|
| `DemoRunConnectionGateTest.*` (전부) | **무영향** | gate `releaseActiveTargets`/`ensureActiveTargetsConnected` 메서드 불변 → 그대로 통과. `releaseEndsOnlyActiveStartedTargets`(현재상태 재해석 가정)도 메서드가 안 바뀌니 유효. |
| `LiveMapSimulatorRunFacadeTest` (5개 전부) | **컴파일 영향** | 생성자에 `DemoRunConnectionRegistry` 인자 추가 → `setUp()` 에서 실제 `new DemoRunConnectionRegistry()` 주입. |
| └ `allModeCreatesRunThenConnects…InOrder` | 행위 동일 | `ensureActive` mock 이 Mockito 기본 빈 Set 반환 → track(empty). inOrder 단언 유지 통과. |
| └ `equipmentOnly…` / `upstreamOnly…` | 행위 동일 | 통과(UPSTREAM_ONLY 는 track 안 함). |
| └ `stopRunCapturesTargetsThenReleasesActiveConnections` | **유지 통과** | RUN-1 은 startRun 미경유 → 미추적 → 폴백(getRun→stop→release 전체목록). inOrder 동일. |
| └ `startRunRolledBackWhenConnectionGateFails` | **단언 갱신 필요** | `ensureActive` 가 throw → track 미실행 → rollback claim empty → release **미호출**. 기존 `verify(connectionGate).releaseActiveTargets(eq(List.of("PLC_MBT_001")),…)` → `verify(connectionGate, never()).releaseActiveTargets(any(), anyLong())` 로 변경. `verify(client).stopRun("RUN-1")`·`never().activateUpstream` 은 유지. |

> 형제 SPEC `sim-demo-eqp-connect-desired-state` 의 "현재상태 재해석" 가정은 **gate 메서드 레벨**에 살아있다(untracked 폴백이 사용). captured-set 전환은 **facade/reaper 호출 인자 레벨**에서만 일어나므로, gate 단위테스트는 깨지지 않는다.

### (8) AC ↔ 테스트 매핑 (테스트 작성은 Implement, 여기선 매핑만)

| AC | 검증 테스트(클래스 / 메서드) | 비고 |
|---|---|---|
| AC1 startRun(ALL) 캡처 기록 | `LiveMapSimulatorRunFacadeTest.startRunTracksCapturedSetForAll` (신규) | gate mock 이 비-empty 반환 → registry.claim 으로 동일 집합 확인. |
| AC2 reaper TERMINAL→release 1회 | `DemoRunConnectionReaperTest.reaperReleasesCapturedSetOnTerminal` (신규) | mock client TERMINAL + ArgumentCaptor 로 captured-set 인자 검증. |
| AC3 reaper FAILED→release+제거 | `DemoRunConnectionReaperTest.reaperReleasesAndUntracksOnFailed` (신규) | 후속 tick release 0회로 제거 확인. |
| AC4 reaper CANCELED→release 0 | `DemoRunConnectionReaperTest.reaperSkipsCanceled` (신규) | `never().releaseActiveTargets`. |
| AC5 사전 STARTED 어느 경로도 미포함 | `LiveMapSimulatorRunFacadeTest.startRunTracksCapturedSetForAll` + stop/reaper 인자 검증 | captured-set 이 사전 STARTED 미포함(gate 반환이 newly-started 만)을 facade 레벨로 확인. gate 레벨은 기존 `ensureStartsOnlyEndedActiveTargets` 가 보강. |
| AC6 stop(추적)→captured-set+제거+reaper no-op | `LiveMapSimulatorRunFacadeTest.stopRunReleasesCapturedSetAndUntracks` (신규) | track→stop→captured 인자 release + claim 소진 후 reaper tick 0회. |
| AC7 ALL ensure성공+awaitPassive throw→rollback captured 1회+제거 | `LiveMapSimulatorRunFacadeTest.rollbackReleasesCapturedSetWhenPassiveFails` (신규) | awaitPassive mock throw. |
| AC8 reaper 404(SimulatorRunNotFoundException)→release+제거 | `DemoRunConnectionReaperTest.reaperTreats404AsTerminated` (신규) | + 지지: `HttpSimulatorOrchestratorClientTest.getRunThrowsRunNotFoundOn404`. |
| AC9 reaper 5xx(IllegalStateException)→유지 | `DemoRunConnectionReaperTest.reaperKeepsTrackingOnServerError` (신규) | `never().releaseActiveTargets` + 잔존. |
| AC10 reaper IO(UiBadRequestException 404아님)→유지 | `DemoRunConnectionReaperTest.reaperKeepsTrackingOnIoError` (신규) | 동상. |
| AC11 UPSTREAM_ONLY 미추적 | `LiveMapSimulatorRunFacadeTest.upstreamOnlyDoesNotTrack` (신규) | claim empty 확인. |
| AC12 web-adapter build PASS | `./gradlew :ui:adapter:tc-ui-web-adapter:build` | 회귀 차단(전 테스트 포함). |
| AC13 컨텍스트 기동+빈 등록 | `UiSimulatorConfigurationContextTest` (신규, ApplicationContextRunner) | registry/reaper/facade 빈 present + 컨텍스트 로드. |
| AC14 실환경 e2e | (수동/실서버) | `[~]` 표기 — gateway 로그 EQP_END 1회 + reconnect ERROR 미발생. |

---

## 3. 단계별 구현 순서 (Implement 세션용)

각 단계 = 실패 테스트 먼저 → 구현 → 통과.

1. **`SimulatorRunNotFoundException`(신규) + client 404 분기** → `HttpSimulatorOrchestratorClientTest` 로 404→예외 / 5xx·409·기타4xx 보존 검증. (AC8 지지)
2. **`DemoRunConnectionRegistry`(신규)** → 단위 테스트로 track/claim 원자성(연속 claim 2회 중 1회만 set) / trackedRunIds 스냅샷.
3. **`UiSimulatorProperties.reaperIntervalMs`** 추가.
4. **facade 수정** (생성자 registry, startRun 캡처 기록, stop/rollback claim+captured) → `LiveMapSimulatorRunFacadeTest` 갱신/추가 (AC1/5/6/7/11) + 기존 5개 시그니처/단언 정리.
5. **`DemoRunConnectionReaper`(신규)** → `DemoRunConnectionReaperTest` (AC2/3/4/8/9/10).
6. **`UiSimulatorConfiguration` @Bean 배선** → `UiSimulatorConfigurationContextTest` (AC13).
7. **회귀** → `./gradlew :ui:adapter:tc-ui-web-adapter:build` (AC12). 출력 마지막 5~10줄 인용.
8. Dev report 작성: 변경 파일 / 테스트 결과(PASS/FAIL + 출력 인용) / 결정 기록 → `demo-run-connection-restore-dev-report.md`.

---

## 4. 위험 (Risk)

| # | 위험 | 영향 | 완화 |
|---|---|---|---|
| R-a | `SimulatorRunNotFoundException extends UiNotFoundException` 로 getRun 404 가 400→404 로 바뀜 | 폴링/미추적-stop 경로 status 변화 | grep 상 404→400 단언 테스트 부재. 404 가 의미상 더 정확. Validate 에서 e2e 폴링 정상 확인. |
| R-b | reaper 스레드와 stop 요청의 동시 claim race | 이중 END | `ConcurrentHashMap.remove` 원자 claim — 한쪽만 set 수령(R4). CANCELED 제외로 stop 경로와 reaper predicate 자체가 비중첩(C2). |
| R-c | `@Scheduled` 가 @Bean 인스턴스에서 미동작 우려 | reaper 안 돎 | `@EnableScheduling` 전역 + `ScheduledAnnotationBeanPostProcessor` 는 등록 방식 무관 처리. AC13 컨텍스트 로드 + 실환경 AC14 로 확인. |
| R-d | abandon UNTIL_STOPPED(EQUIPMENT_ONLY) run 의 설비 STARTED 잔존 | 잔여 누수 | **SPEC Out of Scope 수용**(heartbeat 로 stale 안 됨, TTL 강제정리 미도입). 본 Plan 비대상. |
| R-e | UPSTREAM_ONLY stop 폴백이 getRun 404 시 SimulatorRunNotFoundException 전파 | stop 응답 404 | 폴백은 미추적 경로 한정·실발생 드묾. 404 매핑으로 500 회피(R-a 와 동일 완화). |
| R-f | 미사용 import/필드(예전 rollback `eqpIds` 인자) 잔존 | 컴파일 경고/AI Debt | rollback 시그니처에서 `eqpIds` 제거, 호출부 갱신(rule 4). |

---

## 5. 롤백 방법

- 변경은 **ui-backend web-adapter 단일 모듈**에 격리(gateway/orchestrator/core/db 무수정 — C1). core 의 `DemoRunConnectionGate` 도 무수정.
- 되돌리려면 7개 파일(신규 3 삭제 + 수정 4 revert)만 원복하면 됨. 다운스트림 레포 영향 0(REST 계약·DB 스키마·Kafka 토픽 무변경).
- 런타임 안전판: reaper 가 오작동해도 `releaseActiveTargets` 는 best-effort(throw 안 함)이며 captured-set 한정이라 사전연결 설비를 끊지 않음. 최악의 경우 토글 `tc.ui.simulator.enabled=false` 로 신규 Run 경로 전체를 503 폴백(기존 demo-tester 경로)으로 우회 가능.

---

## 6. Out of Scope (SPEC 승계)

gateway/orchestrator 코드 변경 / PASSIVE 정책 / UPSTREAM_ONLY 종료 정리 / abandon UNTIL_STOPPED 강제정리(TTL) / 공유 MES 토픽 중복응답.

---

## 다음 단계 진입 프롬프트 (Implement)

```
@nori-tc-ui-backend/.claude/specs/demo-run-connection-restore.md
@nori-tc-ui-backend/.claude/specs/demo-run-connection-restore-plan.md 둘 다 읽고 구현.
AC1~AC13 각각 실패 테스트 먼저(AC14 는 실환경 수동/e2e). Plan §3 순서 + §2 결정 엄수.
gateway/orchestrator·core gate 무수정, captured-set 통일(stop·rollback·reaper),
SimulatorRunNotFoundException extends UiNotFoundException, reaper catch 정밀(catch-all 금지).
산출물: nori-tc-ui-backend/.claude/specs/demo-run-connection-restore-dev-report.md.
```

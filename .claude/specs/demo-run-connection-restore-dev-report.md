# DEV REPORT: 데모 run 연결 상태 원복 (ALL 자연완료 시 EQP_END 누락 수정)

> SDD feature slug: `demo-run-connection-restore`
> 입력: [demo-run-connection-restore.md](demo-run-connection-restore.md) + [demo-run-connection-restore-plan.md](demo-run-connection-restore-plan.md)
> 구현: 2026-06-18 / Implement 세션. 모든 변경은 ui-backend `tc-ui-web-adapter` 단일 모듈에 격리.

---

## 1. 변경 파일 목록

### 신규 (소스 3)
| 파일 | 역할 |
|---|---|
| `ui/adapter/tc-ui-web-adapter/.../simulator/DemoRunConnectionRegistry.java` | `runId → Set<eqpId>` 추적. `track` / 원자적 `claim`(remove-and-return) / `trackedRunIds` 스냅샷. final class. |
| `ui/adapter/tc-ui-web-adapter/.../simulator/DemoRunConnectionReaper.java` | `@Scheduled` 폴링. getRun→predicate{TERMINAL,FAILED}/404→release+claim, 5xx·IO→유지. catch-all 없음. final class. |
| `ui/adapter/tc-ui-web-adapter/.../simulator/SimulatorRunNotFoundException.java` | 404 전용 신호. `extends UiNotFoundException`(→404 매핑·distinct catch). |

### 수정 (소스 4)
| 파일 | 변경 |
|---|---|
| `.../simulator/HttpSimulatorOrchestratorClient.java` | `validateResponseStatus` 분기 순서 **500 → 409 → 404(신규) → 4xx**. 404 → `SimulatorRunNotFoundException`. 나머지 매핑·정상반환 계약 보존. |
| `.../config/UiSimulatorProperties.java` | `reaperIntervalMs`(기본 5000) 필드 + getter/setter(기존 스타일 답습). |
| `.../simulator/LiveMapSimulatorRunFacade.java` | 생성자에 `DemoRunConnectionRegistry` 주입+`requireNonNull`. startRun: ALL/EQUIPMENT_ONLY 에서 `ensureActiveTargetsConnected` 반환을 캡처해 `track`(UPSTREAM_ONLY 미추적). rollbackRun: `eqpIds` 인자 제거 + 원자 claim→captured-set release. stopRun: 원자 claim 먼저 → 추적 run 은 captured-set, 미추적은 현행 폴백(getRun→stop→전체목록) 보존. |
| `.../config/UiSimulatorConfiguration.java` | `demoRunConnectionRegistry`·`demoRunConnectionReaper` @Bean 2개 추가 + `liveMapSimulatorRunFacade` @Bean 에 registry 인자 추가. (이미 `UiWebAdapterModuleConfiguration` 에서 @Import 되어 신규 @Import 라인 불요 — R6.) |

### 테스트 (신규 3 + 수정 2)
| 파일 | 변경 |
|---|---|
| `src/test/.../simulator/DemoRunConnectionRegistryTest.java` (신규) | track/원자 claim(2회 중 1회만 set)/스냅샷/빈 집합 키 기록. 5 tests. |
| `src/test/.../simulator/DemoRunConnectionReaperTest.java` (신규) | AC2/3/4/8/9/10. 6 tests. |
| `src/test/.../config/UiSimulatorConfigurationContextTest.java` (신규) | AC13. ApplicationContextRunner 슬라이스. 1 test. |
| `src/test/.../simulator/LiveMapSimulatorRunFacadeTest.java` (수정) | 생성자 시그니처 + AC1/5/6/7/11 신규 + 기존 rolled-back 단언 갱신. 9 tests. |
| `src/test/.../simulator/HttpSimulatorOrchestratorClientTest.java` (수정) | 404→`SimulatorRunNotFoundException` / 500→IllegalStateException / 409→UiConflictException / 기타4xx→UiBadRequestException. 6 tests(2 기존 + 4 신규). |

`DemoRunConnectionGateTest.java`(core) **무변경** — gate 메서드 불변(현재상태 재해석은 미추적 폴백이 사용). 11 tests GREEN 재확인.

---

## 2. AC ↔ 테스트 매핑 (전부 PASS)

| AC | 테스트(클래스.메서드) | 결과 |
|---|---|---|
| AC1 startRun(ALL) 캡처 기록 | `LiveMapSimulatorRunFacadeTest.startRunTracksCapturedSetForAll` | PASS |
| AC2 reaper TERMINAL→release 1회(captured 인자) | `DemoRunConnectionReaperTest.reaperReleasesCapturedSetOnTerminal` (ArgumentCaptor 검증) | PASS |
| AC3 reaper FAILED→release+제거 | `DemoRunConnectionReaperTest.reaperReleasesAndUntracksOnFailed` (2nd tick 0회) | PASS |
| AC4 reaper CANCELED→release 0 | `DemoRunConnectionReaperTest.reaperSkipsCanceled` | PASS |
| AC5 사전 STARTED 어느 경로도 미포함 | `…startRunTracksCapturedSetForAll`(captured 미포함) + `…stopRunReleasesCapturedSetAndUntracks`(release 인자 미포함) + gate `DemoRunConnectionGateTest.ensureStartsOnlyEndedActiveTargets` | PASS |
| AC6 stop(추적)→captured-set+제거+reaper no-op | `LiveMapSimulatorRunFacadeTest.stopRunReleasesCapturedSetAndUntracks` (이후 reaper tick release 0) | PASS |
| AC7 ALL ensure성공+awaitPassive throw→rollback captured 1회+제거 | `LiveMapSimulatorRunFacadeTest.rollbackReleasesCapturedSetWhenPassiveFails` | PASS |
| AC8 reaper 404→release+제거 | `DemoRunConnectionReaperTest.reaperTreats404AsTerminated` + `HttpSimulatorOrchestratorClientTest.getRunThrowsRunNotFoundOn404` | PASS |
| AC9 reaper 5xx(IllegalStateException)→유지 | `DemoRunConnectionReaperTest.reaperKeepsTrackingOnServerError` | PASS |
| AC10 reaper IO(UiBadRequestException, 404아님)→유지 | `DemoRunConnectionReaperTest.reaperKeepsTrackingOnIoError` | PASS |
| AC11 UPSTREAM_ONLY 미추적 | `LiveMapSimulatorRunFacadeTest.upstreamOnlyDoesNotTrack` | PASS |
| AC12 web-adapter build PASS | `./gradlew :ui:adapter:tc-ui-web-adapter:build` | PASS (아래 인용) |
| AC13 컨텍스트 기동+빈 등록 | `UiSimulatorConfigurationContextTest.registersRegistryReaperAndFacadeBeans` | PASS |
| AC14 실환경 e2e (gateway EQP_END 1회 + reconnect ERROR 미발생) | 실서버/수동 | **[~] 이 세션 비대상** — 단위 레벨 검증 완료, 실 gateway 로그 관측은 Validate/실환경 게이트 |

---

## 3. 빌드 출력 인용 (AC12)

`./gradlew :ui:adapter:tc-ui-web-adapter:build -PuseLocalMaven` (common 아티팩트는 mavenLocal 소비 — 레포 컨벤션):

```
> Task :ui:adapter:tc-ui-web-adapter:test
> Task :ui:adapter:tc-ui-web-adapter:check
> Task :ui:adapter:tc-ui-web-adapter:build

BUILD SUCCESSFUL in 36s
14 actionable tasks: 4 executed, 10 up-to-date
```

신규/영향 테스트 클래스 JUnit XML 집계(전부 0 failures/0 errors/0 skipped):

```
DemoRunConnectionReaperTest          tests=6 skipped=0 failures=0 errors=0
DemoRunConnectionRegistryTest        tests=5 skipped=0 failures=0 errors=0
LiveMapSimulatorRunFacadeTest        tests=9 skipped=0 failures=0 errors=0
HttpSimulatorOrchestratorClientTest  tests=6 skipped=0 failures=0 errors=0
UiSimulatorConfigurationContextTest  tests=1 skipped=0 failures=0 errors=0
DemoRunConnectionGateTest (core)     tests=11 skipped=0 failures=0 errors=0
```

---

## 4. 내린 결정·근거

- **gate 메서드 무수정 (Plan §0 핵심 발견 준수).** `releaseActiveTargets`/`ensureActiveTargetsConnected` 는 그대로 두고,
  "captured-set vs 전체목록"을 **facade/reaper 가 넘기는 인자**로만 전환. 덕분에 `DemoRunConnectionGateTest`(현재상태
  재해석 가정 포함) 11개가 변경 없이 GREEN. C1(gateway/orchestrator) + core gate 무수정 동시 충족.
- **stopRun 분기 판별자 = 레지스트리 엔트리 유무.** 추적 run(ALL·EQUIPMENT_ONLY)은 원자 claim→captured-set END,
  미추적(UPSTREAM_ONLY 등)은 현행 폴백(getRun→stop→전체목록)을 **byte-for-byte 보존**(R5-주의, no-op 로 바꾸지 않음).
  → 기존 `stopRunCapturesTargetsThenReleasesActiveConnections`(RUN-1 미추적) 그대로 통과.
- **rollback `eqpIds` 인자 제거.** captured-set claim 으로 좁히면 인자 불필요 → 시그니처에서 제거 + startRun catch 호출부
  갱신(`rollbackRun(runId, timeoutMs, cause)`). 미사용 import/필드 없음(빌드 check 통과로 확인).
- **reaper catch 정밀화(catch-all 금지).** `SimulatorRunNotFoundException`(404)→종료, `IllegalStateException`(5xx)·
  `UiBadRequestException`(IO/기타4xx)→유지. trailing `catch(RuntimeException)` 미도입(dead fallback 금지). predicate 는
  정확히 `{TERMINAL, FAILED}` 문자열, **CANCELED 제외**(stop 전담 — race 회피, C2).
- **R4 이중 END 방지 = `ConcurrentHashMap.remove` 원자 claim.** stop·rollback·reaper 세 발행처 모두 동일하게 claim 한
  쪽만 END. `DemoRunConnectionRegistryTest.claimIsAtomicSoSecondClaimGetsEmpty` 로 자료구조 레벨 보장 검증.
- **SimulatorRunNotFoundException extends UiNotFoundException.** 폴링/미추적-stop 경로로 전파돼도 `UiApiExceptionHandler`
  가 404 로 매핑(미분류 RuntimeException→500 회귀 회피). 404 분기 순서는 500→409→404→4xx.
- **AC13 슬라이스 테스트는 ApplicationContextRunner.** stub 빈(ObjectMapper, EqpManagementService mock,
  DemoRunConnectionGate mock, UiDualRequestProperties) + `withUserConfiguration(UiSimulatorConfiguration.class)` 로
  registry/reaper/facade 빈 present + `hasNotFailed()` 확인. `@Scheduled` 는 슬라이스에 `@EnableScheduling` 부재라
  미발화하나 빈 등록·컨텍스트 로드 검증에는 무관.

---

## 5. 완료 게이트 점검 (work order §5)

- [x] 신규 3 + 수정 4 파일 전부 반영
- [x] AC1~AC13 각각 대응 테스트 존재 + 통과(§2 표)
- [x] DemoRunConnectionGate / gateway / orchestrator 무수정 확인(git status: gateway clean, simulator clean, core gate·gate test 미변경)
- [x] reaper catch-all 없음 / predicate={TERMINAL,FAILED} / CANCELED 제외
- [x] SimulatorRunNotFoundException extends UiNotFoundException + 404 분기 순서(500→409→404→4xx)
- [x] stopRun 미추적 폴백 순서·동작 현행 보존 / 추적 captured-set 사용
- [x] rollback eqpIds 인자 제거 + 호출부 갱신, 미사용 import 없음
- [x] `./gradlew :ui:adapter:tc-ui-web-adapter:build` PASS + 출력 인용(§3)
- [~] AC14 실환경 e2e — 이 세션 비대상(실 gateway 로그 관측 필요)

---

## 6. 다음 단계 진입 프롬프트 (Validate)

```
@nori-tc-ui-backend/.claude/specs/demo-run-connection-restore.md
@nori-tc-ui-backend/.claude/specs/demo-run-connection-restore-plan.md
@nori-tc-ui-backend/.claude/specs/demo-run-connection-restore-dev-report.md
셋 다 읽고 AC1~AC14 + Plan 결정 충족 검증. 통과 항목만 [x] 로 업데이트.
AC14 는 실환경 게이트 시 [~] 유지.
```

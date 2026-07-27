# 리팩토링 캠페인 Phase 1 감사 — nori-tc-ui-backend (S08)

> 캠페인 SPEC: `c:\workspace_nori\nori-tc-common\.claude\specs\nori-tc-refactor-campaign.md`
> 방법론 v5: `...\refactor-campaign-prompts.md` / Plan: `...\nori-tc-refactor-campaign-plan.md`
> hotspot 입력: `...\refactor-hotspots.md` §1 nori-tc-ui-backend
> **읽기 전용 세션** — 코드 수정 0. 산출물은 본 md 1개. 롤백 = 본 파일 삭제.

---

## §0 분석 기준

### 0.1 대상 · HEAD

| 항목 | 값 |
|---|---|
| 레포 | `c:\workspace_nori\nori-tc-ui-backend` |
| HEAD SHA | `584ba0ff58cc03b1cb2d51dc8d3a4f86541b4be0` |
| HEAD 커밋 | `584ba0f build(jacoco): P0-1b JaCoCo 측정 convention 적용 (S02)` |
| git status | clean (`git status --porcelain` 출력 0줄) |
| hotspot 기준 SHA | `584ba0ff...` (`refactor-hotspots.md` §0) — **현재 HEAD 와 일치**. 재현 기준 편차 없음 |
| 감사일 | 2026-07-27 |

### 0.2 규모 (source 만 집계 — generated 분리)

> S05 에서 743 이 source+generated 합산 오류로 지적되었으므로, 본 감사는 `src/main/java` 만 집계하고
> `build/generated` 는 별도 계상한다.

| 모듈 | main `.java` | test `.java` | main LOC |
|---|---|---|---|
| `ui/tc-ui-domain` | 56 | 0 | 1,404 |
| `ui/tc-ui-core` | 188 | 23 | 17,214 |
| `ui/adapter/tc-ui-db-adapter` | 49 | 21 | 12,106 |
| `ui/adapter/tc-ui-kafka-adapter` | 10 | 3 | 1,360 |
| `ui/adapter/tc-ui-redis-adapter` | 21 | 4 | 2,800 |
| `ui/adapter/tc-ui-web-adapter` | 145 | 37 | 12,118 |
| `ui/starter/tc-ui-backend-starter` | 3 | 3 | 564 |
| **합계 (source)** | **472** | **91** | **47,566** |
| `build/generated` (별도) | **0** | — | — |

측정 명령:
```
find . -path ./build -prune -o -name "*.java" -path "*/src/main/java/*" -print | wc -l   → 472
find . -name "*.java" -path "*/src/test/java/*" | wc -l                                  → 91
find . -path "*/build/generated/*" -name "*.java" | wc -l                                → 0
```

- **generated 0건**: 본 레포는 MapStruct 등 애노테이션 프로세서 산출 소스가 없다(clean 상태 기준).
- gradle 모듈 7개 (`settings.gradle.kts` 실측).

### 0.3 `*ModuleConfiguration` 실개수 — 6 (S05 기록과 일치, 직접 find 재확인)

```
./ui/adapter/tc-ui-db-adapter/.../db/UiDbAdapterModuleConfiguration.java
./ui/adapter/tc-ui-kafka-adapter/.../kafka/config/UiKafkaAdapterModuleConfiguration.java
./ui/adapter/tc-ui-redis-adapter/.../redis/config/UiRedisAdapterModuleConfiguration.java
./ui/adapter/tc-ui-web-adapter/.../web/config/UiWebAdapterModuleConfiguration.java
./ui/starter/tc-ui-backend-starter/.../starter/UiCoreModuleConfiguration.java
./ui/tc-ui-core/.../core/service/UiCoreServiceModuleConfiguration.java
```
AutoConfiguration 진입점 1개: `ui/starter/.../META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
= `com.nori.tc.ui.starter.TcUiBackendAutoConfiguration`

### 0.4 hotspot 깊이 배분 (Deploy 클러스터 deep)

`refactor-hotspots.md` §1/§2/§3 — ui-backend 는 deep 배분 2순위, Deploy* 클러스터가 churn·LOC·co-change 3축 최상위.

| 파일 | churn | LOC(hotspot 기록) | LOC(본 감사 실측) | *Test | 본 감사 처리 |
|---|---|---|---|---|---|
| `DeployServiceCommandSupport` | 17 | 1114 | 1114 | **없음** | **메서드 단위 deep** (D/B/C) |
| `DeployJdbcStorePort` | 8 | 1274 | 1274 | **없음** | **메서드 단위 deep** (D/B/C) |
| `DeployPlanningService` | 11 | 1105 | 1105 | 있음 | 메서드 단위 deep (B/C) |
| `JpaEqpCrudPort` | 10 | 967 | 967 | 있음 | 메서드 단위 deep (D/B/C) |
| `DeployService` | 10 | 693 | 693 | 있음 | 메서드 단위 deep (D/C) |
| 그 외 467 파일 | — | — | — | — | 클래스/모듈 단위 wide |

**P0-3 characterization 후보(무테스트 고LOC)**: `DeployServiceCommandSupport`(1114), `DeployJdbcStorePort`(1274).
두 파일은 축 D 의 P2 8건 중 4건(D-2/D-14/D-16/D-17)의 소재지이므로 R 트랙 착수 전 안전망 필수.

### 0.5 방법

- 읽기 전용 서브에이전트 5개 병렬 탐색(엔드포인트 인벤토리 / 축 A / 축 D / 축 B·C / 축 F) + 메인 세션 축 E 직접 수행.
  서브에이전트는 Write 도구 미보유로 scratchpad 중간 파일을 생성하지 못했고, 결과를 반환값으로 회수해 본 문서에 통합했다
  (방법론의 "중간 파일 Write" 의도는 재개 가능성 확보이며, 본 세션은 결과 회수에 성공했으므로 감사 완결성에 영향 없음. §7 세션 메타에 기록).
- **채택한 P1/P2 전건을 메인 세션이 직접 file:line 으로 재검증**했다(§7 재검증 목록).
- **반증 우선**: 각 P2 후보에 대해 "이 실패 시나리오가 성립하지 않는 이유"를 먼저 탐색하고, 반증에 실패한 것만 P2 로 채택했다.
  반증에 성공해 강등/기각한 항목은 §0.6 에 전부 남겼다.
- Java 정적 분석 도구(SpotBugs/PMD/checkstyle/sonar)는 워크스페이스 미도입(방법론 실측) — 수동 카탈로그 + grep 이 1차 수단.
- 외부 도구: OWASP dependency-check (Gradle `dependencyCheckAggregate`, `SecurityScanConventionsPlugin` 경유). 버전은 §5.7.

### 0.6 심각도 조정 노트 (서브에이전트 라벨 → 본 감사 확정 라벨)

캠페인 척도: **P1 = 장애·보안 즉시(무선행조건) / P2 = 잠재(선행조건·저빈도 경합) / P3 = 품질**.
서브에이전트 라벨을 그대로 쓰지 않고 아래와 같이 재조정했다.

| 항목 | 서브에이전트 | 본 감사 | 조정 근거 (직접 확인) |
|---|---|---|---|
| C-5 `restoreProtocolDetails` SECS 분기의 `eqpPlcStore.deleteByEqpKey` 누락 | P2 "실드리프트" | **P3** | **반증 성립.** `commInterface` 는 생성 후 불변이다 — `JpaEqpCrudPort.java:220` 이 `existing.eqp().commInterface()` 를 넘기고 `EqpManagementCommand.Update`(`:163`) 에 `commInterface` 필드가 없다. SECS 설비에 PLC 행이 존재할 수 없으므로 누락 delete 는 no-op. 방어 코드 비대칭(품질)로 강등 |
| B-2 actor fallback 기본값 드리프트(`"ANONYMOUS"` vs `"SYSTEM"`) | P2 | **P3** | **반증 성립.** 드리프트는 실재하나(`EqpParamController.java:252,260` = `ANONYMOUS`, `DeployController.java:59`/`EqpControllerCommandSupport.java:70,78` = `SYSTEM`) 해당 분기는 `authentication == null` 또는 principal 타입 불일치일 때만 도달한다. `UiSecurityConfig.java:141` 의 `anyRequest().access(...)` 가 `UserPrincipal` 이 아닌 요청을 전부 거부(`:267-273`)하므로 게이팅된 엔드포인트에서 도달 불가 |
| B-5 예외 변환 `isConflict` 누락 → HTTP status 드리프트 | P2 | **P3** | **부분 오탐.** 지목된 `JpaModelFlowCommandPort.java:87-91` 은 실제로 `isConflict` 를 처리한다(직접 확인). `JpaLineQueryPort.java:37-41` 은 SELECT 전용이라 제약 위반 conflict 자체가 발생 불가. 남는 것은 50곳 블록 중복(품질) |
| B-8 JsonNode 접근자 계약 불일치 | P2 | **P3** | **약화.** 두 세트는 서로 다른 클래스의 `private static` 이고 arity 도 다르다(`DeployServiceCommandSupport.longValue(node, field)` 2-arg lenient vs `DeployPlanningService.longValue(parent, objectField, field)` 3-arg strict). 호출 지점에서 혼동될 구조가 아니다. 신규 코드가 잘못된 관용구를 복사할 위험(품질)만 남음 |
| `DELETE /api/live-map/demo-runs/scenarios/{id}` 권한 미시드 → 전 사용자 403 | (메인 세션 1차 가설) | **기각 — FALSE** | 권한 `LIVE_MAP_DEMO_RUN_SCENARIO_DELETE`(PREFIX `/api/live-map/demo-runs/scenarios`, DELETE)가 `nori-tc-exec\docs\db_table\patch\postgres_2026_06_22_add_live_map_demo_run_scenario_delete_permission.sql:30` 에 실재한다. 1차 grep 이 멀티라인 `VALUES` 를 놓친 계측 오류였다. **감사 자체의 오탐 기록으로 보존** |
| A-1 `UiCoreFallbackStorageConfiguration` 의 `@ConditionalOnMissingBean` 항상-참 | P2 | **P3** | **반증 성립.** 조건이 항상 참인 것과 죽은 in-memory 빈 3개 등록은 확인했으나(`TcUiBackendAutoConfiguration.java:62` 가 `:65` 보다 먼저 import), `@Primary`(`RedisEqpRepairTaskStore.java:24`, `DeployExecutionRedisAdapter.java:18`, `DeployTraceRedisAdapter.java:28`)가 주입을 정상 해소하므로 **현재 기능 실패 없음**. 잠재 함정으로 남김 → §1.2 A-1 |
| A-8 `buildRollbackTraceId` 파라미터 무시 | P2 | **P3** | **반증 성립.** traceId 는 `deployTraceRecorder.register(...)` 상관관계 키로만 쓰이며 결정성이 요구되지 않는다. 난수로도 기능 정상 → §1.2 A-8 |
| 축 D P2 10건 (D-1~D-10) | P2 | **P2 유지** | 전건 메인 세션이 인용 라인을 직접 열어 재현 조건을 확인함(§7). 어느 것도 "무선행조건"이 아니므로 P1 승격 없음 |
| 축 F "RedisBusinessDlqEntry JDK 역직렬화" (S07 E-2 전제) | — | **전제 오류 정정** | ui-backend 어댑터에 `JdkSerializationRedisSerializer` 는 0건. 실제는 JSON 4갈래. 대신 **직렬화 mode 불일치**라는 별개 결함 발견 → D-18 로 신규 등록. §6.3 cross-repo 정정 참조 |

**P1 판정**: 0건. 근거는 §5.1(전 108 엔드포인트 인가 경로 대조 결과 미게이팅 0건) — 선험적 배제가 아니라 전수 대조 결과다.

---

## §1 축 A — 죽은 코드 / 불필요 로직 + ModuleConfiguration @Import 정합성

### 1.1 `@Import` 정합성 — **0 결함**

`@RestController`/`@Service`/`@Component`/`@Repository` 스테레오타입 클래스 전수를 6개 `*ModuleConfiguration`
+ `TcUiBackendAutoConfiguration` 의 `@Import` 등록 집합(132 클래스)과 대조했다.

| 모듈 | 스테레오타입 컴포넌트 | 미등록 |
|---|---|---|
| `ui/tc-ui-core` | 36 | **0** |
| `ui/adapter/tc-ui-db-adapter` | 34 | **0** |
| `ui/adapter/tc-ui-kafka-adapter` | 3 | **0** |
| `ui/adapter/tc-ui-redis-adapter` | 12 | **0** |
| `ui/adapter/tc-ui-web-adapter` | 32 | **0** |
| `ui/tc-ui-domain` / `ui/starter/*` | 0 | — |
| **합계** | **117** | **0** |

> 집계 기준 주: 위 117 은 `@RestController`/`@Service`/`@Component`/`@Repository` 4종 기준이다.
> 독립 대조를 수행한 서브에이전트는 `@Configuration`/`@ControllerAdvice`/`@RestControllerAdvice` 를 포함해 **130** 으로 집계했고,
> 여기에 `@ConfigurationProperties` 10종(전부 `@EnableConfigurationProperties` 경유)과 `@AutoConfiguration` 5종을 별도 확인했다.
> **두 독립 경로 모두 미등록 0건**으로 수렴한다.

실행 명령(재현 가능):
```bash
cat $(find . -name "*ModuleConfiguration.java" -o -name "TcUiBackendAutoConfiguration.java" | grep -v build) \
  | grep -o "[A-Z][A-Za-z0-9]*\.class" | sed 's/\.class//' | sort -u > /tmp/imp_all.txt   # 132
grep -rl "^@\(RestController\|Service\|Component\|Repository\)" --include="*.java" <module>/src/main/java \
  | xargs -n1 basename | sed 's/\.java$//' | sort -u | comm -23 - /tmp/imp_all.txt          # 각 모듈 0줄
```
20개 컨트롤러 전부 + 보안 4종(`UiSecurityConfig`/`UiTokenAuthenticationFilter`/`UiApiPermissionCache`/`UiAuthenticationEntryPoint`)이
`UiWebAdapterModuleConfiguration.java:43-80` 에 명시 등록되어 있다.

> **캠페인 누적**: common(AutoConfig 정합 0) / gateway(11 config 0) / business(5 config 0) / ui-backend(6 config 0).
> 4개 레포 연속 0결함 — `@Import` 누락은 이 코드베이스의 실제 부채가 아니다(리스크 인식은 유지하되 백로그 항목 아님).

**등록 체인 (file:line)**
- `ui/starter/.../AutoConfiguration.imports:1` → `TcUiBackendAutoConfiguration` 1개
- `TcUiBackendAutoConfiguration.java:58-66` → `@EnableConfigurationProperties(UiDeployProperties.class)` +
  `@Import({UiCoreModuleConfiguration, UiWebAdapterAutoConfiguration, UiKafkaAdapterAutoConfiguration, UiRedisAdapterAutoConfiguration, UiDbAdapterAutoConfiguration})`
- 각 `*AdapterAutoConfiguration.java:14-15` → 대응 `*ModuleConfiguration` 1개씩
- `UiCoreModuleConfiguration.java:21-30`(8개) → `UiCoreServiceModuleConfiguration.java:10-41`(30개)
- `@ConfigurationProperties` 10종은 전부 `@EnableConfigurationProperties` 경유 등록 확인
  (`UiKafkaConfiguration.java:40`, `AsyncResultStoreService.java:43`, `UiRedisConfiguration.java:43`,
  `GatewayShardLeaseRedisAdapter.java:29`, `UiSessionCacheService.java:34`, `UiSimulatorConfiguration.java:29`,
  `EqpController.java:62`, `UiSecurityConfig.java:56`, `TcUiBackendAutoConfiguration.java:60`)
- 스테레오타입 애노테이션 없는 협력자는 `@Bean` 메서드 경유 등록 확인
  (`UiSimulatorConfiguration.java:33-88` — `LiveMapSimulatorRunFacade`/`DemoRunConnectionReaper`/`DemoRunConnectionRegistry`/
  `SimulatorModeMapper`/`SimulatorTargetAssembler`/`HttpSimulatorOrchestratorClient`,
  `UiSecurityPolicyConfiguration.java:19-31` — `UiPasswordPolicyService`/`PasswordEncoder`,
  `TcUiBackendAutoConfiguration.java:277` — `UiBackendRuntimeHealthIndicator`)

### 1.2 죽은 코드 / 불필요 로직 — 16건 (전부 P3)

> 서브에이전트가 P2 로 올린 A-1·A-8 두 건은 메인 세션 재검증에서 **기능 실패 시나리오가 성립하지 않아 P3 로 강등**했다(§0.6 연장 — 아래 각 항목에 근거 기재).
> 트랙은 전부 **R**, 요구 축은 A-1~A-8·A-13~A-16 = **R1/R2**, A-9~A-12 = **R1**, 심각도는 16건 전부 **P3**.
>
> **[검증 보정] 다운스트림 영향 (S09 세션 완결성 게이트에서 누락 필드 보완)** — 8필드 중 `다운스트림`이 원 표에 없어 추가한다.
> - **A-14** → **nori-tc-exec**: 분할된 `tc.ui.backend.async` prefix 의 실제 값이 exec config 에 있다
>   (`nori-tc-exec\apps\tc-ui-backend-app\config\tc-ui-backend.properties:62,66,69` — 직접 확인). 설정 surface 통합 시 exec 동기 필수.
> - **A-12** → **nori-tc-exec**: exec 의 `apps/tc-ui-backend-app/build.gradle.kts` 에 actuator 직접 선언이 없어(직접 확인) 전이 의존에 의존한다. 제거 시 부팅 검증 필요.
> - **A-10** → 없음(빌드 경고 재확인만) / **A-9·A-11** → 없음(모듈 내 gradle 선언).
> - **나머지 11건(A-1~A-8·A-13·A-15·A-16)** → **없음**. 전부 ui-backend 내부의 `private`/`package-private` 멤버 또는 javadoc 이며 타 레포 참조 0건
>   (§1.3 의 `c:\workspace_nori` 전 레포 grep 범위로 확인).

| ID | 위치 | 문제 | 확신도 | 난이도 |
|---|---|---|---|---|
| A-1 | `ui/tc-ui-core/.../service/UiCoreFallbackStorageConfiguration.java:17-33` | `@ConditionalOnMissingBean` 이 운영에서 항상 참 → 죽은 in-memory 빈 3개 등록 | MEDIUM-HIGH | M |
| A-2 | `ui/tc-ui-core/.../deploy/DeployTargetCounts.java:19-21` | `hasActiveTarget()` 전 워크스페이스 호출 0건 | HIGH | S |
| A-3 | `ui/tc-ui-domain/.../auth/UserPrincipal.java:66-76` | `hasAnyPermission(String...)` 호출 0건 | HIGH | S |
| A-4 | `ui/tc-ui-domain/.../auth/UserPrincipal.java:83-85` | `hasNoPermission()` 호출 0건 | HIGH | S |
| A-5 | `ui/tc-ui-core/.../registry/UiDualTaskFinalResult.java:41-43` | `hasPartialFailure()` 호출 0건 | HIGH | S |
| A-6 | `ui/tc-ui-core/.../model/AsyncResultEntry.java:58` | `AsyncStatus` 3상수를 전부 case 처리한 뒤의 `default -> throw` = 도달 불가 | HIGH | S |
| A-7 | `ui/tc-ui-core/.../service/EqpManagementService.java:288-299` | compact 생성자가 `COMPLETED ⇒ reply != null` 를 강제하는데 `reply == null` 가드 3개 + 도달 불가 `default -> throw` | HIGH | S |
| A-8 | `ui/tc-ui-core/.../service/EqpDirectScenarioRollbackService.java:218-220` | `buildRollbackTraceId(executionId, stepCode)` 가 두 파라미터를 전부 무시하고 난수 반환 | HIGH | S |
| A-9 | `ui/adapter/tc-ui-db-adapter/build.gradle.kts` | `compileOnly(libs.jakarta.annotation.api)` — 해당 모듈에 `jakarta.annotation` import 0건 | HIGH | S |
| A-10 | `ui/starter/tc-ui-backend-starter/build.gradle.kts` | `compileOnly(libs.jackson.annotations)` — starter main 에 import 0건. 단 같은 파일 주석이 "외부 class 파일 애노테이션 메타데이터 해석용"이라 설명 → 제거 전 빌드 경고 재확인 필요 | MEDIUM | S |
| A-11 | `ui/starter/tc-ui-backend-starter/build.gradle.kts:52-53` | `spring-boot-starter-actuator` + `spring-boot-actuator` 중복 선언(전자가 후자를 전이 포함) | MEDIUM-HIGH | S |
| A-12 | `ui/adapter/tc-ui-web-adapter/build.gradle.kts` | actuator 선언했으나 `org.springframework.boot.actuate`/`.health` import 0건(문자열 경로 매칭만). 단 exec 앱이 actuator 를 직접 선언하지 않아 전이 의존에 의존 → 제거 시 부팅 검증 필수 | MEDIUM | S |
| A-13 | `ui/adapter/tc-ui-db-adapter/.../config/UiDbAdapterAutoConfiguration.java:10-11` | stale javadoc — 존재하지 않는 패키지 `com.nori.tc.ui.adapters.db` 를 서술(`ls` 결과 부재) | HIGH | S |
| A-14 | `UiAsyncProperties.java:22` / `UiDualRequestProperties.java:26` / `AsyncResultStoreService.java:231` | 동일 prefix `tc.ui.backend.async` 를 서로 다른 모듈의 두 클래스가 분할 바인딩 + `${...timeout-scan-interval-ms:1000}` 은 어느 쪽에도 미바인딩 → 설정 surface 3분할 | HIGH | M |
| A-15 | `ui/tc-ui-core/.../service/DeployPlanningService.java:1098-1104` | `safeLong`/`safeInt` 가 인자를 그대로 반환하는 항등 함수(이름이 "safe" 를 약속하나 미이행) | HIGH | S |
| A-16 | `ui/tc-ui-core/.../service/DeployServiceCommandSupport.java:307-314` | `buildTraceId(deployKey, deployTargetKey, stepCode, attemptNo)` 가 파라미터 4개를 전부 무시 | HIGH | S |

**A-1 상세 · 강등 근거**
```java
@Configuration(proxyBeanMethods = false)
class UiCoreFallbackStorageConfiguration {
    @Bean @ConditionalOnMissingBean(EqpRepairTaskPort.class)
    EqpRepairTaskPort inMemoryEqpRepairTaskPort() { return new InMemoryEqpRepairTaskStore(); }
    ... (DeployExecutionCoordinationPort, DeployTraceRedisPort 동일 패턴)
}
```
이 클래스는 AutoConfiguration 이 아니라 일반 `@Configuration` 이고, `TcUiBackendAutoConfiguration.java:62` 에서
`UiCoreModuleConfiguration` 이 `UiRedisAdapterAutoConfiguration`(`:65`)보다 **먼저** import 된다(직접 확인).
`@ConditionalOnMissingBean` 은 정의 등록 시점 기준이라 Redis 어댑터 정의 등록 전에 평가되어 **운영에서 항상 참**이다.
`RedisEqpRepairTaskStore.java:24`, `DeployExecutionRedisAdapter.java:18`, `DeployTraceRedisAdapter.java:28` 에
`@Primary` 가 붙어 있는 것이 중복 빈이 실존한다는 증거다.
**강등 근거(반증 성립)**: `@Primary` 가 단일-포트 주입을 정상 해소하므로 **현재 기능 실패는 없다**.
남는 것은 ① 운영 컨텍스트의 죽은 빈 3개 ② `@Primary` 제거 같은 향후 리팩터링 시
`NoUniqueBeanDefinitionException` 으로 전환되는 잠재 함정. → **P3(제거 가치 높음)**.

**A-8 상세 · 강등 근거**
```java
private String buildRollbackTraceId(final String executionId, final String stepCode) {
    return TraceIdGenerator.newTraceId();
}
// 호출부 :114 — final String compensationTraceId = buildRollbackTraceId(context.executionId(), step.code());
```
**강등 근거(반증 성립)**: 이 traceId 는 `deployTraceRecorder.register(compensationTraceId, ...)` 상관관계 키로만 쓰이며
**결정성이 요구되지 않는다**. 난수로도 기능은 정상. 남는 것은 죽은 파라미터 + 메서드명이 결정적 생성을 암시하는 오해 유발.
→ **P3**. (다만 "보상 traceId 재현"이 요구사항이면 F 트랙 항목이 된다 — 요구사항 문서 **미확인**)

### 1.3 "죽은 코드 아님"으로 판정한 항목 (오판 방지 기록)

| 후보 | 판정 근거 |
|---|---|
| 미참조 top-level 클래스 | **0건**. main 클래스 472개 × 레포 전체 식별자 인덱스(40,183 토큰) 교차 대조 → 참조 0인 클래스 없음 |
| 미사용 private 필드/상수 | **0건**. 라인 경계 보정 후 재실행 |
| `promoteExpiredPendingToTimeout`(`AsyncResultStoreService.java:232`), `refreshPermissions`(`UiApiPermissionCache.java:88`) | `@Scheduled` 로 호출 |
| 컨트롤러 handler / `@Bean` / `@ExceptionHandler` / `@Override`(`doFilterInternal`, `commence`) 약 60개 | 프레임워크 호출 |
| `tc-common-logging` 의존성(kafka/web adapter) | 패키지가 `com.nori.tc.logging` — `UiCommandKafkaSubscriber.java:5`, `EqpController.java:3`, `UiTokenAuthenticationFilter.java:5` 에서 `TcLogContext` 사용 중 |
| `EqpDirectScenarioRollbackService.java:214` 의 `default -> throw` | `UiCommandEventType` 14개 중 11개만 case 처리(`EQP_PARAMETER_UPDATE`/`EQP_PLC_GROUP_START`/`EQP_PLC_GROUP_END` 누락) → **도달 가능** |
| `LiveMapObjectKind.java:26`, `SimulatorModeMapper.java:19`, `JpaModelDetailCommandPort.java:174/636/695` 의 `default -> throw` | String 입력 기반 switch → 도달 가능 |
| `Optional.<T>empty().orElse(null)` 3곳(`DeployServiceCommandSupport.java:638,642,836`) | 불필요 우회지만 타입 추론 목적 가능성 → **관찰**, 제거 단정 보류 |

**"미사용" 단정에 사용한 검색 범위**: `c:\workspace_nori` **전 레포·전 확장자**
(`Grep pattern="hasActiveTarget|hasAnyPermission|hasNoPermission|hasPartialFailure" path=c:\workspace_nori`).
`nori-wm-ui-backend` 의 동명 `UserPrincipal` 은 별개 복제본이고, `nori-tc-exec/.../docs/*.md` 매치는 문서다.
reflection/DI/직렬화/라우팅 경유 사용도 위 표대로 개별 확인했다.

### 1.4 축 A 0건 항목 — 도달 불가 분기(hotspot 5파일 한정)

**[검증 보정] 실행 명령·검색 범위** (원문에 0건 축의 명령이 없어 보완):
```bash
# 검색 범위 = hotspot 5파일 전문 정독 + 아래 패턴 grep (cwd = c:\workspace_nori\nori-tc-ui-backend)
grep -nE "default ->|default:|if \(true\)|if \(false\)|== null\) \{ *return" \
  ui/tc-ui-core/src/main/java/com/nori/tc/ui/core/service/{DeployServiceCommandSupport,DeployPlanningService,DeployService}.java \
  ui/adapter/tc-ui-db-adapter/src/main/java/com/nori/tc/ui/adapter/db/{DeployJdbcStorePort,JpaEqpCrudPort}.java
# 레포 전체 도달 불가 분기 후보: grep -rn "default ->" --include=*.java ui/*/src/main ui/*/*/src/main
```

hotspot 5개 파일(`DeployServiceCommandSupport`, `DeployJdbcStorePort`, `DeployPlanningService`,
`JpaEqpCrudPort`, `DeployService`, 합 5,153 LOC)에서 A-15/A-16 외 도달 불가 분기 **0건**.
`DeployScenarioAction` switch 는 exhaustive 라 조용한 누락이 구조적으로 불가능하다(C-12).
레포 전체 기준 도달 불가 분기는 A-6/A-7 두 건이다.

---

## §2 축 B — 중복 코드 (10건)

> 승격 판단 규칙: **3회 이상 + 도메인 무관**일 때만 nori-tc-common 승격 후보. 도메인 결합 중복은 레포 내부 통합 대상.

| ID | 요구 축 | 트랙 | 대표 위치 | 출현 | 심각도 | 난이도 | common 승격 |
|---|---|---|---|---|---|---|---|
| B-1 | R3,R7 | R | `JpaEqpCrudPort.java:912` | **17곳** | P3 | M | ✅ 후보 |
| B-2 | R3 | R | `EqpParamController.java:250` | 13곳 | P3 | S | ❌ |
| B-3 | R3,R4 | R | `DeployPlanningStateJdbcPort.java:166` | 3곳 | **P2** | L | ❌ |
| B-4 | R3 | R | `DeployServiceCommandSupport.java:879` | 4곳(+6) | P3 | S | ❌ |
| B-5 | R3 | R | `JpaUserCrudPort.java:88` | 50곳 | P3 | M | ❌ |
| B-6 | R3 | R | `JpaUserCrudPort.java:146-173` | 7곳 | P3 | M | ❌ |
| B-7 | R3 | R | `DeployJdbcStorePort.java:1232` | 6곳 | P3 | S | ❌ |
| B-8 | R3 | R | `DeployServiceCommandSupport.java:1030` | 2세트 15메서드 | P3 | M | ❌ |
| B-9 | R3 | R | `UiGatewayEventKafkaPublisher.java:126` | 2쌍 | P3 | M | ❌ |
| B-10 | R3 | R | `JpaEqpCrudPort.java:179` | 30곳 | P3 | S | ❌ |

### B-1 / R3·R7 / R — 문자열 정규화 유틸 17중 재구현 (**common 승격 후보**)

**위치 (전부)**
`DeployServiceCommandSupport.java:1108`(+래퍼 `:419`,`:1044`) / `DeployPlanningService.java:1091` /
`DeployCommandAssembler.java:64` / `DeployRegisterQueryService.java:54` / `EqpRepairTaskService.java:67` /
`UiAuthProperties.java:244` / `DeployCurrentState.java:56` / `DeployPlanCommand.java:73` /
`JpaEqpCrudPort.java:912` / `JpaModelManagementDetailSupport.java:628` / `JpaModelDetailCommandPort.java:592` /
`JpaEqpParamCommandPort.java:413` / `EqpManagementDbSupport.java:355` / `DeployJdbcStoreSupport.java:464` /
`ModelDetailWorkflowJsonNormalizer.java:11` / `ModelControllerRequestSupport.java:222` / `EqpControllerResponseSupport.java:320`

```java
// JpaEqpCrudPort.java:912 — 이 본문이 17회
private String normalizeOptionalText(final String value) {
    if (value == null) { return null; }
    final String normalized = value.trim();
    return normalized.isEmpty() ? null : normalized;
}
```
**문제** 동일 계약(trim 후 빈 문자열이면 null)이 4개 이름(`normalizeOptionalText`/`normalizedText`/`normalizeText`/`normalizedOptionalText`)과
2개 반환형(`String`/`Optional<String>`)으로 흩어져, 신규 코드가 매번 어느 쪽을 쓸지 재결정한다.
**승격 근거** 입출력이 `String` 뿐 — 도메인 결합 0. `nori-tc-common` 사전 확인: 범용 텍스트 정규화 유틸 부재
(`grep -rnE "static (String|Optional<String>) normalize" --include=*.java` → 히트는 전부 `normalizeJarFileName`/`normalizePrefix`/`normalizeEventType` 등 목적 특화).
**심각도** P3 · **난이도** M · **다운스트림** ui-front 무영향. 승격 시 gateway/business 도 동일 패턴 보유 가능성 있어 3앱 공통 이득(승격 전 두 레포 실측 필요)

### B-3 / R3·R4 / R — `EqpManagementSnapshot → UiEquipmentProfileSnapshot` 90줄 매핑 3중 복제 (**P2**)

**위치 (전부)**
1. `ui/adapter/tc-ui-db-adapter/.../DeployPlanningStateJdbcPort.java:166-252` (`toEquipmentProfile` — planner 경로)
2. `ui/tc-ui-core/.../service/DeployServiceSnapshotSupport.java:404-493` (`buildGatewayEquipmentProfile` — gateway 보상 스냅샷)
3. `ui/tc-ui-core/.../service/DeployServiceCommandSupport.java:689-826` (`buildDirectEquipmentProfile` — direct 경로)

같은 22-필드 record 를 채우는데 8번째 슬롯 `Long modelKey` 의 의미가 이미 갈라져 있다:
```java
// DeployPlanningStateJdbcPort.java:175   [검증 보정] 원문 :174 → 실제 :175 (1줄 드리프트, 코드 내용은 일치)
snapshot.model() == null ? null : snapshot.model().modelKey(),        // ← modelKey (모델 정체성)
// DeployServiceSnapshotSupport.java:413-414   [검증 보정] 원문 :412-413 → 실제 :413-414
// F1: gateway-bound 보상 pre-state 의 modelKey 슬롯 = modelVersionKey(정체성 아님) — boot/런타임 키 공간 통일.
snapshot.eqp().modelVersionKey(),                                      // ← modelVersionKey (버전)
```
> **[검증 보정]** 두 인용의 **외곽 메서드 범위**(`:166-252` / `:404-493` / `:689-826`)와 인용 코드 문자열은 재검증에서 정확했고,
> 내부 라인 번호만 각 1줄 어긋나 있었다. 슬롯 위치(둘 다 `eqpPort` 다음·`gatewayJarKey` 앞 = record 8번째 필드
> `UiEquipmentProfileSnapshot.java:20 Long modelKey`)는 재확인 결과 동일하므로 **발견 자체는 유효**하다.
record 정의(`ui/tc-ui-core/.../model/UiEquipmentProfileSnapshot.java:20`)는 `Long modelKey` 단일 필드다.
**문제** 22-필드 매핑이 3벌 존재하고, 그중 한 슬롯의 키 공간이 이미 서로 다르다. 주석은 "modelKey 슬롯 = modelVersionKey" 로
통일을 선언하는데 JDBC planner 경로만 그 규약을 위반한다. record 에 필드를 추가하면 3곳을 모두 고쳐야 하고,
하나만 누락되면 Kafka `equipmentProfile` payload 가 조용히 결손된다.
**반증 시도** 두 산출물의 소비자가 달라 실제로 만나지 않을 가능성을 확인했다 — `toEquipmentProfile` 은 planner 의 currentState 로,
`buildGatewayEquipmentProfile` 은 `DeployServiceSnapshotSupport.java:137` 에서 `root.set("equipmentProfile", ...)` 로 Kafka payload 에 들어간다.
delta 판정(`DeployPlanningService.java:434,447`)은 `UiEquipmentProfileSnapshot.modelKey` 가 아니라 `DeployCurrentState.modelVersionKey()` 를
비교하므로 **현시점 런타임 오동작은 재현되지 않는다**. 따라서 P2 근거는 "이미 발생한 슬롯 의미 분기 + 22필드 3중 복제의 향후 결손 위험"이며,
gateway 측이 두 경로의 `modelKey` 를 동일 의미로 소비하는지는 **미확인**(gateway 레포 trace 필요).
**심각도** **P2** · **난이도** L · **다운스트림** nori-tc-gateway / nori-tc-business (`equipmentProfile` 역직렬화)

### B-2 / R3 / R — actor 정규화·추출 13중 복제 + fallback 기본값 드리프트

**위치** 서비스측 `normalizeActor` 7곳: `DeployServiceCommandSupport.java:412` / `DeployCommandAssembler.java:34` /
`DeployScenarioCatalogService.java:512` / `DeployEqpFinalizationService.java:118` /
`EqpDirectScenarioLocalMutationCoordinator.java:401` / `JpaEqpCrudPort.java:942` / `DeployJdbcStoreSupport.java:457`
웹측 principal→actor 추출 6곳: `DeployController.java:351`(=`SYSTEM`, 상수 `:59`) / `DeployScenarioAdminController.java:132`(`SYSTEM`) /
**`EqpParamController.java:250-260`(=`ANONYMOUS`)** / `EqpControllerCommandSupport.java:68-78`(`SYSTEM`) /
`ModelControllerRequestSupport.java:192`(`SYSTEM`) / `GroupAdminFacade.java:121`(SecurityContextHolder 직접)
```java
// EqpParamController.java:250-252 — 유일하게 ANONYMOUS
private static String resolveCurrentUser(final Authentication authentication) {
    if (authentication == null) { return "ANONYMOUS"; }
```
**문제** 동일 로직 13중 복제 + 미인증 fallback 값이 `SYSTEM`/`ANONYMOUS` 로 갈라짐.
**반증 시도 → 성립** 해당 fallback 분기는 도달 불가하다(§0.6). 남는 것은 순수 중복.
**심각도** P3 (P2 에서 강등) · **난이도** S · **다운스트림** `tc_*.created_by`/`updated_by` 값 표준화 시 기존 데이터와 혼재 가능

### B-4 / R3 / R — `UiEquipmentProfileSnapshot` 전-필드 재조립 4중 복제

`DeployServiceCommandSupport.java:662-686`, `:801-825`, `:879-903`, `DeployPlanningService.java:796-819`
(+ `PlcSettingsSnapshot` 재조립 6곳: `DeployServiceCommandSupport.java:649,758,870` / `DeployPlanningService.java:779` /
`DeployPlanningStateJdbcPort.java:241` / `DeployServiceSnapshotSupport.java:480`)
```java
// DeployServiceCommandSupport.java:879 — plcSettings 하나 바꾸려고 22개 필드 복사
return new UiEquipmentProfileSnapshot(
        profile.eqpKey(), profile.eqpId(), profile.commInterfaceType(), profile.socketType(),
        ... /* 17개 더 */ ..., plcWithGroups);
```
**문제** record wither 부재로 1필드 변경마다 22-인자 생성자를 재작성한다. 동형 타입 인접(`String socketType` / `String eqpIp`)이라
인자 순서 실수가 컴파일을 통과한다. **심각도** P3 · **난이도** S(record `with*` 추가) · **다운스트림** 없음

### B-5 / R3 / R — db-adapter 예외 변환 블록 50중 복제

**위치 (`catch (RuntimeException)` 전수)** `JpaEqpCrudPort.java:170,239,278,312,325` / `JpaEqpParamCommandPort.java:307` /
`JpaEqpQueryPort.java:130,167,284,331,383,437` / `JpaGroupCrudPort.java:71,111,147,193,225` /
`JpaGroupPermissionMappingPort.java:71,114,167,201` / `JpaLineQueryPort.java:37` / `JpaModelCrudPort.java:75,115,161,195` /
`JpaModelFlowCommandPort.java:87,130` / `JpaModelManagementPort.java:150,195,219,268,318,367,411,475` /
`JpaPermissionCrudPort.java:72,112,158,190` / `JpaUserCrudPort.java:88,128,174,215` /
`JpaUserGroupMappingPort.java:74,117,169,207,259,293`
유일한 추출 사례: `JpaEqpCrudPort.java:955 toUiException(...)`.
**문제** 12~16줄 블록 50회 반복. **심각도** P3 (§0.6 강등) · **난이도** M · **다운스트림** 통합 시 일부 엔드포인트 500→409 변경 가능 → ui-front 에러 핸들링 확인

### B-6 / R3 / R — `findAll(UiPageRequest)` 페이징 골격 7중 복제
`JpaUserCrudPort.java:146-173` / `JpaGroupCrudPort.java:165-192` / `JpaModelCrudPort.java:133-160` /
`JpaPermissionCrudPort.java:130-157` / `JpaEqpQueryPort.java:101-129` / `JpaUserGroupMappingPort.java:136-168`, `:226-258`
28줄 골격(effectivePage→dbPage→`UiDbPagedCountSupport.resolveTotalCount`→`PagedResponse.of`)이 7회 동일.
총건수 산정 정책(scan-limit fallback)이 7곳에 흩어져 정책 변경 시 전수 수정. **P3 / M / 다운스트림** 목록 API 페이지네이션 회귀 테스트 필요

### B-7 / R3 / R — JDBC `nullableLong`/`nullableInt` 6중 복제
`DeployJdbcStorePort.java:1232`(`nullableLong`), `:1237`(`nullableInteger`) / `DeployJdbcStoreSupport.java:482`(`nullableInt`) /
`DeployPlanningStateJdbcPort.java:254`,`:259` / `LiveMapLayoutJdbcStorePort.java:135`
```java
private Long nullableLong(final ResultSet resultSet, final String columnLabel) throws SQLException {
    final long value = resultSet.getLong(columnLabel);
    return resultSet.wasNull() ? null : value;
}
```
**common 승격 아님** — `nori-tc-business`/`nori-tc-gateway` 에 `wasNull()` 호출 0건(원시 JDBC RowMapper 는 ui-backend 단독).
`tc-ui-db-adapter` 내부 `UiJdbcResultSetSupport` 로 국지 추출이 적정. **P3 / S / 다운스트림 없음**

### B-8 / R3 / R — JsonNode 안전 접근자 2세트 15메서드 별도 구현
`DeployServiceCommandSupport.java:1030,1037,1059,1080,1094` (lenient — 0 이하를 null 로 승격) vs
`DeployPlanningService.java:963,970,978,992,1007,1015,1037,1044,1052,1069` (strict — 숫자 아니면 `UiBadRequestException`)
**심각도** P3 (§0.6 강등) · **난이도** M · **다운스트림** 관대→엄격 통일 시 기존 통과 payload 가 400 이 될 수 있음 → ui-front deploy 폼 회귀 확인

### B-9 / R3 / R — Gateway/Business 어댑터 near-duplicate 쌍
`UiGatewayEventKafkaPublisher.java:126-224` ↔ `UiBusinessEventKafkaPublisher.java:66-167` — 본문 ~92줄 문자 단위 동일, 실차이 2곳:
```java
// Gateway:150 — 명시 partition
final ProducerRecord<String, Object> record = new ProducerRecord<>(topic, partition, key, kafkaMessage);
// Business:92 — partition 미지정
final ProducerRecord<String, Object> record = new ProducerRecord<>(topic, key, kafkaMessage);
```
(+ `estimatePayloadBytes` 양쪽 중복: `Gateway:226`, `Business:169`)
`GatewayDlqRedisService.java:76-130` ↔ `BusinessDlqRedisService.java:70-124` (`listAllDlq`), `:188` ↔ `:135` (`scanKeys` 완전 동일)
**문제** size guard / `send().get(timeout)` / 예외 분류 / 구조화 로깅이 이중 유지 → timeout 처리 개선이 한쪽에만 반영될 위험.
**P3 / M / 다운스트림** Kafka 발행 — 통합 시 partition 지정·미지정 두 경로 e2e 확인

### B-10 / R3 / R — 가드절 검증 30중 인라인 반복
`eqpId 비어 있음` 11곳(`JpaEqpCrudPort.java:179,287,320,335` / `JpaEqpManageQueryPort.java:26` / `JpaEqpParamCommandPort.java:318` /
`JpaEqpQueryPort.java:153,200,306,351,408` / `EqpManagementCommandValidator.java:34,80` / `EqpManagementService.java:74`),
`modelVersionKey 1 이상` 9곳, `groupId/userPk/flowId 1 이상` 10곳.
같은 조건에 `"eqpId는 비어 있을 수 없습니다."`(`:320`)와 `"eqpId는 필수입니다."`(`:335`) 두 메시지 혼용.
**P3 / S / 다운스트림** 400 메시지 문자열 — ui-front 가 메시지 매칭을 하지 않는지 확인 후 통일

---

## §3 축 C — Code smell (Fowler)

### 3.1 hotspot 실측표

| 클래스 | LOC | public | package-private | private | 인스턴스 필드 | 최장 메서드 |
|---|---|---|---|---|---|---|
| `DeployJdbcStorePort` | 1274 | 23(+ctor) | 0 | 9 | 2 | `findDeployHistory` **113줄** |
| `DeployServiceCommandSupport` | 1114 | 0 | 30(+ctor) | 14 | 8 | `buildDirectEquipmentProfile` **138줄** |
| `DeployPlanningService` | 1105 | 2(+ctor) | 0 | 45 | 4 | `previewRegisterScenarioSelection` **106줄** |
| `JpaEqpCrudPort` | 967 | 5(+ctor) | 1 | 32 | **16(주입)** | `upsertProtocolDetails` **67줄** |
| `DeployService` | 693 | 14(+ctor) | 0 | 12 | 8 | `resolveAsIsState` **72줄** |

### 3.2 hotspot 중첩 smell (항목화) — 15건, 전부 P3/R

| ID | 요구 축 | smell | 위치 | 난이도 | 다운스트림 |
|---|---|---|---|---|---|
| C-1 | R6 | Long Method 138줄 `buildDirectEquipmentProfile` | `DeployServiceCommandSupport.java:689-826` | M | gateway/business payload — 분할 시 필드 결손 계약 테스트 필요 |
| C-2 | R6 | Long Method 113줄(SQL 리터럴 87줄) `findDeployHistory` | `DeployJdbcStorePort.java:872-984` | M | LATERAL 카운트 블록이 `summarizeTargets`(`:987-1021`)와 10개 `FILTER` 절 중복 — EXPLAIN 대조 필요 |
| C-3 | R6 | Long Method 4개 (106/102/92/69줄) | `DeployPlanningService.java:109-214, 719-820, 393-484, 323-391` | L | deploy 계획 전 경로 — `DeployPlanningServiceTest`(678줄) 전량 회귀 |
| C-4 | R6 | Long Method 2개 (72/61줄) | `DeployService.java:214-285, 114-174` | S | deploy summary `asIsState` — ui-front 폴링 화면 |
| C-5 | R6 | Long Method 3개 + 프로토콜 3분기 방어코드 비대칭 | `JpaEqpCrudPort.java:409-475, 177-242, 672-724` | M | `tc_eqp_hsms/socket/plc` — **§0.6 에서 P2→P3 강등**(commInterface 불변으로 누락 delete 는 no-op) |
| C-6 | R6 | Large Class 1274 LOC / public 23 / 필드 2 ("SQL 저장고") | `DeployJdbcStorePort.java:60-1274` | L | `DeployStorePort` 구현 교체 시 `DeployService`/`DeployExecutor`/`DeployTraceRecorder` 재배선 + `@Import` 수정 |
| C-7 | R6 | Large Class 1114 LOC / 책임 6축(상태전이·발행·스냅샷·메시지·JSON·스레드팩토리) | `DeployServiceCommandSupport.java:46-1114` | L | 3개 서비스 재배선 + `validate-module-imports` 검증 |
| C-8 | R6 | Large Class 1105 LOC / public 2 vs private 45, 그중 208줄(19%)이 JSON 유틸 | `DeployPlanningService.java:41-1105` | M | 없음(private 이동) |
| C-9 | R6 | Large Class 967 LOC / **생성자 파라미터 16개** | `JpaEqpCrudPort.java:62-967` | L | DI 그래프 — 분리 시 `UiDbAdapterModuleConfiguration` `@Import` 갱신 필수 |
| C-10 | R6 | Feature Envy — `executionSnapshot.*()` **53회** 접근 vs 자기 필드 1회 | `DeployServiceCommandSupport.java:689-826` | M | B-3 과 동일 |
| C-11 | R6 | Feature Envy — `DeployStoredSnapshot` JSON 스키마와 `EqpManagementSnapshot` 객체 구조 양쪽을 직접 해석 | `DeployService.java:214-285, 294-317` | M | deploy summary `asIsState` |
| C-12 | R6 | Shotgun Surgery — `DeployScenarioAction` 1개 추가 시 4파일 5 switch 동시 수정 | `DeployScenarioAction.java` / `DeployServiceCommandSupport.java:432-487, 536-586` / `DeployServiceSnapshotSupport.java:110` / `EqpDirectScenarioRunner.java` | M | 시나리오 카탈로그 YAML/DB 동기 필요. exhaustive switch 라 조용한 누락은 없음(→P3) |
| C-13 | R6 | Divergent Change — `UiCommandEventType` 축 switch 4개 + 무관 변경 사유 2축 공존 | `DeployPlanningService.java:276, 332, 400, 637` | L | deploy 계획 전 경로 회귀 |
| C-14 | R6 | Divergent Change — 변경 사유 5축(토픽 라우팅/상태머신/UI 문구/JSON 스키마/스레드) | `DeployServiceCommandSupport.java:92-138, 140-259, 272-305, 346-410, 998-1113` | L | C-7 과 동일 |
| C-15 | R6 | Data Clumps + Primitive Obsession + Message Chains | 연결설정 6-튜플 4곳 / `(lastErrorCode,lastErrorMessage)` 7곳 / `target.planDraft()` 11회·`executionSnapshot.<part>()` 53회 | M~L | 값 객체화는 Kafka 계약 DTO·DB 컬럼 매핑에 닿음 → 직렬화 형태 불변 보장 필요 |

> **C-12 가 Deploy* 클러스터 co-change 7~11(hotspots §2)의 구조적 원인**이다. C-7/C-14 와 함께 R 트랙 1건으로 묶는 것이 합리적.

### 3.3 hotspot 밖 smell (한 줄 목록)

- `DeployCommandAssembler.java` 210 LOC, public 25 중 24개가 `commandSupport.*` 순수 위임 — **Middle Man**
- 테스트 Large Class: `DeployServiceTest.java` 2504 LOC / `JpaEqpCrudPortTest.java` 1261 / `EqpDirectScenarioServiceTest.java` 1173
- Large Class 예비군: `JpaModelDetailCommandPort.java` 844 / `EqpDirectScenarioService.java` 723 / `JpaModelManagementDetailSupport.java` 651 / `DeployScenarioCatalogService.java` 648 / `EqpController.java` 507 / `UiAuthProperties.java` 334(설정 record 에 검증·정규화 내장)
- `TcUiBackendAutoConfiguration.java` 361 LOC(`@Import` 집중) — **Divergent Change 허브**
- 페이징 모델 2종 공존: `DeployController.getDeployTargets`(`:343-344`)만 `page/size`, 나머지 6개 컨트롤러는 `UiPageRequestSupport.resolve(offset,limit)` — Divergent Change 예고
- `Optional.<T>empty().orElse(null)` 3곳 (`DeployServiceCommandSupport.java:638,642,836`)

---

## §4 축 D — 결함 / 버그 / 로직 보완 (18건 — P2 11 / P3 7)

### 4.0 전 항목 공통 전제 (반증의 근거)

- **`ui/tc-ui-core` 전체에 `@Transactional` 이 0개다.** 실측: `grep -rn "@Transactional" ui/ | grep -v test`
  → `tc-ui-db-adapter` 40건 + `tc-ui-web-adapter` 2건, **core 0건**.
  즉 core service 의 다중-port 오케스트레이션은 **항상** 어댑터 메서드 단위 개별 트랜잭션으로 쪼개진다.
  "상위 호출자가 트랜잭션을 걸었다"는 반증은 이 레포에서 **성립하지 않는다**.
- 같은 레포 안에 lost-update 를 제대로 막은 **자기 컨벤션이 존재한다**:
  `EqpPlcPollGroupAdapter`(`@Version` + `TransactionTemplate` + 5회 재시도), `JpaEqpParamCommandPort.java:285`(`eqpStore.findByEqpIdForUpdate` row lock).
  아래 D-1/D-5/D-12 는 **그 컨벤션에서 이탈한** 경로다.

### 4.1 P2 (11건)

**D-1 / R4·R5·R8 / F / `ui/adapter/tc-ui-db-adapter/.../JpaEqpCrudPort.java:186-215`**
```java
@Transactional
public EqpManagementSnapshot update(final String eqpId, final EqpManagementCommand.Update command) {
    ...
    final EqpManagementSnapshot existing = requireSnapshot(eqpId);      // :186  잠금 없는 SELECT
    ...
    final TcEqp updatedEqp = eqpStore.upsert(new UpsertTcEqp(           // :199  전 컬럼 blind write
            existing.eqp().eqpId(), command.lineKey(), ...,
            existing.eqp().enabled(), existing.eqp().createdBy(), ...   // :212-213  stale read 값
    ));
```
**문제** `tc_eqp` read-modify-write 가 row lock(`findByEqpIdForUpdate`)도 `@Version` 도 없이 수행된다.
`@Transactional` 은 있으나 기본 READ_COMMITTED + 무잠금 SELECT 이므로 두 트랜잭션이 모두 읽기에 성공한다.
**실패 시나리오** 운영자가 UI 에서 `eqpIp` 를 바꾸는 동시에 deploy finalization 이 `modelVersionKey` 를 커밋하면,
나중에 commit 한 트랜잭션이 자신의 stale read 로 상대 변경분을 덮어써 `eqpIp` 가 조용히 원복된다.
**반증 시도** ① `@Transactional` 격리 → READ_COMMITTED 라 불충분. ② 잠금 API 부재 → `JpaEqpParamCommandPort.java:285` 가
동일 테이블에 `findByEqpIdForUpdate` 를 사용하므로 API 는 존재하고 여기서만 미사용. **반증 실패.**
**심각도** P2 · **난이도** S · **다운스트림** nori-tc-gateway (잘못된 eqpIp/modelVersionKey 가 EQP_UPDATE 로 전파 → 실설비 오연결)
**cross-repo**: gateway S06 D-14 / business S07 D-6 과 동종 근본 원인 → **S14 병합 예고**(원본 ID 보존)

**D-2 / R4·R5 / F / `ui/tc-ui-core/.../service/DeployServiceCommandSupport.java:97-137` + `DeployJdbcStorePort.java:136-149`**
```java
final DeploySummarySnapshot currentSummary = deployStorePort.findDeploySummary(deployKey).orElse(null);
if (currentSummary == null || currentSummary.deployStatus() == DeployStatus.CANCELED) { return; }   // :98
...
deployStorePort.updateDeployStatus(deployKey, deployStatus, false, ...);                            // :131
```
```sql
UPDATE tc_deploy SET deploy_status = :deployStatus, ... WHERE deploy_key = :deployKey   -- 상태 가드 없음(직접 확인)
```
**문제** CANCELED 체크(읽기)와 상태 기록(쓰기)이 별도 트랜잭션이고, UPDATE 에 `AND deploy_status <> 'CANCELED'` 가드가 없다.
**실패 시나리오** 마지막 target 스레드가 `findDeploySummary`(RUNNING)를 읽은 직후 사용자가 취소하면,
그 스레드가 뒤이어 COMPLETED 를 써서 **취소된 deploy 가 "작업 완료"로 되살아난다**(target 들은 CANCELED 인 채).
**반증 시도** `DeployExecutor.executeTarget:100` 이 `finally` 에서 target 마다 `refreshDeployStatus` 를 부르므로 취소와의 동시 진입이 오히려 흔하다. 조건부 UPDATE·락 모두 부재. **반증 실패.**
**심각도** P2 · **난이도** S · **다운스트림** UI 성공 표시 → 운영자가 미적용 설비를 적용된 것으로 오인

**D-3 / R4·R8 / F / `ui/tc-ui-core/.../service/DeployService.java:121-160`**
```java
final PersistedDeploy persistedDeploy = deployStorePort.createDeploy(command, planningResult, normalizedActor); // :121 (txn A)
final List<PersistedDeployTarget> executableTargets = persistedDeploy.targets().stream()...toList();
executableTargets.forEach(commandAssembler::captureTargetPreState);                                              // :129 (txn B..N)
...
deployStorePort.updateDeployStatus(persistedDeploy.deployKey(), DeployStatus.READY, false, ...);                 // :157 (txn Z)
```
**문제** deploy 행 생성 / target pre-state 스냅샷 N건 / READY 승격이 각각 독립 트랜잭션이다(core `@Transactional` 0 — §4.0).
**실패 시나리오** 5번째 target 의 `captureTargetPreState`(jar 바이트 SHA256 계산 포함)가 DB 오류로 던지면
deploy 는 **PLANNING 에 영구 고착**되고 target 1~4 만 스냅샷을 가진 좀비 deploy 가 된다(`startDeploy` 는 READY 만 허용).
**반증 시도** 컨트롤러/파사드 `@Transactional` 확인 → 웹 어댑터에는 `PermissionAdminFacade` 외 전무. 보상 로직 부재. **반증 실패.**
**심각도** P2 · **난이도** M · **다운스트림** `findActiveEqpConflict` 가 PLANNING 을 active 로 보므로 **해당 설비의 후속 deploy 가 전부 409 차단**(수동 삭제 전까지)

**D-4 / R4·R8 / F / `ui/tc-ui-core/.../service/DeployService.java:428-441`**
```java
deployStorePort.markTargetsCanceled(deployKey, now, "DEPLOY_CANCELED", "...");    // :428 (txn A)
deployStorePort.updateDeployStatus(deployKey, DeployStatus.CANCELED, false, ...); // :434 (txn B)
activeTraceIds.forEach(deployTraceRecorder::cancel);                              // :441 (Redis)
```
**문제** 취소가 3개 독립 커밋으로 쪼개져 있고 부분 실패 보상이 없다.
**실패 시나리오** txn A 성공 후 B 실패 → target 전부 CANCELED / deploy 는 RUNNING. pending·running=0 이라 어떤 실행 스레드도
`refreshDeployStatus` 를 다시 부르지 않아 **RUNNING 영구 고착**(`isDeletable(RUNNING)=false`).
**반증 시도** 자가 복구 경로 확인 → `refreshDeployStatus` 진입점이 `DeployExecutor` finally 뿐이고 그 시점엔 target 스레드가 종료. **반증 실패.**
**심각도** P2 · **난이도** S · **다운스트림** D-3 과 동일한 설비 잠금

**D-5 / R4·R5 / F / `ui/tc-ui-core/.../service/DeployEqpFinalizationService.java:33-38`**
```java
final EqpManagementSnapshot snapshot = persistenceService.findSnapshotByEqpId(planDraft.eqpId())  // :33 (txn A)
        .orElseThrow(() -> new UiNotFoundException("설비를 찾을 수 없습니다. eqpId=" + planDraft.eqpId()));
persistenceService.update(planDraft.eqpId(),
        buildFinalizationUpdateCommand(snapshot, planDraft.desiredState(), actor));               // :36 (txn B → D-1 경로)
```
**문제** 읽기(txn A)와 전-컬럼 덮어쓰기(txn B, 내부는 D-1 의 무잠금 RMW)가 분리되어 있고,
`buildFinalizationUpdateCommand` 가 lineKey·commMode·eqpIp·eqpPort·logPolicy·hsms/socket/plc 설정까지 **stale snapshot 전량을 그대로 다시 쓴다**.
**실패 시나리오** 두 호출 사이에 UI 편집이 커밋되면 그 편집이 통째로 소실된다.
**반증 시도** 설비별 Redis lease(`awaitTargetLease(eqpId,...)`)가 보호하는지 확인 → lease 는 **deploy target 실행 경로 전용**이며
UI 직접 편집(`EqpDirectScenarioService`)은 lease 를 취득하지 않는다. **반증 실패.**
**심각도** P2 · **난이도** M · **다운스트림** D-1 과 동일. **D-1 과 함께 고치면 난이도 S**

**D-6 / R4·R5·R8 / F / `ui/tc-ui-core/.../service/EqpDirectScenarioService.java:109-143`**
```java
final EqpManagementSnapshot existingSnapshot = requireExistingSnapshot(eqpId);   // :109  읽기
...
runner.run(context);                                                              // :143  Kafka 왕복(수십 초) 후 DB update
```
**문제** publish-first 설계상 읽기와 최종 DB 쓰기(`finalizeSuccess` → `persistenceService.update`) 사이에 Kafka 왕복 전체가 끼어 있어
RMW 창이 수십 초다. deploy 경로와 달리 eqpId lease 가 없다.
**실패 시나리오** 두 운영자가 같은 설비를 거의 동시에 편집하면 A gateway 반영 → B gateway 반영 → A DB 커밋 → B DB 커밋 순서가 가능해,
gateway 가 중간에 A 설정으로 재구성되고 A 의 응답은 200 으로 나간다.
**반증 시도** eqpId 단위 직렬화 확인 → direct 경로에 `DeployExecutor:81 awaitTargetLease` 같은 lease 없음.
`rejectRoutePartitionChangeOnStartedEquipment`(`:116`)는 routePartition 만 방어. **반증 실패.**
**심각도** P2 · **난이도** L · **다운스트림** nori-tc-gateway equipment context 와 DB 의 영구 divergence 가능

**D-7 / R4·R8 / F / `ui/tc-ui-core/.../service/EqpDirectScenarioService.java:325-336`**
```java
runner.run(context);                                  // :325  gateway PASS 확인
final long eqpKey = existingSnapshot.eqp().eqpKey();
if (eventType == UiCommandEventType.EQP_PLC_GROUP_START) {
    pollGroupPort.addPollGroup(eqpKey, group);        // :329  DB 반영 — 실패 시 보상 없음
} else { pollGroupPort.removePollGroup(eqpKey, group); }
...
} catch (Exception exception) { return toCommandResult(exception); }   // :334
```
**문제** gateway 는 이미 폴링을 시작했는데 DB 반영이 실패하면 보상(gateway GROUP_END 재발행)도 repair task 기록도 없이 에러만 반환한다.
**실패 시나리오** `addPollGroup` 이 `MAX_OPTIMISTIC_LOCK_RETRIES` 초과로 `UiConflictException` 을 던지면
**gateway 는 폴링 중 / DB `enabled_poll_groups` 는 비어 있음**. 이후 EQP 재기동 시 DB 기준으로 폴링이 재개되지 않아 조용히 수집이 멈춘다.
**반증 시도** `EqpDirectScenarioRollbackService` 커버 확인 → rollback 은 `runner.run` **내부** 실패만 처리하고 `:329` 는 run 종료 후라 대상 밖.
`compensateLocalFailure` 도 `EQP_PLC_GROUP_START` 는 명시적 no-op(주석 `:194-197`). **반증 실패.**
**심각도** P2 · **난이도** S · **다운스트림** nori-tc-gateway (PLC 수집 무음 중단)

**D-8 / R4·R5 / F / `ui/adapter/tc-ui-redis-adapter/.../async/AsyncResultStoreService.java:157-177`**
```java
final Optional<RedisUiAsyncResultEntry> existingOpt = readRedisEntry(key);           // :157
...
if (AsyncStatus.COMPLETED.name().equals(existing.getAsyncStatus())) { ... return; }  // :167
...
writeWithConfiguredTtl(key, timeoutEntry, ttlSeconds);                                // :177
```
**문제** Redis 상태의 read-check-write 가 원자적이지 않다(WATCH/Lua/`SET XX` 미사용).
`promoteExpiredPendingToTimeout` 스케줄러와 Kafka 리스너의 `markCompleted` 가 별도 스레드에서 같은 키를 쓴다.
**실패 시나리오** 타임아웃 경계에서 스케줄러가 PENDING 을 읽은 직후 gateway PASS 응답이 COMPLETED 를 쓰면,
스케줄러가 그 위에 TIMEOUT 을 덮어써 **성공한 EQP_START 가 프런트에 TIMEOUT 으로 보인다**.
**반증 시도** `TIMEOUT_BUFFER_MS = 5_000` 이 창을 없애는지 확인 → 버퍼는 만료 *시점*을 늦출 뿐 read↔write 사이 창은 그대로. **반증 실패.**
**심각도** P2 · **난이도** S · **다운스트림** nori-tc-ui-front (오탐 재시도 → 중복 명령)

**D-9 / R4·R8 / F / `ui/tc-ui-core/.../service/EqpManagementService.java:133-168` + `EqpController.java:290,472`**
```java
public CompletableFuture<EqpCommandResult> create(final EqpManagementCommand.Create command,
                                                  final long timeoutMs) {          // timeoutMs 사용처 없음
    return CompletableFuture.supplyAsync(() -> eqpDirectScenarioService.executeCreate(command), eqpManagementExecutor);
}
```
```java
final DeferredResult<ResponseEntity<ApiResponse<Void>>> deferredResult = new DeferredResult<>();  // :290, :472 — timeout 인자 없음
```
**문제** 컨트롤러가 넘기는 `dualRequestTimeoutMs` 가 create/update/delete **3곳 모두에서 완전히 무시**되고(`orTimeout` 없음),
`DeferredResult` 도 명시 타임아웃이 없어 컨테이너 기본값에 위임된다.
`spring.mvc.async.request-timeout` 설정도 ui-backend·exec 어디에도 없다(직접 grep 0건).
**실패 시나리오** EQP_CREATE 시나리오 step 합계가 컨테이너 기본 async timeout 을 넘으면 사용자에겐 503 `AsyncRequestTimeoutException` 이
나가지만 백그라운드 작업은 계속 진행돼 **응답과 실제 결과가 어긋난다**(사용자는 실패로 알고 재시도 → D-6 동시 편집 유발).
**반증 시도** `submitManagementCommand:475` 의 `isSetOrExpired()` 가드 확인 → 이중 setResult 만 막고 작업 취소는 하지 않음.
프레임워크가 `timeoutMs` 를 소비하지도 않음. **반증 실패.**
**심각도** P2 · **난이도** S (`future.orTimeout(...)` + `new DeferredResult<>(timeoutMs)`) · **다운스트림** nori-tc-ui-front

**D-10 / R4·R8 / F / `ui/tc-ui-core/.../service/EqpManagementService.java:325-341`**
```java
CompletableFuture.runAsync(() -> {
    final EqpCommandResult result = action.get();
    if (!result.success() && "TIMEOUT".equalsIgnoreCase(result.errorCode())) { asyncResultStorePort.markTimeout(traceId); return; }
    asyncResultStorePort.markCompleted(traceId, toAsyncReply(traceId, eqpId, interfaceType, eventType, result));
}, eqpManagementExecutor);
return EqpAsyncAcceptResult.accepted(traceId);
} catch (RuntimeException exception) { asyncResultStorePort.markTimeout(traceId); ... }   // :334 — 제출 실패만 잡음
```
**문제** 람다 본문 예외를 받는 `exceptionally`/`whenComplete` 가 없어 **로그 한 줄 없이 소실**된다.
`:334` catch 는 `runAsync` 제출 실패(RejectedExecution)만 커버한다.
**실패 시나리오** Redis 장애로 `markCompleted` 가 던지면 traceId 는 PENDING 에 남고, 스케줄러가 뒤늦게 TIMEOUT 으로 승격시켜
**실제로는 성공한 EQP_START 가 "타임아웃"으로 보고**되며 원인 예외는 어디에도 남지 않는다.
**반증 시도** `EqpDirectScenarioService.execute*` 가 모든 예외를 `EqpCommandResult` 로 변환하므로 `action.get()` 자체는 거의 안 던짐을 확인.
그러나 `markCompleted`/`toAsyncReply` 는 그 밖이라 여전히 노출. **반증 부분 실패 → P2 유지.**
**심각도** P2 · **난이도** S · **다운스트림** 무음 장애(관측 불가)

**D-18 / R4·R5 / F / `ui/adapter/tc-ui-redis-adapter/.../dlq/BusinessDlqRedisService.java:84-92` (신규 — 축 F 에서 승격)**
```java
final Object raw = businessRedisTemplate.opsForValue().get(key);        // :84
if (raw instanceof RedisBusinessDlqEntry entry) { entries.add(entry); } // :85
else if (raw != null) {
    log.warn("Business DLQ 키 타입 불일치. key={}, actualType={}", key, raw.getClass().getName());   // :89
}
```
**문제** 생산측과 소비측의 Redis value serializer **mode 가 불일치**한다.
- 생산: nori-tc-business `RedisBusinessDlqPublisher` → common `TcDbRedisAutoConfiguration.java:119-127`
  `createDefaultJsonSerializer()` = `new GenericJacksonJsonRedisSerializer(objectMapper)` — **typeless**(플레인 `JsonMapper`, default typing 미활성)
- 소비: `UiRedisConfiguration.java:86` `uiRedisValueSerializer` = `RedisSerializer.json()` → `businessRedisTemplate`(`:143-151`) — **typed**(`@class` 기대)

같은 파일 `UiRedisConfiguration.java:202-206` 의 주석이 이 사실을 자백한다:
```java
// 적재 측(Gateway/Business)의 tcRedisTemplate과 동일한 typeless GenericJacksonJsonRedisSerializer 사용
final GenericJacksonJsonRedisSerializer commLogSerializer = new GenericJacksonJsonRedisSerializer(uiRedisObjectMapper);
```
comm-log 경로는 전용 템플릿으로 mode 를 맞췄으나 **DLQ 경로는 맞추지 않았다.**
`RedisUiAsyncResultEntry`(`AsyncResultStoreService.java:282-286`)·`UiTaskResult`(`DualResponseRedisAdapter.java:200`)·
`EqpRepairTask`(`RedisEqpRepairTaskStore.java:70`)에는 있는 Map fallback(`convertValue`)이 DLQ 경로에는 없다.
**실패 시나리오** business 가 typeless 로 적재한 DLQ 항목을 ui-backend 가 typed 로 읽어 `LinkedHashMap` 을 얻고,
`:85` `instanceof` 가 false → `:89` WARN 후 스킵 → **`GET /api/dlq/business` 가 항목이 존재해도 빈 목록을 반환**한다.
DLQ 모니터링 화면이 조용히 무력해진다.
**반증 시도** ① 소비측이 typeless 를 쓰는지 → `:86` 이 `RedisSerializer.json()` 임을 직접 확인(반증 실패).
② `@class` 를 붙이는 생산 경로가 따로 있는지 → common `TcDbRedisAutoConfiguration:80` 이 기본 serializer 를 쓰고,
`valueSerializerProvider` 오버라이드가 business 에 있는지는 **미확인**. ③ 런타임 실증(실제 Redis 값 조회)은 **미수행**.
**심각도** P2 (①로 코드 대조는 확정, ②③ 미확인이므로 P1 승격 안 함) · **난이도** S(전용 typeless 템플릿 주입 또는 Map fallback 추가)
**다운스트림** nori-tc-business (생산측), nori-tc-common (`TcDbRedisAutoConfiguration` 소유), nori-tc-ui-front (DLQ 화면)
**cross-repo**: S07 E-2 가 이 계약을 "JDK 직렬화"로 서술했으나 실제는 JSON 이다 → §6.3 정정. **S14 병합 예고.**

### 4.2 P3 (7건)

> **[검증 보정] 트랙 · 다운스트림 (S09 완결성 게이트에서 누락 필드 보완)** — 심각도는 7건 전부 **P3**(섹션 제목).
> 트랙은 방법론 매핑(D·E → F, 요구 축 R6 → R)에 따라 **D-11~D-15·D-17 = F**, **D-16 = R**(요구 축 R6, 품질 강등 항목).
> 다운스트림은 각 행의 실패 시나리오에서 직접 도출: **D-12·D-13·D-17 → nori-tc-ui-front**(각각 원인불명 500 / 인프라장애가 404 로 / 400 오분류가 그대로 노출),
> **D-14 → nori-tc-ui-front(성공 오표시) + nori-tc-gateway(step 미실행 = 설비 미적용)**,
> **D-11·D-15·D-16 → 없음**(fd 누적 / 스레드 기아 / 드라이버 의존 — ui-backend 내부에서 완결).

| ID | 요구 축 | 위치 | 문제 · 실패 시나리오 | 난이도 |
|---|---|---|---|---|
| D-11 | R4 | `DeployScenarioCatalogService.java:281` | `Files.list(...)` 를 try-with-resources 없이 사용 → `DirectoryStream` 미해제. catalog reload API 반복 호출 시 fd 누적. 반증: 호출이 `@PostConstruct` 1회뿐인지 확인했으나 `reload` 경로에서도 재호출됨(빈도 낮아 P3) | S |
| D-12 | R4 | `JpaEqpCrudPort.java:442-453` | `enabled_poll_groups` `@Version` RMW 에 재시도 없음 + `toUiException:955-965` 이 `OptimisticLockingFailureException` 미매핑. PLC 폼 저장과 GROUP_START 가 겹치면 원인 불명 500 노출(데이터 손상은 `@Version` 이 방지). 반증: `UiDbAdapterExceptionSupport.isConflict` 가 `ConcurrencyFailureException` 계열을 잡을 가능성 → **부분 반증**, P3 | S |
| D-13 | R4 | `AsyncResultStoreService.java:219-222` | Redis 장애를 `Optional.empty()`(404)로 downgrade → 컨트롤러 404, `awaitAsyncResult` 는 deadline 까지 polling. 인프라 장애와 "traceId 없음"이 구분 불가 | S |
| D-14 | R4 | `DeployJdbcStorePort.java:1158-1162` | 미지 `DeployDeltaKind` 를 조용히 무시 → `when.deltaAny` 필터가 step 을 통째로 건너뛰고 COMPLETED 로 종료. 선행조건(운영 중 enum 변경) 필요 | S |
| D-15 | R8 | `DeployExecutor.java:319-337` | lease 획득 busy-wait(50ms)이 `targetExecutor` 스레드를 최대 step timeout 합(~90s)만큼 점유 → `targetConcurrency` 만큼 차면 나머지 target 기아 | M |
| D-16 | R6 | `DeployJdbcStorePort.java:592-596` | RowMapper 안에서 `summarizeTargets(deployKey)` 2차 쿼리(열린 ResultSet 위 중첩 쿼리). 반증: PostgreSQL JDBC 는 기본 전체 fetch 라 동작함 → 드라이버 의존 품질 이슈로 강등 | S |
| D-17 | R4 | `DeployServiceCommandSupport.java:1052-1056` | `catch (Exception)` 후 cause 유실 + 내부 역직렬화 오류를 400(사용자 잘못)으로 오분류. 같은 계층 `DeployJdbcStoreSupport:440,448` 은 cause 전달 — 일관성 파괴 | S |

### 4.3 반증으로 기각한 축 D 후보 (투명성 기록 — 7건)

| 후보 | 기각 사유 (직접 확인) |
|---|---|
| `DeployService.startDeploy:496-524` 상태 check-then-act(READY 이중 실행) | `DeployExecutor:93 registerTargetExecution(executionKey, ttl)` 가 Redis 로 target 실행을 dedup 하고 `awaitTargetLease` 가 eqpId 를 직렬화 → 중복 dispatch 무해. **관찰**로 강등 |
| `DeployPlanningService.calculateDeltaKinds:416` `currentState.eqpIp()` 무검증 역참조 | `validateCurrentStatePrecondition:293` 이 EQP_UPDATE 계열에서 `currentStateOptional.isEmpty()` 를 먼저 던짐. EQP_CREATE 분기는 currentState 미사용 → 성립 안 함 |
| Kafka publish 타임아웃/재시도 부재 | `UiGatewayEventKafkaPublisher:159-160` `send().get(publishTimeoutSeconds, SECONDS)` + Timeout/Execution/Interrupted 3분기 처리 존재 → 성립 안 함 |
| Kafka consumer 예외 삼킴 | `UiCommandKafkaSubscriber:174-192` 인프라 예외는 재전파(컨테이너 재시도), 비즈니스 예외만 ACK-skip + ERROR 로그 + 메트릭 → 의도된 정책 |
| `tc_deploy` 비정규화 카운터 stale | `findDeploySummary`/`findDeployHistory` 가 LATERAL 로 라이브 재집계하고 저장 컬럼을 읽지 않음 → 사용자 영향 없음 |
| `JdbcAuditLogPort:50` / `LogoutUseCase:73` / `ValidateTokenUseCase:191` silent catch | 세 곳 모두 주석 + 메트릭(`recordLogoutCacheEvictFailure`, `recordLastSeenUpdateFailure`) + ERROR 로그가 있는 명시적 best-effort 설계 |
| `DemoRunConnectionGate:209/238/248` catch 후 진행 | javadoc 에 best-effort 계약 명시, run 종료를 막지 않는 것이 요구사항 |

---

## §5 축 E — 보안 (레포 중점: 인증·인가 게이팅)

### 5.1 인가 경로 커버리지 — **미게이팅 엔드포인트 0건**

> ui-backend 는 캠페인에서 **HTTP 표면이 실재하는 첫 레포**다(common/gateway/business 는 REST 0).
> 따라서 P1 을 선험적으로 배제하지 않고 108개 엔드포인트를 전수 대조했다.

**엔드포인트 총계: 108** (`@RestController` 20개, `@Controller` 0). 컨트롤러별:
EqpController 15 / DeployController 12 / GroupController 9 / UserController 9 / LiveMapController 9 /
ModelBranchController 7 / DlqController 6 / EqpParamController 6 / LiveMapLayoutController 6 /
ModelFlowController 5 / PermissionController 5 / AuthController 4 / DeployScenarioAdminController 3 /
ModelMutationController 3 / ModelQueryController 3 / ModelDetailController 2 /
AsyncResultController 1 / CommLogController 1 / GatewayShardLeaseController 1 / LineController 1

**인가 강제 경로 (전수 — 이 4개가 전부다)**

| # | 경로 | 위치 | 적용 대상 |
|---|---|---|---|
| 1 | `SecurityFilterChain` `permitAll` 목록 | `UiSecurityConfig.java:121-139` | `/error` + ERROR 디스패치, `POST /api/auth/login`, `GET /api/auth/csrf`, `GET /api/actuator/health(/**)`, `OPTIONS /**` |
| 2 | `ActuatorAccessAuthorizationManager` (IP CIDR **AND** 토큰 인증) | `UiSecurityConfig.java:133-137` → `ActuatorAccessAuthorizationManager.java:54-86` | `GET /api/actuator/{prometheus,info,metrics,metrics/**}` |
| 3 | `anyRequest().access(...)` → `UiApiPermissionCache.isAuthorized` (DB 기반 closed-by-default) | `UiSecurityConfig.java:141`, `:249-282` → `UiApiPermissionCache.java:130-163` | **나머지 전부** |
| 4 | 컨트롤러 내부 분기 | `AuthController.java:154,163,171,186`(logout), `:214,222`(me) | 2개 엔드포인트만 |

- **`@PreAuthorize`/`@Secured`/`@RolesAllowed` 는 전 레포 0건**(grep 실측). 인가는 전량 경로 3에 위임된다.
- **추가 `SecurityFilterChain` bean 0개, `WebMvcConfigurer` 0개, `HandlerInterceptor` 0개**(grep 전수) — 우회 체인 없음.
- 그 외 컨트롤러의 `UserPrincipal` 참조는 전부 *actor 문자열 해석*용이며 권한 분기가 아니다
  (`DeployController.java:351-361`, `DeployScenarioAdminController.java:132-142`, `EqpParamController.java:250-263`,
  `ModelControllerRequestSupport.java:192-196`, `EqpControllerCommandSupport.java:68-72`).

**closed-by-default 실증** (`UiApiPermissionCache.java:142-147`)
```java
if (matching.isEmpty()) {
    // Closed by default: 매핑된 권한이 없으면 차단
    log.warn("API 권한 미등록 경로 차단. uri={}, method={}, userPk={}", requestUri, httpMethod, principal.userPk());
    return false;
}
```
기동 로드 실패 시 failsafe(`:107-116, 131-135`)도 전면 차단이다("로드 실패 시 전체 허용" 반대 방향).

**권한 행 ↔ 엔드포인트 전수 대조 결과** — 미게이팅(= 어느 경로에도 안 걸리는) 엔드포인트 **0건**.
git-tracked 시드/패치 SQL 에서 추출한 API 권한 행이 108개 엔드포인트를 전부 덮는다(§5.2 표).

> **[검증 보정] 108 의 분할식 정정 (S09 완결성 게이트)** — 원문의 `permitAll 5개 + actuator 4개 + 권한행 매칭 99개 = 108` 은
> **집합이 섞인 오류**다. `permitAll` 5개 중 `/error`·ERROR 디스패치·`GET /api/actuator/health(/**)`·`OPTIONS /**` 와
> actuator 4개는 **컨트롤러 엔드포인트가 아니라서 애초에 108 에 포함되지 않는다**(108 = `@RestController` 20개의 핸들러 메서드 수).
> 재계산한 올바른 분할은 다음과 같다.
>
> | 구분 | 개수 | 근거 |
> |---|---|---|
> | `permitAll` (108 안) | **2** | `POST /api/auth/login`(`UiSecurityConfig.java:124`), `GET /api/auth/csrf`(`:125`) — `AuthController.java:96,255` |
> | `anyRequest().access()` → DB 권한행 매칭 | **106** | 나머지 전부 |
> | **합계** | **108** | — |
>
> `permitAll` 의 나머지 3항목(`/error`+ERROR 디스패치 / `GET /api/actuator/health(/**)` / `OPTIONS /**`)과
> actuator 4항목(`prometheus`/`info`/`metrics`/`metrics/**`)은 **108 밖의 별도 표면**이며 §5.1 의 경로 1·2 표에 그대로 유효하다.
> 컨트롤러 내부 분기 2개(`AuthController` logout/me)는 별도 버킷이 아니라 **권한행 매칭 106 에 포함**된 뒤 추가로 적용되는 2차 분기다
> (`AUTH_LOGOUT_PERM`/`AUTH_ME_PERM` 행이 실재 — §5.2).
> **정정 후에도 결론(미게이팅 0건 = P1 0건)은 그대로다.** 오히려 권한행이 덮는 범위가 99 → 106 으로 넓어진다.

**전제 재확인 (프롬프트 제시 서술의 검증 결과)**

| 전제 | 판정 | 근거 |
|---|---|---|
| ui RBAC 는 2레이어(사용자→그룹→권한) | **참** | `tc_user_group_member` + `tc_user_group_permission` 실재, `UserPrincipal.permissionCodes` 로 flatten |
| DB 가 source of truth | **참** | `UiApiPermissionCache` 가 `tc_ui_permission` 을 기동 로드 + 5분 주기 갱신(`:87-90`). 코드에 권한 하드코딩 0 |
| 백엔드가 closed-by-default 로 강제 | **참** | `UiApiPermissionCache.java:142-147` (위 인용) |
| 신규 권한은 ADMIN 행 명시 INSERT 필요 | **부분 정정** | 시드는 그 반대다 — `postgres_insert_sample_data.sql:809-820` 이 ADMIN 에 `p.is_active = TRUE` 인 **전 권한을 자동 조인 부여**한다("새 permission seed가 추가되더라도 ADMIN은 별도 목록 수정 없이 모든 활성 권한을 자동으로 받습니다"). 다만 이는 **시드 재실행 시**에만 적용되고, 운영 중 patch SQL 로 권한을 추가할 때는 각 patch 가 ADMIN 매핑 INSERT 를 명시해야 한다(예: `postgres_2026_06_22_...:58`). 즉 "ADMIN 명시 INSERT 필요"는 **patch 경로에 한해 참**이다 |
| 라이브 DB 권한이 시드와 drift | **미확인** | 본 감사는 git-tracked SQL 만 대조했다. 라이브 `tc_ui_permission`/`tc_user_group_permission` 조회는 수행하지 않았다 |

### 5.2 API 권한 인벤토리 (git-tracked SQL 전수 — **29 permCode**)

> **[검증 보정]** 원문 제목은 "30행"이었으나 아래 표가 실제로 열거하는 permCode 는 **29개**다
> (`_READ / _WRITE` 결합 표기 3쌍을 2개씩 계산한 값). 시드(`postgres_insert_sample_data.sql:761-787`)의
> `resource_type='API'` 행 22개 + 패치 7개 = 29 로 일치한다. 같은 블록의 `DASH_READ`(`:778`)는
> `resource_type='PAGE'` 라 API 인가 대조 대상이 아니다.
> **라이브 DB 의 실제 행 수는 여전히 미확인**(§5.1 전제 표) — 이 29 는 git-tracked SQL 기준값이다.

| permCode | matchType | resource | httpMethod | 출처 |
|---|---|---|---|---|
| `AUTH_ME_PERM` | EXACT | `/api/auth/me` | GET | seed:761 |
| `AUTH_LOGOUT_PERM` | EXACT | `/api/auth/logout` | POST | seed:762 |
| `EQP_READ` | PREFIX | `/api/eqp` | GET | seed:763 |
| `EQP_WRITE` | PREFIX | `/api/eqp` | (NULL=전 메서드) | seed:766 |
| `ASYNC_READ` | PREFIX | `/api/async` | GET | seed:767 |
| `DLQ_READ` | PREFIX | `/api/dlq` | GET | seed:768 |
| `DLQ_DELETE` | PREFIX | `/api/dlq` | DELETE | seed:769 |
| `MODEL_READ` | PREFIX | `/api/model` | GET | seed:770 |
| `MODEL_WRITE` | PREFIX | `/api/model` | (NULL) | seed:773 |
| `LIVE_MAP_READ` | EXACT | `/api/live-map` | GET | seed:774 |
| `LIVE_MAP_DEMO_SCENARIO_READ` | EXACT | `/api/live-map/demo-scenarios` | GET | seed:775 |
| `LIVE_MAP_DEMO_JOB_READ` / `_WRITE` | PREFIX | `/api/live-map/demo-jobs` | GET / POST | seed:776-777 |
| `DEPLOY_READ` | PREFIX | `/api/deploys` | GET | seed:779 |
| `DEPLOY_WRITE` | EXACT | `/api/deploys` | POST | seed:780 |
| `DEPLOY_PREVIEW` | EXACT | `/api/deploys/register/scenario-preview` | POST | seed:781 |
| `DEPLOY_ACTION` | REGEX | `^/api/deploys/[0-9]+/(start\|hold\|cancel\|redeploy)$` | POST | seed:782 |
| `DEPLOY_DELETE` | REGEX | `^/api/deploys/[0-9]+$` | DELETE | seed:783 |
| `DEPLOY_ADMIN` | PREFIX | `/api/admin/deploy-scenarios` | (NULL) | seed:784 |
| `USER_INFO_WRITE` | PREFIX | `/api/user` | (NULL) | seed:785 |
| `GROUP_WRITE` | PREFIX | `/api/group` | (NULL) | seed:786 |
| `PERMISSION_WRITE` | PREFIX | `/api/permission` | (NULL) | seed:787 |
| `COMM_LOG_READ` | PREFIX | `/api/comm-logs` | GET | patch 2026-05-07 |
| `LINE_READ` | EXACT | `/api/line` | GET | patch 2026-06-02 |
| `LIVE_MAP_LAYOUT_READ` / `_WRITE` | PREFIX | `/api/live-map/layout` | GET / (NULL) | patch 2026-06-02 |
| `LIVE_MAP_DEMO_RUN_READ` / `_WRITE` | PREFIX | `/api/live-map/demo-runs` | GET / POST | patch 2026-06-16 |
| `LIVE_MAP_DEMO_RUN_SCENARIO_DELETE` | PREFIX | `/api/live-map/demo-runs/scenarios` | DELETE | patch 2026-06-22 |

> seed = `nori-tc-exec\docs\db_table\sample_data\postgres_insert_sample_data.sql`
> patch = `nori-tc-exec\docs\db_table\patch\postgres_*.sql`

### 5.3 발견 항목

**E-1 / R8 / F / P2 — actuator IP 화이트리스트가 프록시 뒤에서 무력화 + permCode 미검사**

위치 ① `ui/adapter/tc-ui-web-adapter/.../security/ActuatorAccessAuthorizationManager.java:59-85`
```java
final String remoteAddr = context.getRequest().getRemoteAddr();       // :59  프록시 IP
if (!isIpAllowed(remoteAddr)) { return new AuthorizationDecision(false); }
...
if (!(authentication.getPrincipal() instanceof UserPrincipal)) { ... }
return new AuthorizationDecision(true);                                // :85  permCode 검사 없음
```
위치 ② `ui/tc-ui-core/.../properties/UiActuatorSecurityProperties.java:38-44` — 미설정 시 기본 CIDR
```java
private static final List<String> DEFAULT_ALLOWED_CIDRS = List.of(
        "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "127.0.0.1/32", "::1/128");
```
위치 ③ `nori-tc-exec\deploy\docker\nginx.conf:27-31`
```
location /api/ {
    proxy_pass http://tc-ui-backend.mes.svc.cluster.local:8080;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
```
**문제** ui-backend 앞단은 ui-front pod 의 nginx 프록시다(`deploy/helm/tc-ui-backend/templates/` 에는 Service 만 있고
HTTPRoute 는 `tc-ui-front` 에만 존재 — 직접 확인). `server.forward-headers-strategy` 는 ui-backend·exec 어디에도 설정돼 있지 않으므로
(grep 0건) Spring 은 소켓 원격 주소 = **nginx pod IP(클러스터 CIDR 10.x)** 를 보고, 이는 기본 허용 대역에 항상 포함된다.
`tc.ui.backend.actuator.allowed-cidrs` 는 `tc-ui-backend.properties`(162줄) 에 없어 기본값이 적용된다.
따라서 IP 화이트리스트는 공개 경로로 들어오는 모든 요청에 대해 **무조건 통과**하고, 남는 통제는 "인증된 `UserPrincipal`"뿐이며
**permCode 검사가 없다**.
**실패 시나리오** OPERATOR 등 최소 권한 계정으로 로그인한 사용자가 인터넷에서
`GET https://tc.noricloud.org/api/actuator/prometheus` 를 호출해 전체 메트릭(설비 ID·연결 수·토폴로지·JVM 상태)을 열람한다.
노출 endpoint 는 `health,info,prometheus`(`nori-tc-exec\apps\tc-ui-backend-app\src\main\resources\application.yaml:64-65`).
**반증 시도** ① `management.server.port` 분리로 별도 포트에만 노출되는지 → exec 전체 grep 0건, 앱 포트 공유. ②
`exposure.include` 에 `metrics` 가 없어 `/api/actuator/metrics` 는 404 → prometheus/info 만 실 노출(영향 축소하나 제거 아님).
③ nginx 가 `/api/actuator` 를 차단하는지 → `location /api/` 하나뿐, 별도 deny 없음. **반증 실패.**
**심각도** **P2** (인증 필요 = 무선행조건 아님. 쓰기 불가, 자격증명 노출 아님 → P1 아님) · **난이도** S
(`forward-headers-strategy=framework` + `allowed-cidrs` 실 클라이언트 기준 재설정, 또는 actuator 에도 전용 permCode 부여)
**다운스트림** nori-tc-exec(config/helm), nori-tc-gateway·nori-tc-business (동일 `/api/actuator` base-path 규약 — 동종 여부 미확인)

**E-2 / R8 / F / P2 — RBAC 권한 상승 경로: 매핑 API 가 엔티티 CRUD 와 같은 permCode 로 묶임**

위치 ① `ui/adapter/tc-ui-web-adapter/.../application/admin/GroupAdminFacade.java:97-104`
```java
public GroupPermissionMappingResponse addPermissionMapping(final long groupId, final long permId) {
    final UiUserGroupPermission saved = groupPermissionMappingPort.upsert(
            assembler.toPermissionMappingCommand(groupId, permId, DEFAULT_GRANTED_BY)
    );
    auditLogPort.record(UiAuditEvent.permissionGrant(currentActor(), permissionTarget(groupId, permId)));
    return assembler.toGroupPermissionMappingResponse(saved);
}
```
위치 ② 권한 행 `GROUP_WRITE` = PREFIX `/api/group`, httpMethod **NULL(전 메서드)** — §5.2
**문제** `GROUP_WRITE` 하나가 `/api/group` 아래 9개 엔드포인트를 전부 덮는다. 여기에는 그룹 CRUD 뿐 아니라
`POST /api/group/{groupId}/permission/{permId}`(`GroupController.java:213`) = **임의 그룹에 임의 권한 부여**가 포함된다.
서버측 가드가 전무하다 — `GroupAdminFacade` 에는 "자기 그룹 금지"·"ADMIN 전용 권한 보호"·"self-escalation 차단" 로직이 없고
감사 로그만 남긴다(grep `ADMIN|self|본인|guard|forbid` → 0건).
동일 구조가 `USER_INFO_WRITE`(PREFIX `/api/user`, NULL)에도 있다 — `POST /api/user/{userPk}/group/{groupId}`(`UserController.java:182`)로
**임의 사용자를 임의 그룹에 편입**할 수 있고, `POST /api/user/{userPk}/password/reset`(`:254`)로 **임의 사용자의 비밀번호를 재설정**할 수 있다.
**실패 시나리오** 관리자가 "그룹 관리만 시키려고" 신설 그룹에 `GROUP_WRITE` 를 부여하면, 그 그룹 구성원이
자기 그룹에 `PERMISSION_WRITE`·`USER_INFO_WRITE`·`DEPLOY_ADMIN` 을 스스로 부여해 **사실상 ADMIN 으로 승격**한다.
또는 `USER_INFO_WRITE` 보유자가 자신을 ADMIN 그룹에 편입하거나 admin 계정 비밀번호를 재설정한다.
**반증 시도** ① 시드 매핑이 이를 막는지 → 막는다. `postgres_insert_sample_data.sql:823-836` 의 DEVELOPER 는
`USER_INFO_WRITE`/`GROUP_WRITE`/`PERMISSION_WRITE` 를 **명시 제외**하고, OPERATOR 는 조회 중심 11개만 받는다.
즉 **시드 상태에서는 이 3개 권한이 ADMIN 전용이라 현시점 승격 경로가 열려 있지 않다.**
② 그러나 ui-front 에 그룹 권한 self-serve 편집 UI(`GroupPermissionsModal`)가 존재해 운영자가 임의 그룹을 만들고
이 permCode 들을 배분할 수 있으므로, "설정 실수 1회 = 즉시 ADMIN 승격"이라는 구조적 위험은 남는다. **반증 부분 성립 → P2 유지(P1 아님).**
**심각도** **P2** (선행조건: 해당 permCode 가 ADMIN 외 그룹에 부여됨) · **난이도** M
(permCode 를 `GROUP_READ`/`GROUP_WRITE`/`GROUP_PERMISSION_GRANT` 로 분리 + 서버측 self-escalation 가드)
**다운스트림** nori-tc-ui-front (권한 상수·게이팅), nori-tc-exec (권한 시드 patch), **nori-wm-ui-backend**
(`tc_user_group_permission` 등 RBAC 7종 공유 — §6.3)

**E-3 / R8 / F / P3 — PREFIX 매칭에 경로 경계 검사 없음**

위치 `ui/adapter/tc-ui-web-adapter/.../security/UiApiPermissionCache.java:180-182`
```java
if (perm.matchType() == UiPermissionMatchType.PREFIX) {
    return requestUri.startsWith(perm.resource());
}
```
**문제** `/` 경계를 요구하지 않아 `/api/eqp` PREFIX 가 `/api/eqp-admin`, `/api/eqpanything` 도 매칭한다.
**반증 시도** 현시점 실제 충돌 확인 → 108개 엔드포인트 base path 전수(`/api/async, /api/auth, /api/comm-logs, /api/deploys,
/api/admin/deploy-scenarios, /api/dlq, /api/eqp, /api/eqp/gateway-shard-leases, /api/group, /api/line, /api/live-map,
/api/live-map/layout, /api/model, /api/permission, /api/user`) 를 §5.2 의 22개 PREFIX resource 와 대조한 결과
**도메인을 넘는 over-grant 는 0건**이다(모든 PREFIX 하위 경로가 같은 도메인). **반증 성립 → 잠재 위험만 남음.**
**심각도** P3(잠재) · **난이도** S(`equals(resource) || startsWith(resource + "/")`) · **다운스트림** 없음
**주의** 라이브 DB 의 PREFIX 행이 시드와 다르면 이 반증은 무효다(§5.1 미확인 항목).

**E-4 / R8 / F / P3 — CSRF BREACH 보호 비활성**

위치 `ui/adapter/tc-ui-web-adapter/.../security/UiSecurityConfig.java:95-103`
```java
.csrf(csrf -> csrf
        .csrfTokenRepository(csrfTokenRepository)
        /*
         * Spring Security 7 기본 핸들러(XOR)는 헤더 토큰을 마스킹 포맷으로 기대합니다.
         * 본 UI는 쿠키(XSRF-TOKEN)의 원문 토큰을 그대로 헤더(X-XSRF-TOKEN)로 재전송하므로
         * raw 토큰 비교가 가능한 RequestAttributeHandler를 명시적으로 사용합니다.
         */
        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
)
```
**문제** Spring Security 기본값인 `XorCsrfTokenRequestAttributeHandler`(요청마다 토큰 마스킹 → BREACH 압축 사이드채널 완화)를
`CsrfTokenRequestAttributeHandler`(raw 토큰)로 명시 교체했다. CSRF 방어 자체는 유효하나 BREACH 완화가 빠진다.
**실패 시나리오** 응답 압축 + 공격자 제어 반사 콘텐츠가 동시에 성립하면 BREACH 로 CSRF 토큰을 추출할 수 있다.
**반증 시도** 두 선행조건 확인 → 압축/반사 콘텐츠 존재 여부는 **미확인**(nginx `gzip` 설정 미조사). 결정적 반증도 확증도 못 함.
**심각도** P3(선행조건 2개, 미확인) · **난이도** S(프런트를 XOR 포맷에 맞추면 기본값 복귀 — ui-front 동시 변경 필요)
**다운스트림** nori-tc-ui-front (토큰 재전송 로직)

**E-5 / R8 / F / P3 — 운영 DEBUG 로깅 + 세션 토큰 접두 8자 기록**

위치 ① `nori-tc-exec\apps\tc-ui-backend-app\src\main\resources\application.yaml:53` → `com.nori.tc: DEBUG`
위치 ② `ui/tc-ui-core/.../usecase/ValidateTokenUseCase.java:91`
```java
log.debug("토큰 캐시 미스 - DB 조회 시작. token={}...", token.substring(0, Math.min(8, token.length())));
```
위치 ③ `ui/adapter/tc-ui-db-adapter/.../JpaSessionPort.java:58,65,86,101,103,117` — `UiAuthTokenFingerprint.mask(...)`
**문제** 운영 프로파일에서 `com.nori.tc` 가 DEBUG 라 위 로그가 실제로 기록된다. 토큰 접두 8자가 로그에 남는다.
`ValidateTokenUseCase:91` 은 공용 `UiAuthTokenFingerprint.mask` 대신 인라인 `substring` 을 써 마스킹 방식이 이원화되어 있다.
**반증 시도** 토큰 위조 가능성 확인 → 토큰은 `SecureRandom` 64자 alphanumeric(`LoginUseCase.java:53-54,161`)이고
DB 저장은 SHA-256 hex(`JpaSessionPort.java:87,102,120` + `UiAuthSessionDbMapper.java:24`), Redis 키도
`tc:ui:backend:session:{sha256(token)}`. 8자 접두(62^8)로는 나머지 56자를 복원할 수 없다. **위조 위험은 반증 성립.**
남는 것은 로그 기반 세션 상관추적 가능성 + 마스킹 규약 이원화.
**심각도** P3 · **난이도** S · **다운스트림** nori-tc-exec(로그 레벨 config)

**E-6 / R8 / F / P3 — 내부 HTTP 호출 URI 미인코딩**

위치 `ui/adapter/tc-ui-web-adapter/.../simulator/HttpSimulatorOrchestratorClient.java:62,72-75,86,105`
```java
return sendJson("POST", "/api/demo-runs/" + runId + "/targets/" + eqpId + "/stop", ...);   // :62
final String path = mode == null || mode.isBlank() ? "/api/scenarios" : "/api/scenarios?mode=" + mode;   // :72-75
exchange("DELETE", "/api/scenarios/" + scenarioId, null);                                  // :86
...
.uri(URI.create(properties.getBaseUrl() + path))                                           // :105
```
**문제** 사용자 제어 `runId`/`eqpId`/`scenarioId`/`mode` 를 URL 인코딩 없이 내부 시뮬레이터 API URI 에 연결한다.
특히 `mode` 는 쿼리 문자열에 직접 들어가 `&`/`#` 주입이 가능하다(HTTP parameter injection).
**실패 시나리오** `GET /api/live-map/demo-runs/scenarios?mode=x%26foo=bar` → 내부 시뮬레이터 `/api/scenarios?mode=x&foo=bar` 호출.
**반증 시도** 경로 traversal 가능성 확인 → `@PathVariable` 세그먼트에 `/` 를 넣으려면 `%2F` 가 필요한데
Spring Security 기본 `StrictHttpFirewall` 이 인코딩 슬래시·`..`·`;` 를 400 으로 거부한다
(`HttpFirewall`/`StrictHttpFirewall` 커스터마이즈 grep 0건 → 기본값 유효). **traversal 은 반증 성립**, 쿼리 주입만 남는다.
영향 범위는 내부 시뮬레이터 API 로 한정된다.
**심각도** P3 · **난이도** S(`URLEncoder`/`UriComponentsBuilder`) · **다운스트림** nori-tc-simulator

### 5.4 축 E 0건 항목 — 실행 명령·검색 범위 명시

| 항목 | 결과 | 검증 방법 |
|---|---|---|
| SQL 인젝션 | **0건** | `grep -rn 'jdbcTemplate\.(query\|update\|queryFor)[a-zA-Z]*(\s*"[^"]*"\s*+' --include=*.java ui/adapter/tc-ui-db-adapter/src/main` → 0. `grep -rn 'sql\.append(' ... \| grep '+'` → 0. 동적 `ORDER BY`/정렬 컬럼 삽입 grep → 0. 전 쿼리가 `NamedParameterJdbcTemplate` 바인딩 파라미터 사용 |
| 하드코딩 시크릿 | **0건** | `grep -rniE '(password\|secret\|apikey\|api_key\|token)\s*=\s*"[^"]{6,}"' --include=*.java ui/*/src/main ui/*/*/src/main` → 0. 자격증명은 전부 `nori-tc-exec` config / k8s Secret 경유 |
| 안전하지 않은 역직렬화 | **0건** | `JdkSerializationRedisSerializer` 전 레포 grep → 0. Redis 전 경로 JSON(§6.4). Kafka 는 String/String deserializer(`UiKafkaConfiguration.java:59-79`) |
| 명령 인젝션 | **0건** | `Runtime.getRuntime().exec`, `ProcessBuilder` grep → 0 |
| 경로 traversal(파일 쓰기) | **0건** | 시나리오 업로드는 로컬 파일 쓰기가 아니라 시뮬레이터 HTTP 프록시(`LiveMapSimulatorRunFacade.java:254-260` → `HttpSimulatorOrchestratorClient.java:79`). ui-backend 에 사용자 입력 기반 `Files.write`/`new File(userInput)` 0건 |
| 세션 토큰 평문 저장 | **0건** | DB `tc_ui_auth_session.token` 은 SHA-256 hex(`UiAuthSessionDbMapper.java:24`, `JpaSessionPort.java:87,102,120`). Redis 키도 `sha256Hex`(`UiSessionCacheService.java:185`) |
| 과도한 권한 기본값 | **0건** | 권한 캐시 로드 실패 시 전면 차단(`UiApiPermissionCache.java:107-116,131-135`). CORS `cors-allowed-origins=` 는 운영 config 에서 **빈 값**(`tc-ui-backend.properties:147`) → same-origin 만 허용 |

### 5.5 인가 게이팅 자동 검증 자산 (기존 안전망 — 보존 대상)

`src/test` 에 인가 게이팅 전용 테스트가 8개 존재한다. R 트랙 실행 시 회귀 기준선으로 사용한다.

```
ui/adapter/tc-ui-web-adapter/src/test/.../security/UiApiPermissionCacheTest.java
ui/adapter/tc-ui-web-adapter/src/test/.../security/UiApiPermissionGatingTest.java
ui/adapter/tc-ui-web-adapter/src/test/.../security/ActuatorAccessAuthorizationManagerTest.java
ui/adapter/tc-ui-web-adapter/src/test/.../security/EqpPollingGroupPermissionTest.java
ui/adapter/tc-ui-web-adapter/src/test/.../security/LiveMapLayoutPermissionTest.java
ui/adapter/tc-ui-web-adapter/src/test/.../security/LiveMapScenarioPermissionTest.java
ui/adapter/tc-ui-web-adapter/src/test/.../security/LiveMapDemoRunListPermissionTest.java
ui/adapter/tc-ui-web-adapter/src/test/.../controller/DlqControllerSecurityTest.java
```
`UiApiPermissionGatingTest.java:88-` 은 `unmappedUriIsDeniedByClosedDefaultEvenWithAllPermissions` 로
closed-by-default 를 명시 검증한다.
**공백**: `UiSecurityConfig` 의 `permitAll` 목록 자체를 고정하는 테스트는 없다(향후 `permitAll` 추가가 조용히 통과함) — Phase 4 게이트 후보.

### 5.6 세션/토큰 처리 (`tc_ui_auth_session`)

| 항목 | 구현 | 위치 |
|---|---|---|
| 토큰 생성 | `SecureRandom` 64자 alphanumeric | `LoginUseCase.java:53-54, 161` |
| DB 저장 | SHA-256 hex fingerprint(평문 미저장) | `UiAuthSessionDbMapper.java:24`, `UiAuthTokenFingerprint.java:26-34` |
| 전달 | HttpOnly 쿠키(`TC_UI_AUTH`), `Secure=true`, `SameSite=None` | `tc-ui-backend.properties:121-138`, `UiAuthCookieFactory` |
| 세션 정책 | STATELESS(서버 HttpSession 미사용) | `UiSecurityConfig.java:111-112` |
| 캐시 | Redis `tc:ui:backend:session:{sha256(token)}`, TTL 기본 300s | `UiSessionCacheService.java:40,137,185` |
| 폐기 | `revokeByToken(sha256)` + 삭제 쿠키 | `JpaSessionPort.java:100-104`, `AuthController.java:152-190` |
| 만료 토큰 처리 | 즉시 401 대신 익명 통과 → 인가 단계에서 401(만료 쿠키로 로그인 자체가 막히는 것 방지) | `UiTokenAuthenticationFilter.java:185-195` |
| 비밀번호 | BCrypt(`BcryptPasswordVerifier`), 90일 만료 판정, 이력 `tc_user_password_history` | `LoginUseCase.java:113`, `JdbcPasswordHistoryPort` |

설계상 문제 없음. **0건.**

### 5.7 의존성 CVE 스캔 — **완료(실측)**

실행: `./gradlew dependencyCheckAggregate -PuseLocalMaven` (cwd = `c:\workspace_nori\nori-tc-ui-backend`, 로그 리다이렉트 — 파이프 미사용)

```
FAILURE: Build failed with an exception.

* What went wrong:
Execution failed for task ':dependencyCheckAggregate'.
>
  Dependency-Analyze Failure:
  One or more dependencies were identified with vulnerabilities that have a CVSS score greater than '7.0': CVE-2026-46340, CVE-2026-41293, ...
  See the dependency-check report for more details.

BUILD FAILED in 1m 5s
5 actionable tasks: 1 executed, 4 up-to-date
```

**`BUILD FAILED` 는 스캔 실패가 아니라 `SecurityScanConventionsPlugin` 의 CVSS>7.0 게이트가 정상 작동한 결과다**
(S06 gateway 와 동일 동작). 분석 자체는 1분 5초에 완주했다 — S06 이 4시간 55분 걸린 것과 달리 NVD 캐시가 이미 예열돼 있었다.

| 지표 | 값 | 산출 명령 |
|---|---|---|
| 리포트 전체 고유 CVE | **138** | `grep -o "CVE-[0-9]\{4\}-[0-9]*" build/reports/dependency-check-report.html \| sort -u \| wc -l` |
| **CVSS > 7.0 (게이트 트립)** | **74** | 로그 103행의 CVE 목록 `sort -u \| wc -l` |
| 리포트 경로 | `build/reports/dependency-check-report.html` | — |

취약 컴포넌트(발췌 — 리포트 상 jar): `jackson-databind-2.20.2`, `jackson-databind-3.0.4`, `jackson-core-2.20.2`/`3.0.4`,
`kafka-clients-4.1.1`, `netty-*-4.2.9.Final`, `lettuce-core-6.8.2.RELEASE`, `logback-classic/core-1.5.25`,
`commons-lang3-3.19.0`, `hibernate-validator-9.0.1.Final`, `micrometer-*-1.16.2`, `log4j-api/to-slf4j-2.25.3`, `asm-9.8` 외.

**본 감사는 목록화까지만 수행한다**(방법론 규율 — 업그레이드는 F 트랙).
74건은 Phase 2 백로그에서 F 트랙으로 승격한다.
> 주의: gateway S06 의 "2265 취약점"과 본 수치는 **집계 단위가 다르다**(S06 = 의존성별 취약점 인스턴스 합, 본 감사 = 고유 CVE 수).
> 직접 비교하지 말 것. 두 레포 공통 의존성이 대부분이므로 S14 에서 CVE 단위로 병합하는 것이 옳다.

---

## §6 축 F — 계약 표면 인벤토리 (목록화)

### 6.1 Kafka 계약 — 3 토픽 (produce 2 / consume 1), 메시지 DTO 전부 common 소유

| 방향 | 토픽 (설정 키) | 메시지 DTO (FQCN) | DTO 소유 | ui-backend 위치 | 상대편 레포 (실증) |
|---|---|---|---|---|---|
| produce | `tc.ui.events.gateway` (`tc.ui.backend.kafka.gateway-events-topic`) | `com.nori.tc.messaging.kafka.contract.KafkaUiTaskMessage` | **nori-tc-common** | `UiGatewayEventKafkaPublisher.java:133,150,159` (key=eqpId, **명시 partition = routePartition**) | **nori-tc-gateway** — `GatewayUiEventKafkaSubscriber.java:43,136` (`TopicPartition` ASSIGN) |
| produce | `tc.ui.events.business` (`...business-events-topic`) | `KafkaUiTaskMessage` | **nori-tc-common** | `UiBusinessEventKafkaPublisher.java:75,92,101` (key=eqpId, partition 미지정) | **nori-tc-business** — `BusinessUiEventKafkaSubscriber.java:35-36` (`@KafkaListener`) |
| consume | `tc.ui.commands` (`...commands-topic`) | `com.nori.tc.messaging.kafka.contract.KafkaUiTaskReplyMessage` | **nori-tc-common** | `UiCommandKafkaSubscriber.java:104-108,140-141` (`groupId=tc-ui-backend-group`, `AckMode.MANUAL_IMMEDIATE`) | **nori-tc-gateway** `GatewayUiCommandKafkaPublisher.java:113,146` / **nori-tc-business** `BusinessUiTaskReplyPublisher.java:74,124` |

- 내포 계약: `GatewayEquipmentProfileSnapshot`(common) ← `UiKafkaContractMapper.java:14` 가 ui-backend 소유
  `UiEquipmentProfileSnapshot` 을 변환. **B-3 의 계약 접점.**
- 이벤트 타입 enum: `KafkaUiTaskEventType:9-22` (13종), 응답 `KafkaUiTaskReplyEventType:33-` (`*_REP` 13종) — **전부 common 소유**
- 헤더: `KafkaHeaderSupport.addTracingHeaders(...)` — `UiGatewayEventKafkaPublisher.java:151`, `UiBusinessEventKafkaPublisher.java:93`
- **토픽 이름 상수 클래스는 common 에 없다.** `KafkaTopicProperties:9-51` 은 getter 인터페이스일 뿐이고
  ui-backend 는 이를 구현하지 않고 독자 `UiKafkaTopicProperties.java:22`(`@ConfigurationProperties("tc.ui.backend.kafka")`)를 쓴다.
  실제 값은 `nori-tc-exec\apps\tc-ui-backend-app\config\tc-ui-backend.properties:24,28,31`.
- **common consumer lifecycle 미사용** — `AbstractKafkaConsumerLifecycle`/`KafkaConsumerLoop`/`KafkaMessageDispatcher` grep 0건.
  Spring `@KafkaListener` 1개 + 전용 ContainerFactory 만 사용(**business 와 같고 gateway 와 다름**).
- DLT 제거됨(`UiKafkaConfiguration.java:35-37`). 파싱 실패는 WARN + `kafka.command.parse_error` 메트릭 + ACK(`UiCommandKafkaSubscriber.java:217-262`).

### 6.2 외부 노출 REST DTO — 76개 (request 27 / response 49). 소비자 = nori-tc-ui-front

| 카테고리 | request | response | 대표 FQCN |
|---|---|---|---|
| 공통 래퍼 | — | 1 | `...web.dto.response.ApiResponse` |
| Deploy | 3 | 11 | `DeployCreateRequest` / `DeploySummaryResponse` |
| Model | 9 | 11 | `ModelUpsertRequest` / `ModelInfoResponse` |
| Eqp | 7 | 8 | `EqpCreateRequest` / `EqpInfoResponse` |
| RBAC (Group/Permission/User) | 4 | 8 | `GroupUpsertRequest`, `UserPasswordResetRequest` / `GroupInfoResponse`, `PermissionInfoResponse` |
| LiveMap | 3 | 4 | `LiveMapObjectSaveRequest` / `LiveMapResponse` |
| Auth | 1 | 2 | `LoginRequest` / `LoginResponse`, `MeResponse` |
| Async(polling) | — | 2 | `AsyncAcceptResponse`(`record(String traceId)`, `:11`), `AsyncResultResponse` |
| Line / Gateway lease | — | 2 | `LineResponse`, `GatewayShardLeaseResponse` |

공통 래퍼 (`ApiResponse.java:16-21`): `record ApiResponse<T>(boolean success, T data, String errorCode, String errorMsg)`
팩토리 `success(T)` `:30-32`, `error(String,String)` `:45-47`.

ui-front 소비 실증: `nori-tc-ui-front/src/shared/api/*.ts:17`(`payload.success`/`payload.data`),
`src/features/auth/api/auth.api.ts:56-78`(`errorCode`/`errorMsg`), `src/features/eqp/api/eqp.api.ts:661,667,679`(`traceId`),
`src/features/group/components/GroupPermissionsModal.tsx:85,94,104,263`(`permCode`).

**dto 패키지 밖으로 새는 계약(주의)**

| 엔드포인트 | 반환 타입 | 소유 | 위치 |
|---|---|---|---|
| DLQ 목록 3종 | `ApiResponse<List<?>>` — 실체는 common `RedisDlqEntry`/`RedisBusinessDlqEntry`/`RedisQuarantineEntry` | **nori-tc-common** | `DlqController.java:82,130,178` |
| comm-log 조회 | `ApiResponse<List<CommLogEntry>>` (`com.nori.tc.ui.core.commlog.CommLogEntry`) | ui-backend core | `CommLogController.java:36` |

> DLQ 3종은 **common DB 도메인 타입이 REST 응답으로 그대로 노출**된다 — common 변경이 ui-front 를 직접 깬다. Phase 2 계약 항목.

### 6.3 직접 결합 DB 테이블 — 고유 46개

- **ui-backend 에 `@Entity`/`@Table`/MyBatis mapper XML 은 0건.** JPA 엔티티는 전부 nori-tc-common
  (`db/jpa/tc-db-jpa-common-schema/.../entity/**`) 소유이며 ui-backend 는 `com.nori.tc.db.core.**.store.Tc*Store` 포트로만 접근한다.
- 두 갈래: (A) common Store 경유 35 테이블, (B) ui-backend 자체 `JdbcTemplate` raw SQL 14 테이블(중복 3 제외 → 46 고유).

**(A) common Store 경유 (35)** — `tc_eqp`, `tc_eqp_hsms`, `tc_eqp_socket`, `tc_eqp_socket_protocol_type`(R),
`tc_eqp_plc`, `tc_eqp_log`, `tc_eqp_state`, `tc_eqp_state_hist`(R), `tc_eqp_param`, `tc_eqp_param_version`,
`tc_eqp_port_status`, `tc_jar_gateway`(R), `tc_jar_business`(R), `tc_line`(R), `tc_model`, `tc_model_mdf`,
`tc_model_param`, `tc_model_secs_message`, `tc_model_socket_message`, `tc_model_mes_message`, `tc_model_message_flow`,
`tc_model_message_flow_dcop_item`, `tc_model_dcop_item`, `tc_model_eventid`, `tc_model_reportid`, `tc_model_variableid`,
`tc_model_plc_group`, `tc_model_plc_register`, **`tc_user_info`**, **`tc_ui_auth_session`**, **`tc_ui_permission`**,
**`tc_user_group`**, **`tc_user_group_member`**, **`tc_user_group_permission`**, `tc_work`(delete)

**(B) ui-backend raw JDBC (14)**

| 테이블 | R/W | 어댑터 |
|---|---|---|
| `tc_deploy` | RW | `DeployJdbcStorePort`, `DeployJdbcStoreSupport` |
| `tc_deploy_target_state` | RW | `DeployJdbcStorePort`, `DeployPlanningStateJdbcPort` |
| `tc_deploy_target_plan` | RW | 동상 |
| `tc_deploy_target_snapshot` | RW | 동상 |
| `tc_deploy_scenario_catalog_meta` | RW | `DeployScenarioCatalogMetaJdbcPort` |
| `tc_live_map_object` | RW | `LiveMapObjectJdbcStorePort` |
| `tc_live_map_placement` | RW | `LiveMapObjectJdbcStorePort` |
| `tc_live_map_layout` | RW | `LiveMapLayoutJdbcStorePort` |
| `tc_eqp` / `tc_eqp_socket` / `tc_eqp_hsms` (raw 경로) | **R only** | `JpaDeployRegisterQueryPort` (A 와 중복 3) |
| **`tc_user_password_history`** | RW | `JdbcPasswordHistoryPort` |
| **`tc_ui_audit_log`** | W only | `JdbcAuditLogPort` |
| `tc_demo_job` | RW(delete) | `DemoJobJdbcCleanupPort` |

**nori-wm-ui-backend 동시 영향 — RBAC 7종** (프롬프트의 "RBAC 6종" 중 `tc_user` 는 **존재하지 않음** — 실명 `tc_user_info`)

| 테이블 | ui-backend | wm-ui-backend 실증 | 동시 영향 |
|---|---|---|---|
| `tc_ui_auth_session` | RW | `wm-ui-db-adapter/.../JpaSessionPort.java:21,42,46` | **예** |
| `tc_ui_permission` | RW | `.../JpaApiPermissionPort.java:21,51`, `JpaPermissionCrudPort.java:40,44` | **예** |
| `tc_user_group` | RW | `.../JpaGroupCrudPort.java:40,44` | **예** |
| `tc_user_group_member` | RW | `.../JpaUserGroupMappingPort.java:43,47` | **예** |
| `tc_user_group_permission` | RW | `.../JpaGroupPermissionMappingPort.java:41,45` | **예** |
| `tc_user_info` | RW | `.../JpaUserPort.java:18,37,41`, `JpaUserCrudPort.java:52,61,193` | **예** |
| `tc_user_password_history` | RW | `.../JdbcPasswordHistoryPort.java:14,23,29` | **예** |
| `tc_ui_audit_log` | W | wm-ui-backend grep 0건 | 아니오 |

공유 경로 실증: wm-ui-backend 가 **동일 common Store/Upsert 클래스**를 import
(`com.nori.tc.db.core.user.store.{TcUiAuthSessionStore, TcUiPermissionStore, TcUserGroupMemberStore,
TcUserGroupPermissionStore, TcUserGroupStore, TcUserInfoStore}` + `upsert.UpsertTc*` 6종).
> **E-2 의 다운스트림 범위**가 여기까지다 — RBAC 스키마/권한 모델 변경은 nori-wm-* 를 동시에 건드린다(캠페인 Out of Scope 레포).

### 6.4 Redis 값 계약 — 15 (키 패턴 13 + Pub/Sub 채널 2)

**직렬화 설정 (근거)**

| Template bean | Value serializer | 위치 |
|---|---|---|
| `gatewayRedisTemplate` | `RedisSerializer.json()` — **typed(`@class`)** | `UiRedisConfiguration.java:124-132` → `:75-87` |
| `businessRedisTemplate` | `RedisSerializer.json()` — **typed** | `:143-151` |
| `businessStringRedisTemplate` | `StringRedisSerializer` | `:162-171` |
| `commLogRedisTemplate` | `GenericJacksonJsonRedisSerializer(uiRedisObjectMapper)` — **typeless** | `:190-211` (특히 `:202-206`) |
| `uiRedisObjectMapper` | `JsonMapper.builder().findAndAddModules().build()` — **default typing 미활성** | `:62-67` (`:57-58` 보안 주석) |

> **`JdkSerializationRedisSerializer` 는 ui-backend 어댑터 어디에도 없다(0건).** 전 경로 JSON.
> `BusinessDlqRedisService.java:31-34` 와 business 측 `RedisBusinessDlqPublisher.java:23` 의 "JDK 직렬화" javadoc 은 **stale** 이다.

| # | Key 패턴 | 값 타입 | 소유 | 직렬화 | TTL | R/W | 위치 | 상대편 |
|---|---|---|---|---|---|---|---|---|
| 1 | `tc:ui:backend:session:{sha256(token)}` | `RedisUiSessionEntry` | ui-backend | typed | 300s 기본 | RW | `UiSessionCacheService.java:40,137,141,185` | — |
| 2 | `tc:ui:backend:async:{traceId}` | `RedisUiAsyncResultEntry` | ui-backend | typed | 600s 기본 | RW | `AsyncResultStoreService.java:49,254-293` | — |
| 3 | `tc:ui:backend:dual:{traceId}` (Hash) | `UiTaskResult` | ui-backend | typed | `timeoutMs+5000` | RW | `DualResponseRedisAdapter.java:40-55,77-85,196-200` | — |
| 4 | ch. `tc:ui:backend:dual:complete` | traceId 문자열 | ui-backend | — | — | pub/sub | `DualResponseRedisAdapter.java:52,121`; `DualResponsePubSubListener.java:25,34` | — |
| 5 | `tc:ui:backend:deploy-trace:{traceId}` (Hash) | JSON 문자열 | ui-backend | String+수동 JSON | `timeoutMs+5000` | RW | `DeployTraceRedisAdapter.java:33-47,107-132` | — |
| 6 | ch. `tc:ui:backend:deploy-trace:complete` | traceId 문자열 | ui-backend | — | — | pub/sub | `DeployTraceRedisAdapter.java:37,92`; `DeployTracePubSubListener.java:18,27` | — |
| 7 | `tc:ui:backend:deploy:lease:{eqpId}` | `String`(ownerToken) | ui-backend | typed(문자열) | `ttlMs` | RW | `DeployExecutionRedisAdapter.java:23,40-50,66-68` | — |
| 8 | `tc:ui:backend:deploy:execution:{executionKey}` | `String` | ui-backend | typed(문자열) | `ttlMs` | RW | `DeployExecutionRedisAdapter.java:24,81-86` | — |
| 9 | `tc:ui:backend:eqp-repair:tasks` (Hash) | `EqpRepairTask` | ui-backend | typed | 없음 | RW | `RedisEqpRepairTaskStore.java:29,44,55-70` | — |
| 10 | `tc:gateway:comm-log:{eqpId}` (ZSet) | `RedisCommLogEntry` | **common** | **typeless** | 조회 cutoff 1h | **R** | `CommLogRedisQueryAdapter.java:39-59,67-80` | **nori-tc-gateway** `GatewayCommLogRedisAdapter.java:19,26,32,35` |
| 11 | `tc:business:comm-log:{eqpId}` (ZSet) | `RedisCommLogEntry` | **common** | typeless | 동상 | **R** | `CommLogRedisQueryAdapter.java:40,63-64` | **nori-tc-business** `BusinessCommLogRedisAdapter.java:19,26,32,35` |
| 12 | `tc:business:core:dlq:{dlqId}` | `RedisBusinessDlqEntry` | **common** | **소비=typed / 생산=typeless → 불일치(D-18)** | 생산측 설정 | R+Delete | `BusinessDlqRedisService.java:42,70-120` | **nori-tc-business** `RedisBusinessDlqPublisher.java:31,60-84` |
| 13 | `tc:gateway:dlq:{dlqId}` | `RedisDlqEntry` | **common** | 소비=typed / 생산=**미확인** | 미확인 | R+Delete | `GatewayDlqRedisService.java:44,78-91,118` | **nori-tc-gateway** `RedisDlqPublisher.java:27,32` |
| 14 | `tc:gateway:quarantine:{equipmentId}` | `RedisQuarantineEntry` | **common** | 소비=typed / 생산=**미확인** | 미확인 | R | `GatewayDlqRedisService.java:47,146-159` | **nori-tc-gateway** `RedisQuarantineStore.java:25,30` |
| 15 | `{tc.gateway.lease.key-prefix}:{groupId}` | `String`(ownerInstanceId) | — | String | gateway 소유 | **R** | `GatewayShardLeaseRedisAdapter.java:26,34,39,54-57`; `UiGatewayLeaseProperties.java:15,23` | **nori-tc-gateway** |

**미확인**: #13/#14 의 gateway 측 write serializer 설정(동일 mode 불일치가 있으면 D-18 과 같은 결함이 gateway DLQ 조회에도 존재).
S14 에서 확인 항목으로 승계한다.

---

## §7 P1/P2 Top 요약 표

**P1 = 0건.** 근거: §5.1 의 108 엔드포인트 × 4 인가 경로 전수 대조에서 미게이팅 0건.
선험적 배제가 아니라 대조 결과이며, 인가는 `anyRequest().access()` closed-by-default 로 전면 강제된다.

**P2 = 14건**

| 순 | ID | 축 | 요구 축 | 트랙 | 위치 | 1줄 요약 | 난이도 | 다운스트림 |
|---|---|---|---|---|---|---|---|---|
| 1 | D-1 | D | R4,R5,R8 | F | `JpaEqpCrudPort.java:186-215` | `tc_eqp` 무잠금 read-modify-write → lost update (같은 레포에 `findByEqpIdForUpdate` 있는데 미사용) | S | gateway |
| 2 | D-5 | D | R4,R5 | F | `DeployEqpFinalizationService.java:33-38` | stale snapshot 으로 EQP 전 컬럼 blind overwrite (D-1 경로 경유) | M | gateway |
| 3 | D-3 | D | R4,R8 | F | `DeployService.java:121-160` | createDeploy 3단계 무트랜잭션 → PLANNING 좀비 deploy, 해당 설비 후속 deploy 전부 409 | M | ui-front |
| 4 | D-4 | D | R4,R8 | F | `DeployService.java:428-441` | cancelDeploy 3개 독립 커밋 → 부분 실패 시 RUNNING 영구 고착 | S | ui-front |
| 5 | D-2 | D | R4,R5 | F | `DeployServiceCommandSupport.java:97-137` + `DeployJdbcStorePort.java:136-149` | CANCELED TOCTOU + UPDATE 에 terminal 가드 없음 → 취소된 deploy 가 COMPLETED 로 부활 | S | ui-front |
| 6 | E-2 | E | R8 | F | `GroupAdminFacade.java:97-104` + 권한 행 `GROUP_WRITE`/`USER_INFO_WRITE` | 매핑 API 가 엔티티 CRUD 와 같은 permCode → 권한 부여 시 즉시 ADMIN 승격 가능, 서버측 가드 0 | M | ui-front, exec, **wm-ui-backend** |
| 7 | E-1 | E | R8 | F | `ActuatorAccessAuthorizationManager.java:59-85` + `nginx.conf:27-31` | 프록시 뒤에서 IP 화이트리스트 무력 + permCode 미검사 → 인증된 모든 사용자가 `/api/actuator/prometheus` 열람 | S | exec, gateway, business |
| 8 | D-18 | D/F | R4,R5 | F | `BusinessDlqRedisService.java:84-92` + `UiRedisConfiguration.java:86` | DLQ Redis 직렬화 mode 불일치(생산 typeless ↔ 소비 typed, Map fallback 없음) → DLQ 목록 무음 공백 | S | business, common, ui-front |
| 9 | D-9 | D | R4,R8 | F | `EqpManagementService.java:133-168` + `EqpController.java:290,472` | `timeoutMs` 완전 무시 + `DeferredResult` 타임아웃 미설정 → 503 과 실제 결과 불일치 | S | ui-front |
| 10 | D-6 | D | R4,R5,R8 | F | `EqpDirectScenarioService.java:109-143` | publish-first 로 RMW 창이 Kafka 왕복(수십 초), eqpId lease 없음 | L | gateway |
| 11 | D-7 | D | R4,R8 | F | `EqpDirectScenarioService.java:325-336` | gateway PASS 후 DB 반영 실패 시 보상·repair task 없음 → PLC 폴링 무음 중단 | S | gateway |
| 12 | D-8 | D | R4,R5 | F | `AsyncResultStoreService.java:157-177` | Redis 비원자 RMW → COMPLETED 가 TIMEOUT 으로 덮임 | S | ui-front |
| 13 | D-10 | D | R4,R8 | F | `EqpManagementService.java:325-341` | `runAsync` 본문 예외를 로그 없이 삼킴 → 성공이 TIMEOUT 으로 보고 | S | — |
| 14 | B-3 | B | R3,R4 | R | `DeployPlanningStateJdbcPort.java:166` / `DeployServiceSnapshotSupport.java:404` / `DeployServiceCommandSupport.java:689` | 22-필드 매핑 3중 복제 + `modelKey` 슬롯 키 공간이 이미 분기 | L | gateway, business |

**묶음 권고 (Phase 2 입력)**
- **묶음 1 (S, 최우선)**: D-1 + D-5 — 동일 근본 원인(`tc_eqp` 무잠금 RMW). `findByEqpIdForUpdate` 적용으로 함께 해소.
- **묶음 2 (S)**: D-2 + D-4 — deploy 상태 머신 원자성(조건부 UPDATE + 복합 어댑터 메서드).
- **묶음 3 (S)**: D-9 + D-10 — 비동기 경로 타임아웃·예외 누락.
- **P0-3 선행**: D-2/D-14/D-16/D-17 의 소재지 `DeployServiceCommandSupport`(1114, 무테스트)·`DeployJdbcStorePort`(1274, 무테스트)는
  characterization test 없이 손대면 안 된다.

**축별 발견 수**

| 축 | P1 | P2 | P3 | 계 |
|---|---|---|---|---|
| A (죽은 코드 + @Import) | 0 | 0 | 16 | 16 (+@Import 0결함) |
| B (중복) | 0 | 1 | 9 | 10 |
| C (smell) | 0 | 0 | 15 | 15 |
| D (결함) | 0 | 11 | 7 | 18 |
| E (보안) | 0 | 2 | 4 | 6 (+CVE 74건 별도) |
| F (계약) | — | — | — | 인벤토리 (Kafka 3 / REST DTO 76 / DB 46 / Redis 15) |
| **합계** | **0** | **14** | **51** | **65** |

---

## §8 세션 메타

### 8.1 읽기 전용 준수

```
$ git status --porcelain
(출력 0줄)
```
세션 전 구간에서 코드 수정 0. 생성물은 본 md 1개 + `build/reports/dependency-check-report.html`(gradle 산출물, git 미추적).
롤백 = 본 파일 삭제.

### 8.2 메인 세션 직접 재검증 목록 (채택 P2 전건 + 강등/기각 판정 근거)

| 대상 | 재검증 내용 | 결과 |
|---|---|---|
| D-1 | `JpaEqpCrudPort.java:175-215` 직접 열람 — `@Transactional` 존재 확인, `requireSnapshot` → `eqpStore.upsert` 에 stale 값(`existing.eqp().enabled/createdBy`) 사용 확인, `findByEqpIdForUpdate` 가 `JpaEqpParamCommandPort.java:285` 에 실재 확인 | 확정 |
| D-2 | `DeployJdbcStorePort.java:136-149` UPDATE SQL 원문 확인 — `WHERE deploy_key = :deployKey` 만 존재 | 확정 |
| D-3 | `DeployService.java:118-160` 시퀀스 확인 (createDeploy → captureTargetPreState 루프 → updateDeployStatus) | 확정 |
| D-4 | `DeployService.java:424-441` 3개 호출 분리 확인 | 확정 |
| D-5 | `DeployEqpFinalizationService.java:33-38` 읽기/쓰기 분리 확인 | 확정 |
| D-6 | `EqpDirectScenarioService.java:105-145` publish-first 구조 + lease 부재 확인 | 확정 |
| D-7 | `EqpDirectScenarioService.java:320-336` gateway PASS 후 DB 반영 + 보상 부재 확인 | 확정 |
| D-8 | `AsyncResultStoreService.java:152-180` read-check-write 비원자 확인 | 확정 |
| D-9 | `EqpManagementService.java:130-172` `timeoutMs` 미사용 확인 + `EqpController.java:290,472` `new DeferredResult<>()` 인자 없음 확인 + `spring.mvc.async.request-timeout` grep 0건 | 확정 |
| D-10 | `EqpManagementService.java:318-345` `exceptionally`/`whenComplete` 부재 확인 | 확정 |
| D-18 | `UiRedisConfiguration.java:75-90,143-152,200-208` serializer 3종 직접 확인 + `BusinessDlqRedisService.java:28-100` 읽기 경로 확인 + common `TcDbRedisAutoConfiguration.java:70-90,115-130` 기본 serializer 확인 | 확정(런타임 실증은 미수행) |
| E-1 | `ActuatorAccessAuthorizationManager.java:54-93` + `UiActuatorSecurityProperties.java:30-70` + `nginx.conf:27-31` + exec `forward-headers-strategy` grep 0건 + `application.yaml:59-78` exposure 확인 + helm 템플릿에 Ingress/HTTPRoute 부재 확인 | 확정 |
| E-2 | `GroupAdminFacade.java:95-145` 가드 부재 확인(grep 0건) + 권한 행 `GROUP_WRITE`/`USER_INFO_WRITE` NULL 메서드 확인 + 시드 그룹 매핑(`postgres_insert_sample_data.sql:800-860`) 확인 | 확정 |
| B-3 | 3개 builder 인용 라인 직접 열람 + `UiEquipmentProfileSnapshot.java:12-35` record 정의 확인 + 소비처 grep(`toEquipmentProfile`/`buildGatewayEquipmentProfile`) | 확정(런타임 영향은 미확인) |
| **강등 C-5** | `JpaEqpCrudPort.java:415-470, 675-712` 양쪽 분기 직접 대조 + `:220` `existing.eqp().commInterface()` + `EqpManagementCommand.java:163` Update record 확인 → commInterface 불변 | P2→P3 |
| **강등 B-2** | `EqpParamController.java:248-260`, `DeployController.java:59`, `EqpControllerCommandSupport.java:70,78` 값 확인 + `UiSecurityConfig.java:249-282` 도달 불가 확인 | P2→P3 |
| **강등 B-5** | `JpaModelFlowCommandPort.java:85-95`(isConflict 실재 = 오탐), `JpaLineQueryPort.java:35-43`(SELECT 전용) 확인 | P2→P3 |
| **강등 B-8** | `DeployServiceCommandSupport.java:1055-1070` vs `DeployPlanningService.java:1003-1020` arity·소재 클래스 상이 확인 | P2→P3 |
| **기각** | `LIVE_MAP_DEMO_RUN_SCENARIO_DELETE` 워크스페이스 grep → `postgres_2026_06_22_...:30` 실재 | FALSE(오탐) |
| **강등 A-1** | `UiCoreFallbackStorageConfiguration.java` 전문 열람 + `TcUiBackendAutoConfiguration.java:58-66` import 순서 확인 + redis adapter `@Primary` 3곳 grep | P2→P3 |
| **강등 A-8** | `EqpDirectScenarioRollbackService.java:218-220` 본문 + 호출부 `:114` 직접 열람 | P2→P3 |
| A-15/A-16 | `DeployPlanningService.java:1098-1104`, `DeployServiceCommandSupport.java:307-314` 직접 열람 | 확정 |
| @Import 정합성 | 메인 세션 독립 대조(스테레오타입 117 vs 등록 집합 132) **+** 서브에이전트 독립 대조(스테레오타입 130 — `@Configuration`/`@ControllerAdvice` 포함, `@ConfigurationProperties` 10 + `@AutoConfiguration` 5 별도) — **두 경로 모두 미등록 0건** | 0결함 확정 |

### 8.3 축 E CVE 상태

**[x] 완료** — `./gradlew dependencyCheckAggregate -PuseLocalMaven` 자연 실행 완주(1m 5s).
고유 CVE 138건, CVSS>7.0 게이트 트립 74건 실측. 출력 인용 §5.7.
`BUILD FAILED` 는 `SecurityScanConventionsPlugin` 게이트 정상 동작이며 스캔 실패가 아니다.
수동 카탈로그(인젝션 0 / 시크릿 0 / 역직렬화 0 / E-1~E-6)도 완결.

### 8.4 방법론 준수 편차 (정직 기록)

- 방법론은 서브에이전트가 중간 파일을 scratchpad 에 Write 하도록 요구하나, 본 세션의 읽기 전용 서브에이전트에는
  Write 도구가 제공되지 않아 중간 파일이 생성되지 않았다. 결과는 반환값으로 전량 회수해 본 문서에 통합했으므로
  감사 완결성에는 영향이 없으나, **세션 중단 시 재개 가능성 확보라는 원래 의도는 충족하지 못했다.**
  후속 감사 세션(S09~S13)에서는 서브에이전트에 Write 권한을 부여하거나 메인 세션이 반환값을 즉시 중간 파일로 저장할 것.
- 미확인으로 남긴 항목: ① 라이브 DB 의 `tc_ui_permission`/`tc_user_group_permission` 실제 행(시드와 drift 가능),
  ② 모듈별 미사용 gradle 의존성 대조, ③ Redis #13/#14 gateway 측 write serializer,
  ④ B-3 의 `modelKey` 슬롯을 gateway 가 어떻게 소비하는지, ⑤ D-18 런타임 실증, ⑥ nginx 응답 압축 설정(E-4 선행조건).

### 8.5 cross-repo 병합 예고 (S14)

| 본 감사 ID | 동일 원인 | S14 처리 |
|---|---|---|
| D-1 | gateway S06 **D-14**, business S07 **D-6** (read-modify-write lost update) | 3레포 병합 행, 원본 ID 3개 보존. 공통 처방(조건부 UPDATE 또는 `FOR UPDATE`)을 단일 규약으로 |
| D-18 | business S07 **E-2** (`RedisBusinessDlqEntry` 직렬화) — **S07 의 "JDK 직렬화" 전제는 오류**. 실제는 JSON typeless↔typed mode 불일치 | 병합 행에 정정 기록. 소유는 common `TcDbRedisAutoConfiguration` |
| E-1 | gateway/business 도 `/api/actuator` base-path 규약 공유 — 동종 노출 여부 **미확인** | S14 에서 두 레포 actuator 게이팅 확인 후 병합 판단 |
| B-1 | common 승격 후보. gateway/business 의 동일 패턴 보유 여부 미실측 | S14 에서 3레포 실측 후 승격 범위 확정 |
| CVE 74건 | gateway S06 의 CVSS>7.0 52건과 **의존성 대부분 공통** | S14 에서 **CVE 단위**로 dedupe(레포 단위 중복 계상 금지) |

### 8.6 다음 세션

**S09 — 감사: nori-tc-ui-front** (`c:\workspace_nori\nori-tc-ui-front`).
중점: 축 E 에 XSS/토큰 저장 포함. 렌더링 성능은 제외(Phase 3 P 트랙). 미사용=knip, 중복=jscpd(없으면 `npx --yes` 일회 실행 + 버전 기록).
hotspot: 6개 초대형 컴포넌트(1400~1712 LOC) 전부 unit 부재 — deep 배분 **1순위**.
본 감사에서 넘기는 입력: §6.2 REST DTO 76개(ui-front 가 소비), E-2(권한 상수·`GroupPermissionsModal` 게이팅), D-8/D-9(비동기 polling 오탐).

### 8.7 완결성 검증 게이트 결과 (S09 세션 수행, 2026-07-27)

S09 세션 진입 시 본 감사에 **완결성·정직성 게이트**를 적용했다(깊은 반증 재검토는 별도 트랙). BLOCK 사유 없음 → 아래 6건을 in-place 보정했다.

| # | 보정 지점 | 내용 |
|---|---|---|
| 1 | §1.2 표 머리말 | 8필드 중 `다운스트림` 누락 → A-14/A-12 = nori-tc-exec, 나머지 14건 = 없음 으로 보완 |
| 2 | §1.4 | 0건 축의 실행 명령·검색 범위 미기재 → grep 명령 추가 |
| 3 | §2 B-3 | 내부 인용 라인 1줄 드리프트(`:174`→`:175`, `:412-413`→`:413-414`) 정정. 외곽 범위·인용 코드는 정확했음 |
| 4 | §4.2 표 머리말 | 8필드 중 `트랙`·`다운스트림` 누락 → 방법론 매핑과 각 행 시나리오에서 도출해 보완 |
| 5 | §5.1 | `permitAll 5 + actuator 4 + 권한행 99 = 108` 분할식 오류 정정 → **permitAll 2 + 권한행 106 = 108**. 결론 불변 |
| 6 | §5.2 | 제목 "30행" ↔ 표 실제 29 permCode 불일치 정정 |

**게이트에서 독립 재계산·재확인해 원문과 일치한 항목** (보정 불필요):
- 규모 472 main / 91 test / generated 0 + 모듈별 7행 전부 일치. `*ModuleConfiguration` 6개 `find` 재확인 일치.
- 엔드포인트 **108** 독립 재계산 일치 — `@(Get|Post|Put|Patch|Delete|Request)Mapping` 총 128 − 클래스 레벨 `@RequestMapping` 20 = 108,
  컨트롤러 20개 개별 수치도 전부 일치.
- **P1 0 = 실제 게이팅 확인 결과임을 확인**(관성 답습 아님). 게이트가 표본 대조한 엔드포인트:
  `GET /api/live-map`(LIVE_MAP_READ EXACT) / `DELETE /api/live-map/demo-runs/scenarios/{id}`(패치 permCode PREFIX) /
  `POST /api/deploys/{k}/start`(DEPLOY_ACTION REGEX) / `GET /api/line`(LINE_READ EXACT — `@GetMapping` 무경로라 EXACT 성립) /
  `POST /api/auth/login`(permitAll) / `GET /api/eqp/gateway-shard-leases`(EQP_READ PREFIX).
  컨트롤러 base path 15종 전수를 §5.2 의 PREFIX/EXACT/REGEX resource 와 대조 → 누락 0.
- **P1/P2 14건 전건**의 인용 file:line 을 게이트가 직접 열람 — 13건 완전 일치, B-3 만 위 #3 의 1줄 드리프트.
- CVE 수치 독립 재계산 일치: 리포트 고유 CVE `138`(`grep -o "CVE-[0-9]\{4\}-[0-9]*" ... | sort -u | wc -l`),
  게이트 트립 `74`(저장된 실행 로그의 CVSS>7.0 목록 `sort -u`). 인용된 `BUILD FAILED in 1m 5s` / `5 actionable tasks: 1 executed, 4 up-to-date` 도 로그 원문과 일치.
- 읽기 전용 준수: `git status --porcelain` 0줄, S08 커밋 `372aaac` 은 본 md 1파일(+1239)만 변경.

**깊은 재검토 이관 후보**(이 게이트에서 등급을 바꾸지 않음): §8.4 의 미확인 6항목 그대로 승계.

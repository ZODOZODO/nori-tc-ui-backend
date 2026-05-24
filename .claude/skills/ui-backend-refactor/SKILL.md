---
name: ui-backend-refactor
description: nori-tc-ui-backend (Spring Boot + 헥사고날 멀티모듈, UI surface controller) measurement-first 리팩토링. 정적 측정(LOC/클래스 복잡도/@Import 수) + JVM 런타임 baseline 후 분할 적용 → 정적 개선 확인 + 빌드/테스트 regression 차단 + UI surface DTO 보존. "측정 안 한 분할" 금지.
allowed-tools: [AskUserQuestion, Read, Glob, Grep, Bash, Write, Edit]
---

# nori-tc-ui-backend Refactor — Measurement-First

## WHY (이 skill 의 존재 이유)

과거 nori-tc 리팩토링은 "정적 측정 → 보고서 → 사용자가 별 세션에서 분할 적용" 이었다. ui-backend 는 UI surface (Controller / Response DTO) 가 ui-front 와 직결돼 함정이 더 크다:

- 분할 후 **regression 검증 없음** — 클래스 / 모듈 분리는 빌드 시간 / 테스트 시간 / Spring context 초기화에 영향
- **`*ModuleConfiguration` `@Import` 누락** — ComponentScan 이 아닌 `@Import` 명시 등록 컨벤션. 분할 후 등록 깜빡 시 컴파일 성공 + 런타임 실패 (`NoResourceFoundException` 등). ui-backend 는 Controller 분할 시 특히 위험 — Controller 누락은 endpoint 404 로 직결.
- **UI surface DTO contract 위험** — 응답 DTO 필드명 / 타입 변경 시 ui-front 의 TanStack Query / 컴포넌트 마이그레이션 필요. refactor 트랙은 *내부 구조* 한정 — surface 변경은 별 SPEC.
- 분할 가치 (LOC ↓ / 응집도 ↑) 가 측정되지 않으면 "필요 없는 분할" 양산

본 skill 은 **정적 baseline + JVM 런타임 baseline + ModuleConfiguration 정합성 + UI surface DTO 보존 → 분할 → 정적 개선 확인 + 빌드/테스트 regression 검증 + ui-front cross-reference** 사이클을 강제한다.

리팩토링의 주 가치는 **정적** (LOC / 응집도 / `@Import` 분포). JVM 런타임 측정은 **regression 차단** 용도. 런타임 응답 시간 / p95 개선은 별 트랙 ([ui-backend-perf](../ui-backend-perf/SKILL.md)) — 명확 분리.

## 사용자 인자

- `$1` = 타깃 경로. 예: `ui/tc-ui-core` / `ui/adapter/tc-ui-web-adapter`
- `$2` = 모드 (선택). 기본 `audit-full`. 옵션:
  - `audit-full` — 정적 + 런타임 (Actuator/JFR/build-trace) baseline 모두
  - `audit-static` — 정적만 (앱 안 띄움)
  - `verify` — 직전 변경의 정적 + 빌드/테스트 regression 검증만

`$1` 비어있으면 AskUserQuestion 으로 타깃 받음.

## Step 0 — 서버 가동 사전 점검 (audit-full / verify 만)

audit-static 모드면 skip. 그 외:

### 0-1. 앱 가동 확인
```bash
curl -s -o /dev/null -w "ui-backend HTTP %{http_code}\n" http://localhost:8080/actuator/health
```
- HTTP 200 / 401 = 정상.
- 비응답 / 503 = 미가동. 사용자에게 명령 안내 후 중단 (자동 실행 안 함):
  ```
  C:\workspace_nori\nori-tc-exec\exec\windows\build-trace-apps.bat ui-backend
  C:\workspace_nori\nori-tc-exec\exec\windows\run-trace-tc-ui-backend-app.bat
  ```
  → `run-app` skill 이 JFR / GC / heap dump 옵션 자동 적용 ([run-trace-tc-ui-backend-app.bat:103](../../../../nori-tc-exec/exec/windows/run-trace-tc-ui-backend-app.bat#L103)).

### 0-2. Actuator metrics 노출 점검
```bash
curl -s http://localhost:8080/actuator/metrics 2>&1 | head -20
```
- 200 + JSON = 정상.
- 404 / 401 = `management.endpoints.web.exposure.include=health,info,metrics,prometheus` 미설정. 워크스페이스 grep 결과 (`tc-ui-backend.properties` / `nori-tc-exec/apps/tc-ui-backend-app/config/`) `management.*` 부재 → **노출 설정 부재가 default** 가능성 높음 ([ui-backend-perf SKILL.md:51](../ui-backend-perf/SKILL.md#L51) 도 동일 진단). 첫 사용 시 사용자 확인.

### 0-3. JFR 디렉터리 확인
run-trace-tc-ui-backend-app.bat 이 `C:\tc-trace\tc-ui-backend-app\jfr\*.jfr` 로 dump ([run-trace-tc-ui-backend-app.bat:25](../../../../nori-tc-exec/exec/windows/run-trace-tc-ui-backend-app.bat#L25), [:103](../../../../nori-tc-exec/exec/windows/run-trace-tc-ui-backend-app.bat#L103)).
```bash
ls -la C:/tc-trace/tc-ui-backend-app/jfr/ 2>/dev/null | tail -5
```

### 0-4. UI surface DTO baseline 캡처 (ui-front cross-reference)
분할 전 현재 응답 DTO 의 JSON 구조 캡처. 분할 후 동일 endpoint 의 응답이 byte-equal 여야 함:
```bash
TS=$(date -u +%Y-%m-%dT%H%M)
mkdir -p .claude/perf/measurements
# 임의의 read endpoint 1~3 개 — 분할 대상 controller 의 GET 만
curl -s -b cookies.txt http://localhost:8080/api/eqp > ".claude/perf/measurements/surface-baseline-${TS}.eqp.json"
```
ui-front baseline JSON (`c:/workspace_nori/nori-tc-ui-front/.claude/perf/measurements/baseline-*.json`) 의 동일 endpoint 응답 hash 와 cross-check 가능.

## Step 1 — Baseline 측정

### 1-1. 정적 측정 (감사 본질, zero-dep shell+Grep+wc)

각 측정은 출처 file:line 명시. 추측 금지. `$1` 디렉터리 기준.

**LOC 분포** (`$1` 하위 `*.java`):
```bash
find <target> -type f -name "*.java" -path "*/src/main/java/*" -exec wc -l {} + | sort -rn | head -20
```

**클래스 복잡도** (각 `.java` 파일별):
- `public` 메서드 수 — `grep -cE "^\s*public .+\(" file.java`
- `private` 메서드 수 — `grep -cE "^\s*private .+\(" file.java`
- 생성자 파라미터 수
- LOC — `wc -l file.java`
- `@Autowired` / `@Inject` 카운트
- 중첩 `if` / `for` 깊이 — `grep -E "^\s{12,}(if|for|while)" file.java | wc -l` (heuristic)

**Controller 비대화** — ui-backend 특화:
```bash
find ui/adapter/tc-ui-web-adapter/src/main/java -name "*Controller.java" -exec wc -l {} + | sort -rn
```
단일 Controller LOC > 400 / `@GetMapping` + `@PostMapping` 카운트 > 12 → 분할 후보.

**`*ModuleConfiguration` `@Import` 수**:
```bash
for f in $(find <target> -name "*ModuleConfiguration.java"); do
  echo "=== $f ==="
  grep -cE "^\s+[A-Z][A-Za-z0-9_]+\.class" "$f"   # 마지막 원소는 ',' 없음 — comma 빼고 매치
done
```
원소 수 > 15 면 모듈 분리 후보. (ui-backend 는 6 개 ModuleConfiguration 존재 — db / kafka / redis / web / core / service.)

**헥사고날 경계 위반** (`core` 패키지가 `adapter` import 하는지):
```bash
grep -rn "import com\.nori\.tc\.ui\.adapter" <target>/src/main/java/com/nori/tc/ui/core/ 2>/dev/null | head -10
```
1 건 이상 → 즉시 수정 후보 (ArchUnit `UiCoreDependencyArchTest` 차단 대상).

**`@Component` / `@Service` / `@RestController` / `@Repository` 카운트**:
```bash
grep -rcE "^@(Component|Service|RestController|Repository)" <target>/src/main/java 2>/dev/null | grep -v ":0" | sort -t: -k2 -rn | head -10
```

**Response DTO 비대화** — ui-backend 특화 (분할 후보지만 surface 보존 필요):
```bash
find ui -name "*Response*.java" -o -name "*Dto*.java" -o -name "*View*.java" 2>/dev/null | xargs wc -l | sort -rn | head -10
```
DTO LOC > 200 → 도메인 분리 후보. 단, 필드명 / 타입 변경은 surface 깨짐 (별 SPEC).

### 1-2. 런타임 baseline (audit-full)

**Actuator metrics** (Actuator 노출 시):
```bash
TS=$(date -u +%Y-%m-%dT%H%M)
mkdir -p .claude/perf/measurements
OUT=".claude/perf/measurements/refactor-baseline-${TS}"
curl -s http://localhost:8080/actuator/metrics/jvm.memory.used    > "${OUT}.jvm-mem.json"
curl -s http://localhost:8080/actuator/metrics/jvm.gc.pause       > "${OUT}.jvm-gc.json"
curl -s http://localhost:8080/actuator/metrics/process.cpu.usage  > "${OUT}.cpu.json"
ls -la "${OUT}"*
```

**JFR**:
```bash
ls -t C:/tc-trace/tc-ui-backend-app/jfr/*.jfr 2>/dev/null | head -1
"$JAVA_HOME/bin/jfr" summary <file>.jfr 2>&1 | tail -40
```
출력 마지막 5~10줄 인용.

**테스트 baseline**:
```bash
./gradlew :ui:tc-ui-core:test 2>&1 | tail -25
./gradlew :ui:adapter:tc-ui-web-adapter:test 2>&1 | tail -25
```
PASS 확인 + 실행 시간 캡처. **마지막 5~10줄 코드블록 인용 필수**.

**빌드 baseline** — `build-trace` skill 호출:
```bash
C:\workspace_nori\nori-tc-exec\exec\windows\build-trace-apps.bat ui-backend 2>&1 | tail -25
```
JAR 크기:
```bash
ls -la ui/starter/tc-ui-backend-starter/build/libs/*.jar | grep -v plain
```

### 1-3. 보고서 작성
`.claude/refactor/<target-slug>-<YYYY-MM-DD>.md` 에 정적 / 런타임 baseline + UI surface DTO baseline JSON 경로 기록 — regression + surface 비교용 anchor.

## Step 2 — 임계값 + 분할 후보 식별

### 임계값 (Spring 헥사고날 baseline 2026-05-24 기준)

| 항목 | 임계 | 근거 |
|---|---|---|
| `.java` LOC | > 400 | god class 분할 명확선 |
| 단일 Controller LOC | > 400 | endpoint 군 분리 후보 (예: ModelController → ModelQueryController + ModelMutationController) |
| 단일 클래스 메서드 수 | ≥ 20 | 단일 책임 위반 의심 |
| 생성자 파라미터 수 | ≥ 7 | 의존 폭증 — facade 분리 후보 |
| `@Import` 배열 원소 수 | > 15 | 모듈 분리 또는 sub-configuration 분리 |
| `if` / `for` 중첩 깊이 | ≥ 4 | 메서드 추출 후보 |
| adapter → core 역방향 import | ≥ 1 | 즉시 수정 (헥사고날 위반) |
| Response DTO LOC | > 200 | 도메인 분리 후보 (단, surface 보존) |

*ui-front 가 LOC > 500 인 이유는 JSX 가 verbose 해서. Java 는 400 이 더 적절.*

### 우선순위 분류

- **P1 (즉시)**: LOC > 800, 메서드 수 ≥ 30, adapter→core 역방향 import, `@Import` > 25
- **P2 (다음 스프린트)**: LOC 400–800, 메서드 수 20–30, `@Import` 15–25, Controller endpoint 군 분리
- **P3 (기록만)**: 중첩 깊이 4, 생성자 파라미터 7~10 — false positive 가능

### 분할 패턴 후보 (사용자 선택 필요 — 자동 결정 금지)

1. **메서드 추출** — 한 메서드 LOC > 60 시 private helper 분리
2. **클래스 추출** — 단일 클래스 LOC > 400 시
   - (a) helper class 추출
   - (b) facade 패턴
   - (c) strategy 패턴
3. **Controller 분할** — 단일 Controller 가 read + write + admin endpoint 혼재 시
   - 예: `ModelController` → `ModelQueryController` + `ModelMutationController` + `ModelBranchController` (이미 분리됨, [Controllers 목록 grep](#) 으로 확인 가능)
   - **URL path / HTTP method / 응답 body 는 byte-equal 보존** — surface 변경은 별 SPEC
4. **모듈 분리** — `*ModuleConfiguration` `@Import` 원소 > 15 시 하위 모듈 도입
5. **포트 분리** — 단일 포트 인터페이스 메서드 > 10 시 read-port / command-port 분리
6. **어댑터 분리** — 단일 어댑터 클래스가 여러 외부 시스템 의존 시 분리 (예: DemoTesterClient + DLQ Redis 동시 처리 → 분리)

## Step 3 — 변경 후보별 사용자 승인 cycle

**한 번에 한 변경만**. compound 변경 금지.

AskUserQuestion 으로 분할 단위 선택. 질문 본문에 포함:
- file:line (현재 코드)
- 추출 후 신규 파일 경로 + 클래스 시그니처
- 영향받는 import 처 (Grep 으로 추적 결과)
- `*ModuleConfiguration` `@Import` 갱신 필요 여부 + 대상 파일 file:line
- 위험 (caller 변경 동반 / Spring 빈 등록 누락 / ArchUnit 위반 신규)
- **UI surface 영향 — Controller 분할 시 URL path / 응답 body 보존 여부 명시**
- 분할이 빌드 시간 / 테스트 시간에 영향 줄 가능성

## Step 4 — 변경 적용 + 검증

1. **Edit / Write** 으로 추출 적용
2. 새 `@Component` / `@Service` / `@RestController` / `@Repository` 추가 시 **같은 모듈의 `*ModuleConfiguration` 의 `import` + `@Import` 두 줄을 반드시 추가** ([nori-tc-common/CLAUDE.md MUST NOT](../../../../nori-tc-common/CLAUDE.md))
   - Controller 누락 시 endpoint 404 (`NoResourceFoundException`) — ui-front 의 TanStack Query 가 실패하는 형태로 surface 깨짐.
3. `validate-module-imports` skill 호출 — `@Import` 누락 자동 검출
4. **정적 재측정**: 같은 Grep / wc 로 LOC / 메서드 수 / `@Import` 카운트 → baseline 대비 변화 출력
5. **UI surface DTO 보존 검증** — Step 0-4 의 baseline JSON 과 분할 후 동일 endpoint 응답 hash / diff:
   ```bash
   curl -s -b cookies.txt http://localhost:8080/api/eqp > ".claude/perf/measurements/surface-after-${TS}.eqp.json"
   diff <(jq -S . .claude/perf/measurements/surface-baseline-*.eqp.json) \
        <(jq -S . .claude/perf/measurements/surface-after-${TS}.eqp.json) 2>&1 | head -20
   ```
   diff 0 줄 → PASS. 차이 발생 → **surface 깨짐 — 즉시 롤백** + 사용자 보고.
6. 모듈 단위 테스트:
   ```bash
   ./gradlew :ui:tc-ui-core:test 2>&1 | tail -25
   ./gradlew :ui:adapter:tc-ui-web-adapter:test 2>&1 | tail -25
   ```
   **마지막 5~10줄 코드블록 인용 필수**. PASS / FAIL 명시.
7. ArchUnit 검증:
   ```bash
   ./gradlew :ui:tc-ui-core:test --tests "*ArchTest" 2>&1 | tail -25
   ```
   `UiCoreDependencyArchTest` 통과 확인 ([UiCoreDependencyArchTest.java:17](ui/tc-ui-core/src/test/java/com/nori/tc/ui/core/architecture/UiCoreDependencyArchTest.java#L17)).
8. 빌드 검증:
   ```bash
   C:\workspace_nori\nori-tc-exec\exec\windows\build-trace-apps.bat ui-backend 2>&1 | tail -25
   ```
9. 빌드 / 테스트 / ArchUnit / surface diff FAIL 시 자동 롤백 (Edit 으로 원복) + 사용자 보고

## Step 5 — 런타임 regression 측정 (audit-full)

분할이 빌드 시간 / 테스트 시간 / Spring context 에 영향 줬는지 확인:

- **빌드 시간**: baseline 대비 ±10% 내 → PASS
- **테스트 시간**: baseline 대비 ±20% 내 → PASS (모듈 분리 시 소폭 증가는 정상)
- **JFR `jdk.JavaErrorThrow`**: 신규 오류 0 건 → PASS
- **JAR 크기**: baseline 대비 ±5% 내 → PASS
- **UI surface DTO byte-equal** (Step 4-5 결과) — diff 0 줄

```bash
"$JAVA_HOME/bin/jfr" print --events jdk.JavaErrorThrow C:/tc-trace/tc-ui-backend-app/jfr/<after>.jfr 2>&1 | head -50
```

> NOTE: 응답 시간 / p95 개선은 본 skill 의 PASS 조건이 아님. 측정해도 좋지만 판정 근거 아님. perf 트랙은 [ui-backend-perf](../ui-backend-perf/SKILL.md) 별 호출.

## Step 6 — 효과 평가 + 결정

### 정적 측면 (refactor 의 주 가치)
- LOC 감소 N% — 측정값 인용
- 메서드 수 / 생성자 파라미터 / `@Import` 분포 개선 — 측정값 인용
- `@ComponentScan` 도입 0 건 확인 (글로벌 컨벤션)
- ArchUnit 통과 — `UiCoreDependencyArchTest`

### 런타임 측면 (regression 차단)
- 빌드 시간 baseline 대비 ±10% 내
- 테스트 시간 baseline 대비 ±20% 내
- JFR 신규 오류 0 건
- UI surface DTO byte-equal

판정:
- **정적 개선 + regression 없음 + surface 보존**: commit (별 세션에서 `mr-review` 호출 후 MR)
- **정적 개선 + regression 있음**: 사용자 보고 → 롤백 vs 수용 결정
- **surface 깨짐**: 즉시 자동 롤백 (별 SPEC 으로만 surface 변경 가능)
- **정적 개선 미미**: 롤백 권장
- **빌드 / 테스트 / ArchUnit FAIL**: 자동 롤백

## Step 7 — 반복 / 종료

- 다음 후보 있음 → Step 3 으로
- 없음 → 종료 보고:
  - 적용한 분할 목록 (commit hash + 정적 diff + 빌드/테스트 diff + surface diff 결과)
  - 미적용 후보 (사용자 결정 거절 / 변경 금지 영역 충돌 등)
  - ui-front 협업 SPEC 후보 (DTO 분리 / 도메인 분리 등 surface 변경 필요 사항)
  - 다음 측정 시점 권장

## 산출물

- **정적 측정 JSON**: `.claude/perf/measurements/refactor-{baseline,after}-<ISO>.json` (gitignored)
- **UI surface baseline / after JSON**: `.claude/perf/measurements/surface-{baseline,after}-<ISO>.<endpoint>.json` (gitignored)
- **마스터 보고서**: `.claude/refactor/<target-slug>-<YYYY-MM-DD>.md` (git tracked)
- **마스터 진행 노트**: `.claude/progress/refactor-master.md` (gitignored, 이어받기용)

## 변경 금지 영역

- **UI surface DTO contract** — URL path / HTTP method / 응답 body 필드명 / 타입. ui-front 의 TanStack Query / 컴포넌트와 직결. 변경은 별 SPEC + ui-front 마이그레이션 paired PR.
- **응답 JSON 직렬화 정책** — `@JsonProperty` / `@JsonInclude` 정책 변경 금지 (필드 노출 / null 처리 등).
- **인증 / 권한 정책** — `tc.ui.backend.auth.*` properties + AuthController / Filter 변경 금지 (별 보안 SPEC).
- **Kafka 토픽 이름 / metadata.source 규약** — `tc.ui.backend.kafka.*` properties ([tc-ui-backend.properties](../../../../nori-tc-exec/apps/tc-ui-backend-app/config/tc-ui-backend.properties)) 변경 금지. business / gateway 와 contract.
- **DB 스키마 / `@Entity` 매핑** — refactor 트랙 비대상.

## 절대 금지

- **측정 없이 분할 적용 금지** — Step 1 baseline 없이 Step 4 진입 금지
- **compound 변경 금지** — 한 번에 한 클래스의 한 분할만
- **`@ComponentScan` 도입 금지** — 분할 후 빈 등록은 반드시 `*ModuleConfiguration` `@Import` 갱신 ([nori-tc-common/CLAUDE.md MUST NOT](../../../../nori-tc-common/CLAUDE.md))
- **UI surface 변경 금지** — Controller URL / response DTO 필드 변경은 별 SPEC + ui-front paired
- **빌드/테스트/ArchUnit/surface diff 무시 금지** — FAIL 시 자동 롤백
- **추측 측정값 보고서 작성 금지** — 모든 카운트는 도구로 측정, 출처 file:line 명시

## 검증 규칙 (글로벌 #1 VERIFY BEFORE CLAIMING 준수)

- 모든 빌드 / 측정 실행 시 **출력 마지막 5~10줄 코드블록 인용 필수**
- PASS / FAIL 명시
- 정적 측정값은 Grep / wc 출력 인용
- "LOC N% 감소" 주장 전 before/after 카운트 둘 다 인용
- 부재 단언 전 워크스페이스 전체 (`c:\workspace_nori\*`) grep — 동일 패턴이 다른 nori-tc-* 레포에 있는지 먼저 확인

## 컨벤션 참조

- 모듈 구조: [settings.gradle.kts](settings.gradle.kts) — domain / core / adapter / starter 4 레이어
- 헥사고날 + `@Import` 명시 등록: [nori-tc-common/CLAUDE.md](../../../../nori-tc-common/CLAUDE.md)
- 빌드 / 가동: `nori-tc-exec/exec/windows/build-trace-apps.bat ui-backend` + `run-trace-tc-ui-backend-app.bat`
- JFR / GC / heap dump 디렉터리: `C:\tc-trace\tc-ui-backend-app\{jfr,gc,heap,console}` ([run-trace-tc-ui-backend-app.bat:22-26](../../../../nori-tc-exec/exec/windows/run-trace-tc-ui-backend-app.bat#L22-L26))
- ArchUnit: [UiCoreDependencyArchTest.java:17](ui/tc-ui-core/src/test/java/com/nori/tc/ui/core/architecture/UiCoreDependencyArchTest.java#L17)
- Actuator / Micrometer 의존: [ui-backend-perf SKILL.md:289-290](../ui-backend-perf/SKILL.md#L289-L290)

## 같이 쓰는 skill

- [build-trace](../../../../../.claude/skills/build-trace/SKILL.md) — common publishToMavenLocal + bootJar 빌드. Step 1-2 / Step 4 검증에 사용
- [run-app](../../../../../.claude/skills/run-app/SKILL.md) — ui-backend 앱 가동 (JFR 자동). Step 0 / Step 5 재가동에 사용
- [validate-module-imports](../../../../../.claude/skills/validate-module-imports/SKILL.md) — 새 컴포넌트 추가 시 `@Import` 누락 점검. Step 4 직후 권장
- [mr-review](../../../../../.claude/skills/mr-review/SKILL.md) — refactor commit 모아 MR 만들기 직전 self-review
- [ui-backend-perf](../ui-backend-perf/SKILL.md) — 동일 레포 perf 트랙 (런타임 응답 시간 / EXPLAIN / Actuator http.server.requests). refactor 는 정적 구조만, perf 는 런타임만 — 명확 분리. response DTO 슬림화는 perf 트랙 P1 항목.
- [ui-front-refactor](../../../../nori-tc-ui-front/.claude/skills/ui-front-refactor/SKILL.md) — UI surface DTO 변경 필요 시 paired refactor 대상

## SDD 연동

각 P1 항목별로 `/spec-kickoff <feature>-refactor` 호출하여 SPEC 작성 후 Plan / Implement 진행 권장. surface 영향 큰 항목은 ui-front-refactor 와 paired SPEC.

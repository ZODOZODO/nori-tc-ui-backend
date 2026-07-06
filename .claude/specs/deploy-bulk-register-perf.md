# SPEC: deploy-bulk-register-perf

> 크로스레포 SPEC. 핵심(DB/포트 경계)은 **nori-tc-ui-backend**, UI 부분은 **nori-tc-ui-front**.
> 앵커 레포는 ui-backend(선례 `live-map-batch-query.md` 와 동일 위치). 각 요구사항에 대상 레포를 명시한다.

## Why

Deploy 페이지 '등록'에서 B라인 전체 설비를 조회 → 전체 선택 → '선택에 추가' → 모델 변경 후 배포하면
(1) **너무 느리고**, (2) **deploy 생성 자체가 실패**한다. 실측 root cause (2026-07-06, 코드 + 라이브 config):

### 근원 A — 100대 하드 리밋 → 생성 실패 (결정적)
- 라이브 config `tc.ui.backend.deploy.max-targets-per-request=100` ([tc-ui-backend.properties:80](../../../nori-tc-exec/apps/tc-ui-backend-app/config/tc-ui-backend.properties), 기본값도 100 — `UiDeployProperties.java:33`).
- preview([`DeployPlanningService.java:102-105`](../../ui/tc-ui-core/src/main/java/com/nori/tc/ui/core/service/DeployPlanningService.java))와 plan([`:69-72`](../../ui/tc-ui-core/src/main/java/com/nori/tc/ui/core/service/DeployPlanningService.java)) **둘 다** 진입 즉시 타깃 수>100 이면 `UiBadRequestException` throw.
- 프론트는 등록 목록을 `limit=500` 까지 받고(`useDeployRegisterTargets.ts:16`), '전체 선택 → 선택에 추가'에 **개수 상한이 없음**(`DeployRegisterPanel.tsx:747`). → B라인 설비가 100대 초과면 배포 버튼을 눌러도 시나리오 팝업조차 못 뜨고 raw 400("미리보기 실패")으로 끝난다.

### 근원 B — planner per-eqp N+1, 그것도 preview+create 두 번 → 느림(백엔드 주범)
- preview 루프([`DeployPlanningService.java:119-121`](../../ui/tc-ui-core/src/main/java/com/nori/tc/ui/core/service/DeployPlanningService.java))와 plan 루프([`:201-202`](../../ui/tc-ui-core/src/main/java/com/nori/tc/ui/core/service/DeployPlanningService.java))가 타깃마다 `deployPlanningStatePort.findCurrentStateByEqpId(eqpId)` 순차 호출.
- 그 1회가 `mapCurrentState` → `EqpManagementDbSupport.loadSnapshotByEqpId` ([`EqpManagementDbSupport.java:113-154`](../../ui/adapter/tc-ui-db-adapter/src/main/java/com/nori/tc/ui/adapter/db/EqpManagementDbSupport.java))로 설비당 ~13개 sub-query 실행(eqp/model/hsms/socket/plc/log/state/connection/port/params/paramVersion/gatewayJar/businessJar).
- 100대 배포 = preview ~1,400 + create/plan ~1,400 ≈ **3,000 순차 쿼리**.
- 대조: 같은 화면의 등록 목록 조회([`JpaDeployRegisterQueryPort.java:50-93`](../../ui/adapter/tc-ui-db-adapter/src/main/java/com/nori/tc/ui/adapter/db/JpaDeployRegisterQueryPort.java))는 이미 model/jar/conflict를 **벌크 로드**한다 → planner만 per-eqp 패턴이 남음.
- preview 의 delta 판정([`calculateDeltaKinds:427-440`](../../ui/tc-ui-core/src/main/java/com/nori/tc/ui/core/service/DeployPlanningService.java))은 `modelVersionKey / businessJarRef / appliedParamVersion / interfaceType` **4개 필드만** 쓰는데 전체 스냅샷을 over-fetch.

### 근원 C — 배포 후 폴링 N+1 → 모니터링 느림
- `getDeploySummary → resolveAsIsState`([`DeployService.java:232-257`](../../ui/tc-ui-core/src/main/java/com/nori/tc/ui/core/service/DeployService.java))가 타깃마다 `findLatestTargetSnapshot` + `eqpCrudPort.findSnapshotByEqpId`(heavy) 호출. summary 폴링마다 100×heavy 반복.

### 근원 D — 프론트 비가상화 + O(n²) → 전체선택 순간 UI 버벅임
- 등록 검색/선택 테이블이 `features` 미지정으로 **전 행 DOM 렌더**([`DeployRegisterPanel.tsx:1331`, `:1379`](../../../nori-tc-ui-front/src/features/deploy/components/DeployRegisterPanel.tsx)). `SelectableTable` 은 가상화 지원(`SelectableTable.tsx:297`)하나 미사용.
- 체크박스가 행마다 `searchSelectionIds.includes()` O(n) → 렌더 O(n²)([`:840`](../../../nori-tc-ui-front/src/features/deploy/components/DeployRegisterPanel.tsx)).
- 동기화 effect가 선택 변경마다 `registerTargetItems.filter(i => selectedEqpIds.includes(i.eqpId))` O(n²)([`:289-295`](../../../nori-tc-ui-front/src/features/deploy/components/DeployRegisterPanel.tsx)).

### 선례
`live-map-batch-query.md` 가 동일한 `loadSnapshotByEqpId` N+1을 batch prefetch로 해결 → 540 sub-query/call → 6, 5.13s → 88ms. 본 SPEC은 그 패턴을 planner 경로에 적용한다.

## Requirements

> `[repo]` 표기: **BE** = nori-tc-ui-backend, **FE** = nori-tc-ui-front

- [x] R1. `[BE]` `DeployPlanningStatePort` 에 **배치 조회 메서드** 추가 — `findCurrentStatesByEqpIds(List<String>) → Map<String, DeployCurrentState>` (또는 경량/전체 2종). 단건 `findCurrentStateByEqpId` 는 유지(다른 영향 최소화).
- [x] R2. `[BE]` **preview 경로 경량화** — preview 는 delta 4개 필드만 필요하므로 `equipmentProfile`(heavy `loadSnapshotByEqpId`)을 **로드하지 않는** 경량 current-state 를 1회 벌크 SQL(`tc_eqp` + socket/hsms join, `JpaDeployRegisterQueryPort` 패턴)로 조회. preview 루프의 per-eqp 호출 제거.
- [x] R3. `[BE]` **plan 경로 배치화** — plan 은 `injectEquipmentProfile`([`:683`](../../ui/tc-ui-core/src/main/java/com/nori/tc/ui/core/service/DeployPlanningService.java))에 `equipmentProfile` 이 필요하므로, 필요한 스냅샷을 **테이블별 IN-clause 벌크 로드**(선례 R3~R8 방식)로 전환. per-eqp `loadSnapshotByEqpId` 제거.
- [x] R4. `[BE]` **delta / 시나리오 / step 판정 결과는 전후 100% 동일**. 계산 로직(`calculateDeltaKinds` / `resolveScenario` / `resolveSteps`)은 불변, 데이터 공급 방식만 변경.
- [~] R5. `[BE]` **폴링 N+1 완화** — `resolveAsIsState` 의 per-target 스냅샷 조회를 `deployKey` 기준 배치 조회로. as-is 요약은 `summary-cache-ttl-seconds`(기 설정) 활용해 매 폴링 재계산 회피. — **부분**: per-target 배치화(`findLatestTargetSnapshots` DISTINCT ON) + 라이브 fallback 중복 조회(2→1) 제거 완료. as-is 캐시는 D4(c)로 후속 분리(Out of Scope 인접).
- [x] R6. `[BE]` 신규 컴포넌트(신규 port impl 등)는 같은 모듈의 `*ModuleConfiguration` 의 `@Import` 에 명시 등록(nori-tc-common 컨벤션).
- [x] R7. `[FE]` 등록 검색 테이블 + 선택 테이블에 `features={{ virtualization: true }}` 부여.
- [x] R8. `[FE]` `searchSelectionIds` 멤버십 조회를 `Set` 으로 → 체크박스 O(1). 동기화 effect 의 `selectedEqpIds.includes` 를 `Set` 으로 → filter O(n).
- [x] R9. `[FE]` **개수 가드** — 선택/배포 시점에 서버 상한(`max-targets-per-request`)과 정합한 개수 검증. 초과 시 raw 400 대신 "한 번에 최대 N대까지 배포 가능" 안내. (상한값 자체 변경은 Out of Scope.)
- [x] R10. `[FE]` 상호작용 동작 불변 — 전체선택/부분선택/추가/제거/파라미터 편집 등 기존 UX 그대로. 가상화로 off-screen 행 선택도 유지(SelectableTable 이 이미 지원).

## Constraints

- 헥사고날 경계 유지 (`*ModuleConfiguration` 의 `@Import` 패턴 준수).
- `EqpManagementSnapshot` 자체 변경 금지 — 다른 caller(`EqpController` 등)가 그대로 사용. (선례와 동일 제약.)
- `EqpManagementDbSupport.loadSnapshotByEqpId` / `findCurrentStateByEqpId` 단건 메서드 유지 — 단건 endpoint 가 사용.
- delta/시나리오/active-conflict 차단/스냅샷 영속(rollback 조건부 `captureTargetPreState`) 의미 불변. 성능만 개선.
- PostgreSQL IN-clause 파라미터 한도(32K) 내 안전 — 설비 1000개 미만 가정(선례와 동일).
- 서버 상한 `max-targets-per-request=100` 은 **안전장치로 유지**. 배치화로 효율이 확보돼도 값 상향은 별도 정책 결정(Out of Scope).
- ui-front: pnpm 미사용(npm). 테스트 import 는 `@/` 별칭. e2e 는 로컬 전용(CI 미실행).

## Acceptance Criteria

> Validate 결과(2026-07-06): 아래 [PASS]/[부분] 은 이번 검증 세션 실측. 명령별 출력은 dev-report §3 + Validate 세션 로그 참조.

### 백엔드 (BE)
- [PASS] `./gradlew :ui:tc-ui-core:test` PASS (DeployPlanningServiceTest 13/0/0, DeployServiceTest 24/0/0, ArchUnit 3/0/0).
- [PASS] `./gradlew :ui:adapter:tc-ui-db-adapter:test` PASS (LightBatch 3/0/0 실 PG **skip 아님**, BatchEquivalence 2/0/0).
- [PASS] `./gradlew build` PASS (전체, `-PuseLocalMaven`, BUILD SUCCESSFUL).
- [PASS] **동등성 테스트**: 서비스 계층(DeployPlanningServiceTest 배치화 후 무수정 GREEN) + 어댑터 계층(EqpManagementDbSupportBatchEquivalenceTest — 동일 store row 를 단건/배치 양 경로 주입, planner 필드 eqp/model/hsms/socket/plc/log/state/portStatus/param + param 정렬 동등 assert). Docker 가용으로 실 PG 테스트도 실행됨.
- [부분] **쿼리 수 기준** ≤ 30: **코드 구조 검증 완료** — preview=1(light IN), plan≈10~14(light IN 1 + eqp/model findAll 스캔 각 1~2(SCAN_PAGE_LIMIT=500, 설비<1000) + hsms/socket/plc/log/state/portStatus/param IN 7). 쿼리 수가 **타깃 수가 아닌 (테이블 수+페이지 수)에 비례**하여 구조적으로 ≤30. **라이브 SQL DEBUG 100-eqp 실측은 미수행**(러닝 앱+100설비 시드+인증 필요).
- [PASS] ArchUnit 위반 0건 (UiCoreArchitectureGuardTest 2/0/0 + UiCoreDependencyArchTest 1/0/0).

### 프론트 (FE)
- [PASS] `npm run test:unit` PASS (167 pass / 0 fail / 0 skip, 개수 가드 경계값 테스트 포함).
- [PASS] e2e(worst-case): 120대 mock 으로 등록 조회 → 전체 선택 → 선택에 추가 → **개수 가드 차단** + `page.on('console'/'pageerror')` error 0건 (deploy-register-bulk-guard.spec.ts 1 passed). ※ 100+ 는 가드가 팝업 진입 전 차단하므로 "시나리오 팝업 진입"은 유효 개수(≤100) 케이스인 deploy-register-scenario-modal.spec.ts 가 커버(동일 실행서 pass, 총 3 passed).
- [PASS] 가상화 활성: 렌더 distinct 행 수 `>0 && <120`(전체) — Playwright DOM 카운트 assert.
- [PASS] 개수 가드: "최대 100대" 안내 노출 + preview/create(서버 400) 미도달 assert.

## Out of Scope

- 서버 상한 `max-targets-per-request` **값 상향** — 정책 결정. 본 SPEC 은 효율화 + UI 안내까지.
- 등록 목록 조회의 `loadAllEqps()` 전 테이블 메모리 로드 → SQL lineKey 푸시다운([`JpaDeployRegisterQueryPort.java:62`](../../ui/adapter/tc-ui-db-adapter/src/main/java/com/nori/tc/ui/adapter/db/JpaDeployRegisterQueryPort.java)) — 별 SPEC 후보(우선순위 낮음).
- `captureTargetPreState` 스냅샷 build 배치화 — rollback step 존재 시에만 동작하는 조건부 경로. 필요 시 후속.
- 실제 배포 실행(step dispatch)/gateway forward 성능 — 별 영역.
- `EqpManagementSnapshot` 슬림화 — 영향 범위 큼, 향후 별 SPEC.

## SDD 진행 상태

- [x] **Specify** — 이 SPEC.md 작성 완료 (산출물: `.claude/specs/deploy-bulk-register-perf.md`)
- [x] **Plan** — 구현 계획 수립 완료 (산출물: `.claude/specs/deploy-bulk-register-perf-plan.md`)
- [x] **Implement** — AC 만족 구현 완료 (산출물: `.claude/specs/deploy-bulk-register-perf-dev-report.md`)
- [x] **Validate** — SPEC + Plan + Dev report 검증 완료(2026-07-06). AC 9/10 [PASS], AC 쿼리 수만 [부분](코드 구조 ≤30 검증, 라이브 실측 미수행). D1=B / D2 / D3 / D4=(c)후속 준수 확인. 불변식(EqpManagementSnapshot·단건 시그니처·delta/scenario 로직·신규 bean 0) 위반 없음.

## 다음 단계 진입 프롬프트

**Plan 진입 (새 세션 + Plan Mode)**:
```
@.claude/specs/deploy-bulk-register-perf.md 보고 구현 계획 수립.
영향 파일 / 단계 / 위험 / 롤백 방법 포함. BE(배치 포트/어댑터/서비스)와 FE(가상화/Set/개수가드) 분리.
선례 @.claude/specs/live-map-batch-query.md 의 batch prefetch 패턴 참고.
실행 / 코드 수정 금지.
산출물: .claude/specs/deploy-bulk-register-perf-plan.md 에 저장.
```

**Implement 진입 (새 세션)**:
```
@.claude/specs/deploy-bulk-register-perf.md @.claude/specs/deploy-bulk-register-perf-plan.md 둘 다 읽고 구현.
SPEC 의 Acceptance Criteria 모두 만족하도록 각 AC 당 실패 테스트 먼저(특히 delta/시나리오 동등성).
저위험 FE(가상화/Set/개수가드) 먼저 착수 권장. BE 배치화는 동등성 테스트 확보 후.
산출물: .claude/specs/deploy-bulk-register-perf-dev-report.md 에 변경 파일(레포별) / 테스트 결과 / 결정 기록.
```

**Validate 진입 (새 세션)**:
```
@.claude/specs/deploy-bulk-register-perf.md @.claude/specs/deploy-bulk-register-perf-plan.md @.claude/specs/deploy-bulk-register-perf-dev-report.md
셋 다 읽고, 구현이 SPEC 의 모든 Acceptance Criteria 와 Plan 의 결정을 만족하는지 검증.
누락 / 위반 발견 시 보고. 통과 항목은 SPEC 의 AC 체크박스 + SDD 진행 상태를 [x] 로 업데이트.
```

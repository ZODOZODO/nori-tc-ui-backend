# DEV REPORT: deploy-bulk-register-perf

> SDD Implement 산출물. 입력: [`deploy-bulk-register-perf.md`](deploy-bulk-register-perf.md) + [`deploy-bulk-register-perf-plan.md`](deploy-bulk-register-perf-plan.md).
> Validate 세션의 입력이다. 크로스레포: **common**=nori-tc-common, **BE**=nori-tc-ui-backend(앵커), **FE**=nori-tc-ui-front.
> 세 레포 모두 브랜치 `feature/deploy-bulk-register-perf` 에서 작업(main 직접 push 금지).

## 1. 변경 파일 목록 (레포별)

### common — nori-tc-common (Phase 3 D1=B 채택으로 추가)
- `db/tc-db-core/.../eqp/store/TcEqpHsmsStore.java` — `findAllByEqpKeyIn(Collection<Long>)` 추가
- `db/tc-db-core/.../eqp/store/TcEqpSocketStore.java` — 〃
- `db/tc-db-core/.../eqp/store/TcEqpPlcStore.java` — 〃
- `db/tc-db-core/.../eqp/store/TcEqpLogStore.java` — 〃
- `db/jpa/.../repository/eqp/TcEqp{Hsms,Socket,Plc,Log}JpaRepository.java` — `findByEqpKeyIn` derived query 4종
- `db/jpa/.../store/eqp/TcEqp{Hsms,Socket,Plc,Log}JpaStore.java` — `findAllByEqpKeyIn` 구현 4종(state 선례 동일)
- `db/mybatis/.../store/eqp/TcEqp{Hsms,Socket,Plc,Log}MybatisStore.java` — `findAllByEqpKeyIn` = `UnsupportedOperationException`(state 선례 동일; ui-backend 는 JPA 런타임)
- `db/jpa/.../test/.../store/eqp/TcEqp{Hsms,Socket,Plc,Log}JpaStoreBatchTest.java` — JPA 배치 단위 테스트 4종(신규)

### BE — nori-tc-ui-backend
- `ui/tc-ui-core/.../port/db/DeployPlanningStatePort.java` — `findCurrentLightStatesByEqpIds`(경량) + `findCurrentStatesByEqpIds`(전체) default 메서드 2종
- `ui/tc-ui-core/.../service/DeployPlanningService.java` — preview/plan 루프 진입 전 1회 배치 후 map lookup(delta/scenario/step 로직 불변)
- `ui/tc-ui-core/.../port/db/DeployStorePort.java` — `findLatestTargetSnapshots`(배치) 추가
- `ui/tc-ui-core/.../service/DeployService.java` — `resolveAsIsState` 배치화 + 라이브 fallback 중복 조회 제거
- `ui/adapter/.../db/DeployPlanningStateJdbcPort.java` — `CURRENT_STATE_SELECT` 상수 추출 + 경량/전체 배치 override
- `ui/adapter/.../db/EqpManagementDbSupport.java` — `loadSnapshotsByEqpIds`(배치 조립) 신규
- `ui/adapter/.../db/DeployJdbcStorePort.java` — `findLatestTargetSnapshots`(DISTINCT ON) 구현
- 테스트: `DeployPlanningServiceTest`(Phase 0 다중 target 3종), `DeployPlanningStateJdbcPortLightBatchTest`(신규, 실 PG), `EqpManagementDbSupportBatchEquivalenceTest`(신규), `DeployServiceTest`(fake store 배치 메서드)

### FE — nori-tc-ui-front
- `src/features/deploy/lib/deploy-register-selection.ts` — `MAX_DEPLOY_TARGETS_PER_REQUEST=100` + `willExceedMaxTargets` 추가(D3 단일 출처)
- `src/features/deploy/components/DeployRegisterPanel.tsx` — 가상화 2곳 + Set 멤버십 2곳 + 개수 가드 2곳
- `src/i18n/locales/{ko,en,vi}.json` — `deploy.register.maxTargetsExceeded` 키
- `tests/unit/deploy-register-selection.test.ts` — 개수 가드/상수 단위 테스트 보강
- `tests/e2e/deploy/deploy-register-bulk-guard.spec.ts` — worst-case(120대) e2e (로컬 전용, git 미추적)

## 2. 커밋 경계 (Phase 독립)

| 커밋 | 레포 | 내용 |
|---|---|---|
| C0 | BE | Phase 0 다중 target 동등성 baseline |
| C1 | FE | Phase 1 가상화 + Set + 개수 가드 |
| C2 | BE | Phase 2 preview 경량 배치 |
| C3 | common + BE | Phase 3 plan 전체 배치 (common 배치 store + BE 조립/서비스) |
| C4 | BE | Phase 4 폴링 as-is 배치 |

> C3 는 common 선행(배치 store) → `publishToMavenLocal` → BE 소비 순서. 롤백 시 BE 먼저 revert.

## 3. Phase별 빌드/테스트 결과 (PASS + 출력 인용)

### Phase 0 (C0) — `./gradlew :ui:tc-ui-core:test --tests DeployPlanningServiceTest`
```
> Task :ui:tc-ui-core:test
BUILD SUCCESSFUL in 32s
```
`tests="13" skipped="0" failures="0" errors="0"` (신규 3종 포함, 현 per-eqp 코드 기준 GREEN).

### Phase 1 (C1) — `npm run test:unit` + tsc + eslint + e2e
```
# tests 167
# pass 167
# fail 0
```
```
TSC_EXIT=0
ESLINT_EXIT=0
```
e2e worst-case: `1 passed (38.6s)` + 기존 deploy e2e `3 passed`. (가상화 DOM 행<전체 + 개수 가드 안내 + 서버 400 미도달 + console/pageerror 0)

### Phase 2 (C2) — core + 어댑터 경량 배치
```
> Task :ui:adapter:tc-ui-db-adapter:test
BUILD SUCCESSFUL in 32s
```
`DeployPlanningStateJdbcPortLightBatchTest` `tests="3" skipped="0" failures="0" errors="0"` (실 PostgreSQL, Docker 가용 → 단건↔배치 record 동등).

### Phase 3 (C3) — common 배치 store + BE 조립
```
> Task :db:jpa:tc-db-jpa-common-schema:test
BUILD SUCCESSFUL in 27s
```
4 JPA 배치 테스트 각 `tests="2" failures="0"`.
```
> Task :ui:adapter:tc-ui-db-adapter:test
BUILD SUCCESSFUL in 27s
```
`EqpManagementDbSupportBatchEquivalenceTest` `tests="2" failures="0"` (단건↔배치 planner 필드 동일 + param 정렬), `DeployPlanningServiceTest` `tests="13"` **무수정 GREEN 유지**(서비스 계층 동등성).

### Phase 4 (C4) — 폴링 배치
```
> Task :ui:tc-ui-core:test
BUILD SUCCESSFUL in 22s
```
`DeployServiceTest` `tests="24" skipped="0" failures="0" errors="0"` (as-is 값 before/after 동일).

### 전체 회귀 게이트
```
> Task :ui:starter:tc-ui-backend-starter:build
BUILD SUCCESSFUL in 1m 30s
```
- BE `./gradlew build -PuseLocalMaven` PASS (ArchUnit 위반 0 — `check`/archunit-conventions 포함).
- common 변경 모듈 `:db:jpa:...:test :db:mybatis:...:test :db:tc-db-core:build` PASS.
- `-PuseLocalMaven` 통합: BE 가 republish 된 common(신규 배치 store) 소비해 빌드/테스트 통과 = 크로스레포 통합 검증 완료.

## 4. *ModuleConfiguration @Import 변경

- **추가/제거 없음.** BE·common 모든 main 코드 변경은 **기존 빈(@Repository/@Service/@Component)에 메서드 추가**뿐 — 신규 bean 클래스 0개.
  - 검증: `git diff main -- '*/src/main/*.java' | grep '^+.*@(Component|Service|Repository|RestController|Controller)'` → **NONE**.
- 신규 파일은 전부 test 클래스(스캔 대상 아님). → `@Import` 배열 무영향, `validate-module-imports` MISSING 0(구조적).

## 5. D1~D4 실제 채택안 + 사유

- **D1(Phase 3 데이터 공급)**: 기본값 A(어댑터 raw SQL) → **B(common 배치 store 추가)로 변경.**
  - **사유(사용자 결정)**: hsms/socket/plc/log store 가 `findByEqpKey` 단건만 보유(검증 완료) → A 는 5개 도메인 record(PLC enum 2 + enabledPollGroups JSON 포함)를 raw SQL 로 **손매핑**해야 하고, 이 결과가 실행 시 gateway/business 로 forward 되는 **영속 payload** 라 미세 drift 위험이 큼. B 는 검증된 MyBatis/JPA 매핑을 IN 절로 재사용 → 바이트 동등 보장. state/portStatus/param 선례와 동일 패턴. blast radius=common JPA/MyBatis 2곳(다운스트림 implementor 0, grep 확인).
  - eqp/model 은 `findAll` 스캔 재사용(loadAllEqps/loadAllModels 선례, 동일 매퍼) → common 추가 불필요.
- **D2(경량 상태 표현)**: 기본값 유지 — `DeployCurrentState` 재사용, equipmentProfile=null. 신규 record 미도입.
- **D3(FE 상한)**: 기본값 유지 — `MAX_DEPLOY_TARGETS_PER_REQUEST=100` 단일 상수 + BE config 동기 주석. 신규 엔드포인트 없음.
- **D4(Phase 4 캐시)**: **(c) as-is 캐시 후속 분리**(기본값의 fallback 채택).
  - **사유**: 폴링 완화 목적은 (a) `findLatestTargetSnapshots` 배치 + (b) 라이브 fallback 중복 조회 제거로 달성. 캐시는 terminal 판정/TTL/무효화 복잡도 대비 모니터링 전용 경로의 부가 이득이라 범위 분리. **§6 후속 항목.**

## 6. 미완료 / 후속 분리 항목

1. **D4 (c) as-is overview 캐시** — deploy terminal 시 `summaryCacheTtlSeconds` TTL 캐시. 후속 SPEC/커밋.
2. **Phase 4 (b) 라이브 fallback 배치화** — 현재는 per-target `findSnapshotByEqpId` 1회(중복만 제거). 완전 배치는 Phase 3 로더가 `businessJar` 를 생략(planner 미사용)하는 반면 fallback 은 `businessJar.jarFileName` 이 필요해 재사용 불가 → jar 포함 별도 배치 로더가 필요. fallback 은 pre-state 미캡처(NO_CHANGE 등) 예외 경로라 우선순위 낮음.
3. **근원 A 기능적 미해결** — 100 초과 배포는 여전히 불가(서버 상한 상향 = Out of Scope, 정책 대기). FE 가드는 raw 400 → 안내로 UX 개선까지만.

## 7. 쿼리 수 측정 (AC: preview/plan 100건 ≤ 30)

> 라이브 100-eqp SQL DEBUG 로그 실측은 Validate 세션 과제. 아래는 **코드 레벨 SQL 발행 수** 산정.

- **preview 100건**: `findCurrentLightStatesByEqpIds` = light IN **1 SELECT**. `loadSnapshotByEqpId` 미호출. → **1** (기존 ~1,400) ≤ 30. ✓
- **plan 100건**: `findCurrentStatesByEqpIds` = light IN 1 + `loadSnapshotsByEqpIds`{eqp findAll(~1–2) + model findAll(~1–2) + hsms/socket/plc/log IN(4) + state/portStatus/param IN(3)} ≈ **11–14** (기존 ~1,400) ≤ 30. ✓
- **summary 폴링**: as-is per-target `findLatestTargetSnapshot`(N) → **1 배치**(DISTINCT ON). 라이브 fallback 은 target당 2→1.

## 8. 불변식 준수 확인

- `EqpManagementSnapshot` record 정의 / `loadSnapshotByEqpId` 단건 / `findCurrentStateByEqpId` 단건 시그니처 — **불변**(배치는 신규 메서드로 병존).
- delta/scenario/step 계산 로직(`calculateDeltaKinds`/`resolveScenario`/`resolveSteps`/`isReconnectStepAllowed`) — **불변**, 데이터 공급 방식만 변경. Phase 0 테스트가 배치화 전후 무수정 GREEN 으로 기계 검증.
- 배치 스냅샷은 단건과 **동일 매퍼/param 정렬** 재사용 → toEquipmentProfile(영속 payload) 동등(어댑터 동등성 테스트로 증명).

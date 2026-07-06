# PLAN: deploy-bulk-register-perf

> SDD Plan 산출물. 입력 SPEC: [`deploy-bulk-register-perf.md`](deploy-bulk-register-perf.md). 선례: [`live-map-batch-query.md`](live-map-batch-query.md).
> 크로스레포: **BE** = nori-tc-ui-backend(앵커), **FE** = nori-tc-ui-front. 일부 결정은 **common**(nori-tc-common, tc-db) 에 파급 — §3 / §5 참조.
> 이 문서는 계획만 담는다. 코드 수정 / 빌드 / 실행 없음. Implement 세션의 입력이다.

## 0. 근원 → Phase 매핑 (SPEC Why 4개 커버 확인)

| 근원 | 내용 | 커버 Phase |
|---|---|---|
| A | 100대 하드 리밋 → 생성 실패 (preview `:102-105` / plan `:69-72` throw) | Phase 1 (FE 개수 가드로 **raw 400 → 안내로 개선**) — ⚠️ 기능적 >100 배포는 **여전히 불가**(상한 상향 = Out of Scope, 정책 대기). "B라인 전체 한 번에 배포"가 100 초과면 이 SPEC 범위로 미해결 |
| B | planner per-eqp N+1 × (preview + create) ≈ 3,000 순차 쿼리 | Phase 2 (preview 경량 배치) + Phase 3 (plan 전체 배치) |
| C | 폴링 N+1 (`resolveAsIsState` per-target 스냅샷) | Phase 4 |
| D | 프론트 비가상화 + O(n²) | Phase 1 |

## 1. 영향 파일 목록 (레포별)

### BE — nori-tc-ui-backend

| 파일 | 무엇을 / 왜 |
|---|---|
| `ui/tc-ui-core/.../port/db/DeployPlanningStatePort.java` | 배치 조회 메서드 2종(`default`) 추가 — 경량(preview) / 전체(plan). 단건 유지. `default` 로 두어 기존 테스트의 lambda 생성 호환 보존(R1). |
| `ui/tc-ui-core/.../service/DeployPlanningService.java` | preview 루프(`:119-121`) → 사전 1회 경량 배치 조회 후 map lookup. plan 루프(`:77-79`/`planTarget:201`) → 사전 1회 전체 배치 조회 후 map lookup. **delta/scenario/step 계산 로직 불변**, 데이터 공급만 변경(R2/R3/R4). |
| `ui/adapter/tc-ui-db-adapter/.../DeployPlanningStateJdbcPort.java` | 배치 메서드 2종 구현. 경량=단일 IN-clause SQL(현 단건 SQL 을 `IN (:eqpIds)` 로 확장, equipmentProfile 미로드). 전체=경량 IN + `loadSnapshotsByEqpIds` 배치 재사용 + 기존 `toEquipmentProfile` per-eqp(R2/R3). |
| `ui/adapter/tc-ui-db-adapter/.../EqpManagementDbSupport.java` | 신규 `loadSnapshotsByEqpIds(List<String>) → Map<String,EqpManagementSnapshot>` 배치 조립. **기존 per-eqp 매퍼 재사용**으로 결과 동등성 보장. 단건 `loadSnapshotByEqpId` 유지(제약). |
| `ui/tc-ui-core/.../service/DeployService.java` | `resolveAsIsState`(`:213-280`) per-target `findLatestTargetSnapshot` → 배치 1회. 라이브 fallback(`resolveInterfaceType`/`addAsIsFromLiveSnapshot`, `:289-315`)의 per-eqp `findSnapshotByEqpId` → 배치 스냅샷 로더 재사용(R5). |
| `ui/.../port/db/DeployStorePort.java` (인터페이스) + `DeployJdbcStorePort.java` (구현) | `findLatestTargetSnapshots(deployKey, scope, kind) → Map<targetKey, snapshot>` 배치 메서드 추가(raw SQL, ui-backend 내). |
| `ui/.../port/db/EqpCrudPort.java` + `JpaEqpCrudPort.java` | (조건부) 라이브 fallback 배치용 `findSnapshotsByEqpIds` 위임 추가 — `EqpManagementDbSupport.loadSnapshotsByEqpIds` 재노출. Phase 4 에서만. |
| `UiDbAdapterModuleConfiguration.java` | **신규 `@Component`/`@Repository` 추가 시에만** `@Import` 등록. 위 변경은 모두 **기존 빈 수정**이라 신규 등록 불필요(§3.3 확인). |

> `bin/` `build/` 하위 `.class` 는 산출물 — 편집 대상 아님(grep noise).

### FE — nori-tc-ui-front

| 파일 | 무엇을 / 왜 |
|---|---|
| `src/features/deploy/components/DeployRegisterPanel.tsx` | (R7) 검색 테이블(`:1331`) + 선택 테이블(`:1379`)에 `features={{ virtualization: true }}`. (R8) 체크박스(`:840`)의 `searchSelectionIds.includes` → 기존 `selectedSearchItemIdSet.has`(이미 `:254` 존재) 재사용. 동기화 effect(`:294`)의 `selectedEqpIds.includes` → 신규 `selectedEqpIdSet` memo. (R9) `handleAddSelected`(`:747`)/배포 진입에 개수 가드. |
| `src/features/deploy/lib/` 또는 `constants` | (R9) 서버 상한 상수 `MAX_DEPLOY_TARGETS_PER_REQUEST = 100` 단일 출처(§5 D3 결정). |
| `tests/unit/deploy-register-selection.test.ts` (기존) | Set 멤버십 / 개수 가드 단위 테스트 보강. |
| `tests/e2e/deploy/*.spec.ts` (기존 dir) | worst-case(100+ mock) 등록→전체선택→추가→시나리오 팝업 진입 e2e + 가상화 DOM 카운트 + 개수 가드 안내. (e2e 로컬 전용) |

## 2. Phase 분해 (권장 순서 + 근거 + 완료 검증)

원칙: **저위험 FE 먼저 → 백엔드는 동등성 안전망 확보 후 배치화**. 각 Phase 독립 커밋(§4.4).

### Phase 0 — 백엔드 동등성 안전망 (BE, 최우선)

- **무엇**: 배치화 이전에, 고정 fixture(다중 target: NO_CHANGE / MODEL_CHANGED / BUSINESS_JAR / EQP_PARAM / PLC 모델변경 / RECONNECT 허용·차단 controlState 등 대표 조합)에 대한 preview·plan 결과 스냅샷 테스트를 `DeployPlanningServiceTest` 에 추가.
- **왜**: 배치화의 유일한 리스크는 "데이터 공급 방식이 바뀌며 delta/scenario/step 결과가 미묘하게 달라지는 것". 변경 전 baseline 을 고정해야 Phase 2/3 의 동등성을 기계 검증할 수 있다(SPEC AC 동등성).
- **검증**: `./gradlew :ui:tc-ui-core:test` PASS. 테스트는 현 코드(per-eqp) 기준으로 먼저 GREEN. Phase 2/3 후에도 **수정 없이** GREEN 유지 = **서비스 계층(loop→map lookup) 동등성** 증명.
- ⚠️ **증명 범위 한계**: 이 테스트는 포트를 lambda 로 stub(`DeployPlanningServiceTest:54`)하므로, 서비스가 default 배치를 호출해도 default 가 다시 단건 lambda 를 loop 한다 → **실제 어댑터 배치 SQL 은 이 테스트에 태워지지 않는다**. 따라서 Phase 0 GREEN 은 어댑터 배치 SQL 동등성의 **필요조건일 뿐 충분조건이 아님**. 어댑터 계층 동등성은 Phase 2/3 의 **어댑터 단위 테스트(단건 SELECT ↔ 배치 IN 결과 동일 검증)로 별도 증명** 필수.
- **주의**: 테스트 포트는 현재 lambda(`eqpId -> Optional.of(...)`, `DeployPlanningServiceTest:54`). fixture 는 eqpId→상태 map 기반 stub 으로 확장(배치 default 가 단건을 호출하므로 stub 이 단건만 구현해도 배치 경로가 동작).

### Phase 1 — FE 가상화 + Set + 개수 가드 (FE, 저위험)

- **무엇**: R7(가상화 prop) + R8(Set 멤버십) + R9(개수 가드). 상호작용 동작 불변(R10).
- **왜 먼저**: 백엔드 무관·독립 배포 가능. 근원 A(생성 실패)의 UX 방어(raw 400 → 안내)와 근원 D(버벅임)를 즉시 해소. `SelectableTable` 가상화는 이미 배선(`SelectableTable.tsx:297` `useGridVirtual`, off-screen drag 선택까지 지원) → prop 부여만으로 활성.
- **검증**: `npm run test:unit` PASS + e2e worst-case(§AC FE). 가상화는 Playwright DOM 행 수 < 전체 데이터 수로 검증. `page.on('console'/'pageerror')` error 0건.

### Phase 2 — BE preview 경량 배치 (BE)

- **무엇**: preview 는 `calculateDeltaKinds(EQP_REGISTER_DEPLOY)` 가 `modelVersionKey / businessJarRef / appliedParamVersion / interfaceType` 4필드만 사용(코드 `:427-440` 확인). `injectEquipmentProfile` 이 message 에 profile 노드를 넣지만 **preview 출력(removed/remaining/union/recommended)에는 무영향**(desiredState 는 uiMessage 의 modelInfo/businessJar/eqpParams/gatewayProfile 만 읽고 equipmentProfile 노드는 안 읽음). → equipmentProfile 없는 경량 상태로 충분.
- **어떻게**: preview 진입 시 `deployPlanningStatePort.findCurrentLightStatesByEqpIds(eqpIds)` 1회 → `Map<String,DeployCurrentState>`(equipmentProfile=null). 루프는 map lookup 으로 대체. 어댑터는 현 단건 SQL 을 `WHERE e.eqp_id IN (:eqpIds)` 로 확장(socket/hsms join 유지, `loadSnapshotByEqpId` **미호출**).
- **검증**: Phase 0 동등성 테스트 GREEN 유지 + preview 100건 SQL DEBUG 라인 ≤ 30. `:ui:adapter:tc-ui-db-adapter:test` 에 IN-절 배치 어댑터 단위 테스트(빈 입력·부분 매칭·누락 eqpId 처리) 추가.

### Phase 3 — BE plan 전체 배치 (BE, 최고 난도)

- **무엇**: plan 은 `injectEquipmentProfile`(`:683`)·`isReconnectStepAllowed`(`:580` controlState)에서 **full equipmentProfile 필요** → 경량으로 대체 불가.
- **어떻게**: plan 진입 시 `findCurrentStatesByEqpIds(eqpIds)` 1회. 어댑터는 경량 IN + `EqpManagementDbSupport.loadSnapshotsByEqpIds(eqpIds)` 배치 + 기존 `toEquipmentProfile` per-eqp(in-memory, 쿼리 아님).
- **`loadSnapshotsByEqpIds` 조립**: eqp `IN` 1회 → eqpKey 수집 → 각 sub-table 을 key/version `IN` 으로 1회씩. `toEquipmentProfile` 이 실제 사용하는 테이블만: eqp, model(modelKey), hsms, socket, plc, log, state, stateHist(latest CONN), portStatus, param. (jar·paramVersion 은 `toEquipmentProfile` 미사용 — §4.1 확인 → 배치 제외 가능.)
- **배치 store 가용성**(grep 확인): state/stateHist/portStatus/param 은 `findAllByEqpKeyIn` **존재**. hsms/socket/plc/log/model 은 **부재** → §5 **D1 결정** 필요(신규 tc-db 배치 store vs 어댑터 raw SQL).
- **동등성 핵심**: `loadSnapshotsByEqpIds` 는 단건과 **동일 매퍼**(`UiEqpDbMapper`/`UiModelDbMapper`)·**동일 정렬/필터**(param version desc, controlState 매핑) 재사용해야 함. raw SQL 직접 매핑은 미묘한 차이 위험 → 매퍼 재사용 우선.
- **검증**: Phase 0 동등성 GREEN 유지(target별 deltaKinds/scenarioCode/resolvedSteps 동일) + plan 100건 SQL DEBUG ≤ 30 + 어댑터 배치 단위 테스트(선례 `JpaLiveMapPrefetchPortTest` 템플릿).

### Phase 4 — BE 폴링 완화 (BE, 낮은 우선순위)

- **무엇**: (a) `resolveAsIsState` 의 per-target `findLatestTargetSnapshot` → `findLatestTargetSnapshots(deployKey,...)` 배치 1회. (b) 라이브 fallback per-eqp `findSnapshotByEqpId` → Phase 3 배치 로더 재사용. (c) as-is overview 캐시(§5 **D4 결정**).
- **왜 마지막**: 데이터 평면 무영향(모니터링 조회만), 캐시 의미(as-is 언제 고정?)에 판단 필요. 독립 배포 가능.
- **검증**: `:ui:tc-ui-core:test`(`DeployServiceTest`) PASS + summary 1회 폴링 SQL 라인 대폭 감소(수치는 Validate 에서 측정). as-is 값 before/after 동일.

## 3. 포트/어댑터 설계 결정

### 3.1 `DeployPlanningStatePort` 시그니처 (경량 1 + 전체 1 분리)

```java
public interface DeployPlanningStatePort {
    Optional<DeployCurrentState> findCurrentStateByEqpId(String eqpId);       // 유지(제약)

    // preview 전용: equipmentProfile=null 경량. default=단건 loop(테스트 lambda 호환).
    default Map<String, DeployCurrentState> findCurrentLightStatesByEqpIds(List<String> eqpIds) { ... loop ... }

    // plan 전용: equipmentProfile 포함 전체. default=단건 loop.
    default Map<String, DeployCurrentState> findCurrentStatesByEqpIds(List<String> eqpIds) { ... loop ... }
}
```

- **경량/전체 분리 이유**: preview 가 heavy `loadSnapshotByEqpId` 를 피하는 것이 근원 B 절감의 핵심(R2). 단일 전체 메서드로 통합하면 preview 도 heavy 로드 → 목적 상실. SPEC R1 "(또는 경량/전체 2종)" 이 명시 허용.
- **반환 `Map<String, DeployCurrentState>`**: eqpId 키. 누락 eqpId(미존재 설비)는 map 에서 부재 → 서비스가 `Optional.ofNullable(map.get(id))` 로 감싸 기존 `validateCurrentStatePrecondition` 의 present/absent 분기 그대로 재사용(동등성).
- **`default` loop fallback 이유**: `DeployPlanningStatePort` 는 현재 단일 추상 메서드 → 테스트가 lambda 로 생성(`DeployPlanningServiceTest:54`). 추상 메서드 2개 추가 시 lambda 깨짐 + 다른 stub 전부 수정. `default` 면 기존 lambda 그대로, 실 어댑터만 override 로 최적화. (미override 시 correctness 는 유지, perf 만 loop — 어댑터가 반드시 override.)

### 3.2 단건 `findCurrentStateByEqpId` 유지 확인

- 워크스페이스 전체 grep 결과 유일 caller = `DeployPlanningService`(+포트/구현/SPEC). 크로스레포 코드 의존 없음(HTTP API 만). 그러나 SPEC 제약이 명시 유지 요구 → **삭제/시그니처 변경 금지**. 배치 메서드가 이를 대체하되 단건은 존치(회귀 표면 최소화).

### 3.3 ModuleConfiguration `@Import` 등록

- 위 BE 변경은 **모두 기존 빈 수정**(`DeployPlanningStateJdbcPort`/`EqpManagementDbSupport`/`DeployJdbcStorePort`/`JpaEqpCrudPort` 는 이미 `UiDbAdapterModuleConfiguration` `@Import` 에 등록됨, `:12-47` 확인). **신규 클래스가 생기지 않으면 `@Import` 수정 불필요**(R6).
- **예외**: §5 D1 이 "신규 배치 어댑터 클래스" 안을 택하면 그 클래스를 `@Import` 에 반드시 추가(선례: `JpaLiveMapPrefetchPort.class` 등록됨). tc-db 배치 store 안이면 store 는 nori-tc-common 의 자체 config 관할 → ui-backend `@Import` 무관.
- PR 전 `validate-module-imports` skill 로 누락 자동 점검.

### 3.4 벌크 SQL 을 기존 `loadAll*` / prefetch 패턴과 정합

- 경량 배치(Phase 2): `DeployPlanningStateJdbcPort` 의 기존 단건 raw SQL 을 `IN` 으로 확장 — 같은 클래스, 같은 스타일. 신규 store 불필요.
- 전체 배치(Phase 3): 선례 `JpaLiveMapPrefetchPort`(eqpKey `IN` fan-out + in-memory group-by) 패턴을 `EqpManagementDbSupport.loadSnapshotsByEqpIds` 로 이식. 단, live-map 은 이미 배치 store 가 있는 6테이블만 썼고, snapshot 은 hsms/socket/plc/log/model 추가 필요 → D1 이 그 gap 을 해소.

## 4. 위험과 롤백

### 4.1 회귀 위험 지점 (의미 불변 사수)

1. **delta 동등성** — `calculateDeltaKinds` 는 currentState 의 light 필드만 사용. 배치 경량 상태가 단건과 동일 필드값(comm_interface/comm_mode/eqp_ip/eqp_port/model_version_key/applied_param_version/gateway·business_jar_key/socket_protocol_type/hsms_device_id/route_partition)을 반환해야 함. → 경량 SQL 은 단건 SQL 과 **동일 SELECT 절** 유지.
2. **RECONNECT step 필터** — `isReconnectStepAllowed` 가 `equipmentProfile.currentStateSnapshot.controlState` 참조. 배치 전체 상태의 controlState 가 단건과 동일해야 resolvedSteps 동등. → state 배치는 `findAllByEqpKeyIn` 사용(이미 검증된 store).
3. **injectEquipmentProfile 영속 의미** — plan 이 만드는 desiredState JSON(`objectMapper.valueToTree`)은 실제 배포 실행에 쓰이는 영속물. equipmentProfile 전 필드(hsms t3-t8/socket timeouts/plc tuning/params/portStatuses/desiredState)가 단건과 **바이트 동일**해야 함. → §3.3 "매퍼 재사용" 이 이를 보장. raw SQL 재매핑 선택 시 필드 누락·타입·정렬 차이 리스크 ↑.
4. **jar/paramVersion 배치 제외 근거** — `toEquipmentProfile`(`DeployPlanningStateJdbcPort:96-182`)은 `snapshot.gatewayJar()/businessJar()/paramVersionMetas()` 를 **읽지 않음**(jarKey 는 eqp 행에서 직접). 따라서 배치 로더는 이 3테이블 제외 가능 → 쿼리 수 절감. **단 Implement 시 재확인 필수**(§6 매핑).
5. **active-conflict 차단** — planner 경로 무관(등록 목록 조회의 `findActiveEqpConflicts` 이며 이미 벌크, `JpaDeployRegisterQueryPort:65`). 본 변경 대상 아님 → 불변.
6. **NO_CHANGE 제거 로직** — preview 의 `removedEqpIds` 분기(`:142-145`)는 deltaKinds 만 의존 → 데이터 공급 무관, 불변.
7. **FE off-screen 선택**(R10) — 가상화 시 미렌더 행의 선택 상태. `SelectableTable` 이 이미 지원(`:305-348` drag pointer 매핑). 전체선택은 `selectableSearchItemIds`(전체 데이터 기반, `:247`)로 계산 → DOM 렌더와 무관하게 유지.

### 4.2 `EqpManagementSnapshot` 변경 금지

- 다른 caller(`EqpController`/`JpaEqpManageQueryPort`/`JpaEqpCrudPort`) 가 그대로 사용. 신규 `loadSnapshotsByEqpIds` 는 **동일 record 를 batch 로 만들 뿐** record 정의는 불변. 확인 완료(제약 준수).

### 4.3 PostgreSQL IN-clause 한도 / 라인 필터 시점

- 파라미터 한도 32K. 설비 1000 미만 가정(SPEC) + 서버 상한 100 → target 100개 IN 은 여유. eqpKey fan-out 도 ≤100 keys. 안전.
- 라인 필터는 등록 **목록 조회**(`JpaDeployRegisterQueryPort`) 단계에서 이미 적용(`:63`). planner 는 이미 선택된 eqpId 리스트를 받으므로 라인 필터 무관 → 배치에 lineKey 개입 없음. (목록 조회의 `loadAllEqps` 메모리 로드 SQL 푸시다운은 Out of Scope.)

### 4.4 커밋 경계 (Phase 독립 롤백)

- C0: Phase 0 동등성 테스트 (BE, 단독 GREEN)
- C1: Phase 1 FE 3종 (FE, 독립 배포)
- C2: Phase 2 preview 경량 배치 (BE)
- C3: Phase 3 plan 전체 배치 (+ D1 이 tc-db 안이면 common 선행 커밋/publish)
- C4: Phase 4 폴링 (BE)
- 각 커밋은 그 Phase 검증 GREEN 후 분리. 문제 시 해당 커밋만 revert(앞 Phase 무영향). D1=tc-db 안이면 C3 는 common publish 의존 → 롤백 시 common 버전 롤백 순서 주의.

## 5. 열린 결정 (기본값 + 근거 → Validate 게이트 바인딩)

> 각 항목은 **기본값으로 진행**하되, Implement 착수 시 재검토하고 Validate 에서 준수 확인.

### D1 — Phase 3 전체 배치의 데이터 공급 방식 (핵심)

- **기본값(권장): 어댑터 raw SQL 배치를 `EqpManagementDbSupport.loadSnapshotsByEqpIds` 내부에 구현하되, 조립은 기존 `UiEqpDbMapper`/`UiModelDbMapper` 재사용.** hsms/socket/plc/log/model 은 배치 store 부재 → 이 5테이블만 `NamedParameterJdbcTemplate` IN-절 raw SQL 로 읽어 매퍼에 투입, state/stateHist/portStatus/param 은 기존 `findAllByEqpKeyIn` store 재사용.
- **근거**: (1) nori-tc-common(tc-db) 변경·publish·버전범프·다운스트림 13레포 파급을 회피 → 이번 SPEC 범위(ui-backend 성능)에 집중. (2) `DeployPlanningStateJdbcPort` 가 이미 raw SQL 패턴을 확립. (3) 매퍼 재사용으로 동등성 확보 → raw SQL 은 "행 로드"만, "도메인 매핑"은 검증된 매퍼가 담당. (4) ≤30 쿼리 AC 달성(테이블당 IN 1회 ≈ 10쿼리).
- **대안**: tc-db 에 `findAllByEqpKeyIn`(hsms/socket/plc/log) + `findAllByModelVersionKeyIn`(model) 배치 store 추가(선례 live-map 이 일부 store 에 이미 추가한 방식). 헥사고날상 더 정합하나 크로스레포 비용 큼. **Validate 에서 D1 최종 채택안 명시 확인.**
- **주의**: 어느 안이든 `-PuseLocalMaven` 통합 검증 필요할 수 있음([[project_ui_backend_uselocalmaven_flag]]).

### D2 — 경량 상태 표현: 별도 record vs `DeployCurrentState` 재사용

- **기본값(권장): `DeployCurrentState` 재사용, equipmentProfile=null.** record 는 equipmentProfile nullable(compact 생성자 null 체크 없음, `:24` 확인).
- **근거**: 신규 record 도입은 preview/plan 의 delta 계산 코드 분기를 유발(과도 추상화, CLAUDE.md §3/§11). 재사용하면 `calculateDeltaKinds` 등 공통 로직 무변경 → 동등성·surgical. 경량은 "equipmentProfile 만 비어있는 동일 타입"으로 충분.
- **리스크**: preview 경로에서 실수로 equipmentProfile 참조 시 NPE. → preview 는 이미 profile 미참조(§2 Phase2 확인). 방어적으로 preview 전용 배치가 profile 을 만들지 않음을 단위 테스트로 고정.

### D3 — FE 개수 가드 상한값 출처

- **기본값(권장): FE 상수 `MAX_DEPLOY_TARGETS_PER_REQUEST = 100`.** BE 기본값·라이브 config 모두 100(확인). BE 는 실제 상한을 계속 강제(백스톱).
- **근거**: 값 상향은 Out of Scope(정책). 현재 100 고정. 신규 노출 엔드포인트 추가는 표면 증가·범위 초과. 가드는 UX 사전 검증일 뿐 진짜 한도는 BE.
- **리스크**: ops 가 config 만 바꾸면 FE 상수 drift. → 상수에 "BE `tc.ui.backend.deploy.max-targets-per-request` 와 동기 필요" 주석 명시. **Validate 에서 상수-config 정합 확인.**
- **대안(비권장)**: 신규 `GET /api/deploy/config` 로 상한 노출 → drift 제거하나 BE/FE 양쪽 신규 표면 + 범위 확대.

### D4 — Phase 4 as-is overview 캐시 시점

- **기본값(권장): deploy 가 terminal 일 때만 as-is 캐시(deployKey 키, `summaryCacheTtlSeconds` TTL).** 진행 중(pre-state 스냅샷 미확정 구간)엔 캐시 안 함.
- **근거**: as-is 는 "배포 전 상태" — pre-state 스냅샷이 target 마다 순차 캡처되는 진행 구간에는 라이브 fallback 값이 섞여 변동 가능. 이 구간을 긴 TTL(기본 86400s, `:39`)로 캐시하면 transient 값 고정 위험. terminal 이후엔 as-is 불변 → 캐시 안전.
- **리스크 완화**: Phase 4 (b) per-target 배치화만으로도 폴링 비용 대부분 절감 → 캐시는 부가. 캐시 판단 어려우면 **Phase 4 (a)+(b) 배치화만 하고 (c) 캐시는 후속으로 분리** 가능(SPEC R5 "완화" 표현). **Validate 에서 캐시 도입 여부·시점 확인.**

## 6. Acceptance Criteria → 구현/테스트 매핑

| AC (SPEC §Acceptance Criteria) | 충족 Phase / 구현 | 검증 방법 |
|---|---|---|
| BE `:ui:tc-ui-core:test` PASS | Phase 0/2/3/4 | `./gradlew :ui:tc-ui-core:test` |
| BE `:ui:adapter:tc-ui-db-adapter:test` PASS (배치 어댑터 단위) | Phase 2/3 신규 어댑터 테스트 | `./gradlew :ui:adapter:tc-ui-db-adapter:test` |
| BE `./gradlew build` PASS | 전 Phase | `./gradlew build` |
| **동등성(서비스 계층)**: preview removed/remaining/union/recommended + plan target별 delta/scenario/steps 배치 전후 동일 | Phase 0 fixture 스냅샷(고정) → Phase 2/3 후 무수정 GREEN | `DeployPlanningServiceTest` snapshot |
| **동등성(어댑터 계층)**: 배치 IN 조회 결과가 단건 SELECT 와 필드·정렬·매핑 동일 | Phase 2/3 어댑터 단위 테스트 (Phase 0 로는 증명 불가 — lambda stub) | `:ui:adapter:tc-ui-db-adapter:test` (단건↔배치 결과 비교) |
| **쿼리 수**: preview 100건 ≤30, plan 100건 ≤30 | Phase 2/3 | SQL DEBUG 로그 라인 카운트(선례 방식) |
| ArchUnit 위반 0 | 전 Phase(신규 클래스 없음/등록 준수) | ArchUnit 테스트 + `validate-module-imports` |
| FE `npm run test:unit` PASS | Phase 1 | `npm run test:unit` |
| e2e worst-case(100+): 등록→전체선택→추가→시나리오 팝업, console/pageerror error 0 | Phase 1 | Playwright(로컬), worst-case mock |
| 가상화 활성: DOM 행 수 < 전체 데이터 수 | Phase 1 (R7) | Playwright DOM 카운트 |
| 개수 가드: 초과 시 안내 + 서버 400 미도달 | Phase 1 (R9, D3) | Playwright assertion |

## 7. Implement 착수 순서 (요약)

1. **C0** Phase 0 동등성 fixture (BE) — baseline GREEN.
2. **C1** Phase 1 FE(가상화/Set/개수가드) — 독립 배포 가능, 근원 A/D 즉효.
3. **C2** Phase 2 preview 경량 배치 (BE) — 동등성 GREEN 유지 + ≤30.
4. **C3** Phase 3 plan 전체 배치 (BE, D1 채택안) — 동등성 GREEN 유지 + ≤30.
5. **C4** Phase 4 폴링 배치 (+D4 캐시 판단) (BE).

> Implement 세션 진입 프롬프트는 SPEC `## 다음 단계 진입 프롬프트` 의 "Implement 진입" 사용. 이 Plan(`deploy-bulk-register-perf-plan.md`)을 SPEC 과 함께 @첨부.

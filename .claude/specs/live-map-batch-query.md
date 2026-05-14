# SPEC: live-map-batch-query

## Why

`GET /api/live-map` 응답 시간이 warm 5.13s / cold 11s 로 매우 느림. 사용자 체감 사용성 저하.

실측 root cause (2026-05-14):
- 응답 파일: [.claude/perf/2026-05-14-audit-full-perf.md](../perf/2026-05-14-audit-full-perf.md)
- 측정 산출물: [backend-baseline-2026-05-14T0502.prometheus.txt](../perf/measurements/backend-baseline-2026-05-14T0502.prometheus.txt), `C:\tc-trace\tc-ui-backend-app\jfr\manual-perf-baseline-2026-05-14T0504.jfr`
- 단일 trace ID 격리 측정: 1 live-map call = 54 EQP × `EqpManagementDbSupport.loadSnapshotByEqpId` (각 ~100ms) = ~5.4s
- `loadSnapshotByEqpId` 1회 ≈ 9~10 sub-store 호출 (eqpStore, modelStore, eqpHsmsStore, eqpSocketStore, eqpLogStore, eqpStateStore, eqpStateHistStore, eqpPortStatusStore, eqpParamStore, eqpParamVersionStore, jarGatewayStore, jarBusinessStore)
- 54 EQP × ~10 sub-store = **~540 sub-query per live-map call**
- 그러나 `LiveMapQueryService` 가 `snapshot` 에서 *실제 사용하는 필드는 3개* (`snapshot.eqp().{modelVersionKey,appliedParamVersion}`, `snapshot.params()`, `snapshot.portStatuses()`) — 9개 필드 over-fetching
- 추가 중복: `eqpQueryPort.findAll()` 으로 받은 eqp 가 manage_snapshot 안에서 `eqpStore.findByEqpId(eqpId)` 로 또 호출 / runtime_state 도 별도 호출이면서 manage_snapshot 안에도 존재

## Requirements

- [x] R1. LiveMapQueryService 가 `eqpManageQueryPort.findManageSnapshotByEqpId` 사용 중단. 대신 *live-map 전용 prefetch* 메서드 사용
- [x] R2. 신규 port 메서드 (또는 신규 port) — LiveMap 화면에 필요한 데이터만 batch 로 한 번에 prefetch
- [x] R3. batch 단위는 *전체 EQP 목록* — eqp 한 번 findAll 후 그 eqpKey 목록을 IN-clause 로 각 store 에 1회씩 호출
- [x] R4. `eqp_param` batch: 모든 EQP 의 `tc_eqp_param` 한 번에 (PROCESS_TYPE / WAFER_MODE 만)
- [x] R5. `eqp_port_status` batch: 모든 EQP 의 `tc_eqp_port_status` 한 번에
- [x] R6. `eqp_state` batch: 모든 EQP 의 `tc_eqp_state` 한 번에 (controlState / eqpState)
- [x] R7. `eqp_state_hist` batch: 모든 EQP 의 *최신 CONN type* state hist 한 번에 (또는 distinct eqpKey 별 latest by stateType=CONN)
- [x] R8. `tc_model_param` batch (fallback 용): distinct modelVersionKey 만 한 번에
- [x] R9. 응답 schema (`LiveMapResponse` / `LiveMapResponseSupport`) 변경 *없음*
- [x] R10. `LiveMapEquipmentView` (core domain) 필드 변경 *없음*
- [x] R11. 새 컴포넌트 추가 시 같은 모듈의 `*ModuleConfiguration` 의 `@Import` 에 명시 등록 (nori-tc-common CLAUDE.md 컨벤션) — `JpaLiveMapPrefetchPort.class` 가 `UiDbAdapterModuleConfiguration` `@Import` 에 등록됨
- [x] R12. 단위 테스트 — 기존 `LiveMapQueryServiceTest` 의 시나리오 모두 PASS (변경된 service 시그니처 반영) — 5 시나리오 PASS

## Constraints

- 헥사고날 경계 유지 (`*ModuleConfiguration` 의 `@Import` 패턴 준수)
- common 단방향 의존 — 다른 nori-tc-* 레포에 직접 의존 금지
- `EqpManagementSnapshot` 자체는 *변경 금지* — 다른 caller (`EqpController.getManageDetail` / `EqpController.getManageDetailBatch`) 가 그대로 사용
- `EqpManageQueryPort.findManageSnapshotByEqpId` 도 *유지* — 단건 조회 endpoint 가 사용
- 신규 SQL 은 `tc-db-jpa-common-schema` 또는 `tc-ui-db-adapter` 에 위치 결정 (Plan 단계에서 정함)
- batch IN-clause 의 길이 한도: PostgreSQL 의 32K parameter 한도 내 안전 (EQP 1000개 미만 가정)

## Acceptance Criteria

- `./gradlew :ui:tc-ui-core:test` PASS (LiveMap 관련 테스트 포함)
- `./gradlew :ui:adapter:tc-ui-db-adapter:test` PASS (신규 batch query 어댑터 단위 테스트 포함)
- `./gradlew build` PASS (전체)
- 측정: warm 5회 후 `curl -s -b cookies -o /dev/null -w "%{time_total}" http://localhost:8080/api/live-map` 의 응답 시간 **≤ 1.0s** (현재 5.13s 의 80% 이상 감소)
- 측정: prometheus `http_server_requests_seconds_count{uri="/api/live-map"}` 대비 응답 시간 max **≤ 2.0s** (cold 11s 의 80% 이상 감소)
- 측정: live-map 1회 호출의 SQL DEBUG 로그 라인 수 **≤ 30** (현재 ~108)
- 응답 JSON 의 13+4 필드 (eqpId, eqpName, lineValue, lineLabel, clusterId, clusterName, equipmentModeLabel, status, statusLabel, portItems [id, label, status, statusLabel], tooltipMeta, tooltipStep, alertMessage) 가 변경 전과 *데이터 동일* (snapshot 테스트로 검증)
- ArchUnit 위반 0 건

## Out of Scope

- `EqpController` 의 4-fan-out (`/eqp/{id}/manage,param-versions,runtime-state,checkout-status`) — 별 SPEC (`eqp-detail-bundle`)
- `/api/dlq/gateway` Redis SCAN — 별 SPEC (`dlq-gateway-cache`)
- `EqpManagementSnapshot` 자체 슬림화 — *현 단계 영향 범위 크다*. 향후 별 SPEC 후보
- Hibernate L2 cache 도입 — 별 SPEC
- ui-front 측 변경 — 응답 schema 동일, 변경 불필요

## SDD 진행 상태

- [x] **Specify** — 이 SPEC.md 작성 완료, 팀 리뷰 통과
- [x] **Plan** — 별 세션에서 Plan Mode 로 구현 계획 수립 (`~/.claude/plans/claude-specs-live-map-batch-query-md-sparkling-ocean.md`)
- [x] **Implement** — 별 세션에서 Acceptance Criteria 만족하는 구현
- [x] **Validate** — 실측 결과 [.claude/perf/2026-05-14-after-livemap-batch.md](../perf/2026-05-14-after-livemap-batch.md): warm 88 ms avg (≤ 1.0 s), cold 504 ms (≤ 2.0 s), sub-query 6/call (-98.9 % vs ~540), 5 unit-test 시나리오 PASS, ArchUnit 0 violation

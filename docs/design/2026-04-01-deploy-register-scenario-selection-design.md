# Deploy Register Scenario Selection Design

## 날짜

- 2026-04-01

## 문서 목적

- `Register` 화면의 deploy 를 기존 `EQP_UPDATE` 의미와 분리된 별도 도메인으로 설계합니다.
- deploy 생성 전 변경사항을 계산하고, 권장 시나리오를 제안한 뒤 사용자가 직접 시나리오를 선택하는 흐름을 정의합니다.
- backend 가 어떤 시나리오 catalog 구조와 preview 계약을 제공해야 하는지 정리합니다.

## 요청 배경

- 현재 `Register` 화면은 `eventType = EQP_BUSINESS_JARFILE_UPDATE` 로 고정되어 있습니다.
- 현재 구조에서는 `businessJar` 가 없는 경우 backend 검증에서 실패할 수 있습니다.
- 운영 관점에서는 변경 유형별로 미리 정의된 시나리오만 사용하고, deploy 1건은 하나의 시나리오만 타게 하는 편이 안전합니다.
- 다만 선택된 설비들의 변경 패턴이 서로 달라도, deploy 전체 시나리오는 하나를 선택하고 target 별 필요 없는 step 은 `skip` 할 수 있어야 합니다.

## 확인된 현재 구조

### scenario source

- scenario 정의 본문은 DB 가 아니라 YAML catalog 로 적재합니다.
- 기본 리소스 경로는 `ui/tc-ui-core/src/main/resources/deploy-scenarios` 입니다.
- 운영 시에는 `./runtime/ui-backend/deploy-scenarios` 디렉터리가 우선합니다.
- `scenarios.index` 에 등록된 파일만 classpath 에서 적재합니다.

### tc_deploy_scenario_catalog_meta 역할

- `tc_deploy_scenario_catalog_meta` 는 scenario 정의 본문 저장소가 아닙니다.
- 이 테이블은 `catalog_version`, `catalog_checksum`, `scenario_count`, `loaded_at`, `loaded_by`, `reload_reason` 같은 catalog 메타 정보만 관리합니다.
- 따라서 `modelChanged`, `businessJarChanged` 같은 시나리오 정의는 YAML 에 두고, meta 테이블은 버전 ledger 로 유지합니다.

### deploy 실행 구조

- target plan 은 `resolvedScenarioCode` 를 하나만 저장합니다.
- target plan 은 `resolvedStepPlanJson` 을 함께 저장합니다.
- step 상태에는 `SKIPPED` 가 이미 존재합니다.
- 따라서 `deploy 1건당 시나리오 1개 + target 별 step skip` 구조는 현재 저장 모델과 잘 맞습니다.

## 핵심 설계 결정

### 1. register deploy 는 별도 도메인으로 취급합니다

- `Register` 의 deploy 는 설비 연결정보 변경용 `EQP_UPDATE` 와 다른 개념으로 설계합니다.
- deploy 상위 이벤트 타입도 별도 값으로 분리하는 것을 권장합니다.
- 권장 이벤트 타입 예시: `EQP_REGISTER_DEPLOY`
- 이름은 구현 시점에 확정하되, 핵심은 `Register` 용 deploy planner 와 scenario catalog 를 `EQP_UPDATE` 와 분리하는 것입니다.

### 2. deploy 1건은 시나리오 1개만 선택합니다

- 하나의 deploy 에 여러 시나리오를 조합하지 않습니다.
- 사용자는 deploy 생성 전에 권장 시나리오를 확인하고 직접 하나를 선택합니다.
- 최종 선택된 시나리오 코드는 deploy create 요청에 포함됩니다.

### 3. target 별로 필요 없는 step 은 skip 합니다

- 선택된 설비들이 모두 같은 변경 패턴일 필요는 없습니다.
- deploy 전체는 하나의 시나리오를 타되, 각 target 에 대해 실제 변경이 없는 step 은 `SKIPPED` 로 처리합니다.
- 즉, 변경 패턴이 다른 설비가 섞여 있어도 deploy 생성 자체를 막지 않습니다.

### 4. 변경이 없는 설비는 deploy 전에 제거합니다

- Deploy 버튼 클릭 시 설비별 변경사항을 계산합니다.
- 변경이 없는 설비는 `Selected Equipments` 에서 제거합니다.
- 제거 후 남은 설비가 0대이면 deploy 를 생성하지 않고 사용자에게 안내합니다.

### 5. 시나리오 선택은 popup 이 아니라 사전 선택 모달에서 수행합니다

- 현재처럼 create 실패 후 보정용 dialog 를 띄우는 방식은 사용하지 않습니다.
- Deploy 버튼 클릭 시 preview 결과를 기반으로 시나리오 선택 모달을 먼저 띄웁니다.
- 사용자는 모달 안의 data table 에서 권장 시나리오와 선택 가능한 시나리오 목록을 보고 최종 선택합니다.

## 변경 계산 규칙

각 target 에 대해 아래 세 가지 변경만 계산합니다.

- `MODEL_CHANGED`
- `BUSINESS_JAR_CHANGED`
- `EQP_PARAM_CHANGED`

### target 별 계산 기준

- `MODEL_CHANGED`
  - `Configuration & Deploy` 에서 선택한 `model/modelVersion` 과 현재 설비의 `model/modelVersion` 이 다를 때
- `BUSINESS_JAR_CHANGED`
  - `Configuration & Deploy` 에서 선택한 `businessJar` 와 현재 설비의 `businessJar` 가 다를 때
- `EQP_PARAM_CHANGED`
  - `Selected Equipments` 에서 최종 선택된 `eqp param version` 과 현재 설비의 적용 버전이 다를 때

### no-change 처리

- 세 변경 모두 거짓이면 해당 설비는 no-change target 입니다.
- no-change target 은 deploy preview 단계에서 제거합니다.

### deploy 단위 합집합 계산

- no-change target 제거 후 남은 설비들의 변경 집합을 합쳐 deploy 단위 union 을 계산합니다.
- 이 union 은 권장 시나리오 계산과 선택 가능 시나리오 필터링의 기준이 됩니다.

## 시나리오 catalog 정책

### catalog 저장 위치

- 시나리오 정의는 계속 YAML catalog 로 관리합니다.
- 경로는 아래를 사용합니다.
  - 개발 리소스: `ui/tc-ui-core/src/main/resources/deploy-scenarios`
  - 운영 오버라이드: `./runtime/ui-backend/deploy-scenarios`

### 등록 방식

- register 전용 scenario YAML 을 신규 추가합니다.
- `scenarios.index` 에도 함께 등록합니다.
- `tc_deploy_scenario_catalog_meta` 구조는 변경하지 않습니다.

## register 전용 시나리오 목록

아래 7개를 기본 시나리오 세트로 권장합니다.

| 시나리오 코드 | 변경 조합 | 기본 step 순서 |
| --- | --- | --- |
| `REGISTER_MODEL_ONLY` | `MODEL_CHANGED` | `RELOAD_BUSINESS_MODEL_RUNTIME` -> `UPDATE_MODEL_BINDING` |
| `REGISTER_EQP_PARAM_ONLY` | `EQP_PARAM_CHANGED` | `UPDATE_EQP_PARAM` |
| `REGISTER_MODEL_EQP_PARAM` | `MODEL_CHANGED + EQP_PARAM_CHANGED` | `RELOAD_BUSINESS_MODEL_RUNTIME` -> `UPDATE_MODEL_BINDING` -> `UPDATE_EQP_PARAM` |
| `REGISTER_MODEL_BUSINESS_JAR` | `MODEL_CHANGED + BUSINESS_JAR_CHANGED` | `RELOAD_BUSINESS_PLUGIN` -> `RELOAD_BUSINESS_MODEL_RUNTIME` -> `UPDATE_MODEL_BINDING` |
| `REGISTER_MODEL_BUSINESS_JAR_EQP_PARAM` | `MODEL_CHANGED + BUSINESS_JAR_CHANGED + EQP_PARAM_CHANGED` | `RELOAD_BUSINESS_PLUGIN` -> `RELOAD_BUSINESS_MODEL_RUNTIME` -> `UPDATE_MODEL_BINDING` -> `UPDATE_EQP_PARAM` |
| `REGISTER_BUSINESS_JAR_ONLY` | `BUSINESS_JAR_CHANGED` | `RELOAD_BUSINESS_PLUGIN` -> `UPDATE_MODEL_BINDING` |
| `REGISTER_BUSINESS_JAR_EQP_PARAM` | `BUSINESS_JAR_CHANGED + EQP_PARAM_CHANGED` | `RELOAD_BUSINESS_PLUGIN` -> `UPDATE_MODEL_BINDING` -> `UPDATE_EQP_PARAM` |

## 시나리오 선택 규칙

### 권장 시나리오

- deploy 단위 union 과 정확히 일치하는 시나리오가 있으면 그것을 권장 시나리오로 선택합니다.
- exact match 가 없으면 union 을 모두 포함하는 가장 작은 상위 시나리오를 권장합니다.
- 동률이면 step 수가 적은 시나리오를 우선합니다.

### 선택 가능한 시나리오

- data table 에는 현재 union 을 모두 커버하는 시나리오만 노출합니다.
- union 의 일부만 처리하는 시나리오는 선택 불가입니다.
- 따라서 사용자는 잘못된 하위 시나리오를 선택할 수 없습니다.

### 예시

- 설비 A: `MODEL_CHANGED`
- 설비 B: `MODEL_CHANGED + EQP_PARAM_CHANGED`
- deploy union: `MODEL_CHANGED + EQP_PARAM_CHANGED`
- 권장 시나리오: `REGISTER_MODEL_EQP_PARAM`
- 선택 가능 시나리오 예시:
  - `REGISTER_MODEL_EQP_PARAM`
  - `REGISTER_MODEL_BUSINESS_JAR_EQP_PARAM`
- A 설비에서는 `UPDATE_EQP_PARAM` step 만 `SKIPPED`

## UI/UX 설계

### Deploy 버튼 클릭 시 흐름

1. 현재 선택 상태를 기준으로 target 별 변경사항을 계산합니다.
2. no-change 설비를 `Selected Equipments` 에서 제거합니다.
3. 남은 설비가 0대면 `변경 사항이 없어 Deploy 할 수 없습니다.` 메시지를 보여주고 종료합니다.
4. backend preview API 를 호출해 권장 시나리오와 선택 가능 시나리오 목록을 받습니다.
5. 시나리오 선택 모달을 엽니다.
6. 사용자가 시나리오를 선택하고 `Deploy 생성` 을 누르면 실제 create API 를 호출합니다.

### 시나리오 선택 모달 구성

- 상단 요약 영역
  - 자동 제외된 설비 수
  - 실제 배포 대상 설비 수
  - 감지된 변경 종류
  - 권장 시나리오와 권장 이유
- 본문 data table 컬럼 권장안
  - `선택`
  - `권장`
  - `시나리오명`
  - `코드`
  - `포함 변경`
  - `단계 수`
  - `설비 적용 요약`
  - `설명`
- 하단 버튼
  - `취소`
  - `선택한 시나리오로 Deploy`

### 사용자 메시지 원칙

- no-change 로 제외된 설비가 있으면 모달 상단에 명시합니다.
- 권장 시나리오는 자동 선택 상태로 표시합니다.
- 사용자가 비권장 시나리오를 선택해도 union 을 커버하는 범위라면 허용합니다.

## backend API 설계

### 1. scenario preview API 추가 권장

현재 `GET /api/deploys/scenarios` 는 `eventType` 기준으로만 필터링합니다. 이 응답만으로는 권장 시나리오 계산과 선택 가능 여부 판단이 어렵습니다.

따라서 register deploy 전용 preview API 를 추가하는 것을 권장합니다.

권장 예시:

- `POST /api/deploys/register/scenario-preview`

### 2. preview request 예시

```json
{
  "interfaceType": "SECS",
  "selectedModelVersionKey": 101,
  "selectedBusinessJarRef": 3001,
  "targets": [
    {
      "eqpId": "EQP_A",
      "selectedEqpParamVersion": "v3"
    },
    {
      "eqpId": "EQP_B",
      "selectedEqpParamVersion": "v5"
    }
  ]
}
```

### 3. preview response 예시

```json
{
  "removedEqpIds": ["EQP_C"],
  "remainingEqpIds": ["EQP_A", "EQP_B"],
  "unionChangeKinds": ["MODEL_CHANGED", "EQP_PARAM_CHANGED"],
  "recommendedScenarioCode": "REGISTER_MODEL_EQP_PARAM",
  "recommendedReason": "감지된 변경 조합과 정확히 일치합니다.",
  "scenarios": [
    {
      "code": "REGISTER_MODEL_EQP_PARAM",
      "name": "Register Model + Eqp Param",
      "summary": "모델 변경과 설비 파라미터 반영을 수행합니다.",
      "version": 1,
      "stepCount": 3,
      "coversChangeKinds": ["MODEL_CHANGED", "EQP_PARAM_CHANGED"],
      "recommended": true,
      "selectable": true,
      "reason": "정확 일치"
    },
    {
      "code": "REGISTER_MODEL_BUSINESS_JAR_EQP_PARAM",
      "name": "Register Model + Business Jar + Eqp Param",
      "summary": "모델, 비즈니스 JAR, 설비 파라미터 변경을 모두 처리합니다.",
      "version": 1,
      "stepCount": 4,
      "coversChangeKinds": ["MODEL_CHANGED", "BUSINESS_JAR_CHANGED", "EQP_PARAM_CHANGED"],
      "recommended": false,
      "selectable": true,
      "reason": "상위 시나리오"
    }
  ]
}
```

### 4. create API 사용 방식

- preview 이후 create API 는 기존 `resolvedScenarioCode` 전달 방식을 유지할 수 있습니다.
- 단, 상위 `eventType` 은 register 전용 값으로 분리하는 것을 권장합니다.
- planner 는 선택된 scenario code 를 기준으로 plan 을 생성합니다.

## planner 동작 원칙

### create 시점

- create 요청은 preview 에서 선택된 `resolvedScenarioCode` 를 포함합니다.
- backend 는 요청된 scenario 가 현재 deploy union 을 커버하는지 다시 검증합니다.
- 검증 실패 시 `INVALID_REQUEST` 로 거절합니다.

### target 별 step plan 생성

- 모든 target 에 같은 `resolvedScenarioCode` 를 기록합니다.
- 다만 step 별 실행 여부는 target 의 실제 delta 를 기준으로 결정합니다.
- 필요 없는 step 은 초기 상태부터 `SKIPPED` 로 plan 에 반영하는 방식을 권장합니다.

### skip 판단 시점

- runtime 실행 중 즉석 판단보다 planning 시점에 skip 을 확정하는 것을 권장합니다.
- 이유는 저장된 `resolvedStepPlanJson` 과 실제 실행 이력의 정합성을 유지하기 쉽기 때문입니다.

## 구현 비목표

- `tc_deploy_scenario_catalog_meta` 를 scenario definition 저장 테이블로 변경하지 않습니다.
- 운영자가 임의로 step 조합을 만드는 자유 조합형 scenario editor 는 제공하지 않습니다.
- deploy 1건 안에 여러 scenario code 를 저장하는 구조로 바꾸지 않습니다.
- gateway connection 변경용 `EQP_UPDATE` 시나리오와 register deploy 시나리오를 혼합하지 않습니다.

## 영향 범위

### backend

- `ui/tc-ui-core`
  - register 전용 event type 추가
  - register scenario YAML 추가
  - preview service / planner 보강
- `ui/adapter/tc-ui-web-adapter`
  - preview API 추가
  - scenario summary 응답 확장 또는 preview 응답 신규 정의

### frontend

- `nori-tc-ui-front`
  - `Deploy` 버튼 클릭 시 create 직행 대신 preview + modal 흐름으로 변경
  - 오류 후 선택 dialog 가 아니라 사전 시나리오 선택 modal 로 전환
  - 자동 제거된 설비와 권장 시나리오 안내 UX 추가

## 검증 포인트

- no-change target 이 preview 단계에서 정확히 제거되는지
- 서로 다른 변경 패턴의 설비가 섞여 있어도 union 기반으로 하나의 시나리오를 고를 수 있는지
- exact match 와 superset 후보가 동시에 있을 때 exact match 가 권장되는지
- create 시 preview 와 다른 데이터로 요청이 들어와도 backend 검증이 막는지
- target 별 step skip 이 계획과 실행 결과에 일관되게 반영되는지

## open questions

- register 전용 상위 event type 이름을 무엇으로 확정할지
- preview API 를 별도 엔드포인트로 둘지, 기존 create API 의 dry-run 모드로 흡수할지
- 모달 data table 에 `선택 불가 시나리오` 를 함께 보여줄지, 선택 가능한 목록만 보여줄지
- history 화면에서 선택된 scenario code 와 skip 된 step 수를 어디까지 노출할지


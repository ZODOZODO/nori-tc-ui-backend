# Deploy Register Scenario Selection Guide

## 날짜

- 2026-04-01

## 문서 목적

- `/deploy` 의 `Register` 탭에서 사용하는 새 시나리오 선택형 deploy 흐름을 안내합니다.
- 운영자 화면 동작, backend API 계약, YAML scenario catalog 관리 위치를 한 문서에 정리합니다.
- 이 문서는 구현 완료 기준의 사용 가이드입니다.

## 관련 문서

- 설계: `/home/donggeon/workspace_nori/nori-tc-ui-backend/docs/design/2026-04-01-deploy-register-scenario-selection-design.md`
- backend impact: `/home/donggeon/workspace_nori/nori-tc-ui-backend/docs/impact/2026-04-01-deploy-register-scenario-selection-backend-impact.md`
- front impact: `/home/donggeon/workspace_nori/nori-tc-ui-front/docs/impact/2026-04-01-deploy-register-scenario-selection-front-impact.md`
- controlled execution 설계: `/home/donggeon/workspace_nori/nori-tc-exec/docs/design/12-deploy-controlled-execution-and-duplicate-guard-program.md`
- controlled execution front impact: `/home/donggeon/workspace_nori/nori-tc-ui-front/docs/impact/2026-04-01-deploy-register-front-phase5-impact.md`
- deploy monitor control impact: `/home/donggeon/workspace_nori/nori-tc-ui-front/docs/impact/2026-04-01-deploy-monitor-front-phase6-impact.md`

## 핵심 변경 사항

- `Register` deploy 는 더 이상 `EQP_BUSINESS_JARFILE_UPDATE` 로 고정되지 않습니다.
- `Register` deploy 전용 상위 event type 은 `EQP_REGISTER_DEPLOY` 입니다.
- Deploy 버튼 클릭 시 바로 생성하지 않고 먼저 scenario preview 를 계산합니다.
- 변경이 없는 설비는 `Selected Equipments` 에서 자동 제거합니다.
- 남은 설비가 있으면 권장 시나리오와 선택 가능한 시나리오 목록을 modal 로 보여줍니다.
- 사용자가 시나리오 1개를 선택한 뒤에만 실제 deploy 를 생성합니다.
- deploy 1건은 시나리오 1개만 사용합니다.
- target 별로 필요 없는 step 은 실행 계획에 포함하지 않습니다.
- deploy 생성 직후 상태는 `READY` 이며 자동 실행되지 않습니다.
- 생성된 deploy 는 `Deploy` 탭에서 사용자가 직접 `Deploy` 로 시작하거나 `Hold` 후 다시 재개합니다.
- active deploy 가 이미 잡고 있는 설비는 `Register` 검색/선택 단계와 create API 양쪽에서 차단됩니다.

## 운영자 화면 동작

### 1. 설비 선택

- `Target Equipment Search` 에서 설비를 검색합니다.
- 원하는 설비를 선택한 뒤 `Add to Selected` 로 `Selected Equipments` 에 추가합니다.

### 2. 변경 값 입력

- `Configuration & Deploy` 에서 아래 값을 선택할 수 있습니다.
- `New Model`
- `New Model Version`
- `Business Jar File`
- 설비별 `Eqp Param Version`

### 3. Deploy 버튼 클릭 시 처리

Deploy 버튼을 누르면 frontend 는 아래 순서로 동작합니다.

1. 공통 선택값과 설비별 `Eqp Param Version` 을 기준으로 preview 요청을 보냅니다.
2. backend 가 각 설비의 현재 상태와 비교해서 변경 종류를 계산합니다.
3. 변경이 없는 설비는 `Selected Equipments` 에서 자동 제거됩니다.
4. 변경이 남아 있으면 권장 시나리오와 선택 가능한 시나리오 목록이 modal 로 열립니다.
5. 사용자가 시나리오를 선택하고 `선택한 시나리오로 Deploy` 를 누르면 실제 deploy 가 생성됩니다.
6. 생성이 성공하면 새 deploy 는 `READY` 상태로 `Deploy` 탭에 나타나고, 사용자가 직접 실행할 때까지 대기합니다.

### 4. no-change 설비 처리

- 변경이 없는 설비는 deploy 대상에서 자동 제외됩니다.
- 자동 제외된 설비 수는 시나리오 선택 modal 상단 요약에 표시됩니다.
- 자동 제외 후 남은 설비가 0대이면 deploy 는 생성되지 않습니다.

### 5. 생성 이후 실행 제어

- `Register` 는 deploy 생성 전용 흐름입니다.
- 생성 성공 후에는 `Deploy` 탭 active row 에 `READY` 상태로 표시됩니다.
- `READY` row 기본 액션은 `Deploy` 이고, 실행을 시작하면 `RUNNING` 으로 전환됩니다.
- `RUNNING` row 기본 액션은 `Hold` 입니다.
- `Hold` 는 즉시 중단이 아니라 현재 step 이 끝난 뒤 safe point 에서 `READY` 로 되돌리는 요청입니다.
- `pauseRequested=true` 인 동안에는 `Hold 요청됨` 상태로 표시됩니다.

### 6. active 설비 중복 차단

- 다른 active deploy 가 이미 포함한 설비는 `Target Equipment Search` 에서 충돌 상태로 표시됩니다.
- 충돌 설비는 `Selected Equipments` 에 추가할 수 없습니다.
- UI 선차단을 우회하더라도 backend create/start 는 같은 설비가 `READY` 또는 `RUNNING` deploy 에 포함돼 있으면 `409 Conflict` 로 거부합니다.

## 변경 종류 계산 기준

각 설비에 대해 아래 세 가지 변경만 계산합니다.

- `MODEL_CHANGED`
- `BUSINESS_JAR_CHANGED`
- `EQP_PARAM_CHANGED`

세부 기준은 아래와 같습니다.

- `MODEL_CHANGED`
  - 선택한 `modelVersionKey` 와 설비 현재 `modelVersionKey` 가 다를 때
- `BUSINESS_JAR_CHANGED`
  - 선택한 `businessJar.jarRef` 와 설비 현재 `businessJarRef` 가 다를 때
- `EQP_PARAM_CHANGED`
  - 설비별 최종 `eqpParams.version` 과 현재 적용 버전이 다를 때

## 시나리오 선택 규칙

### 공통 원칙

- deploy 1건당 시나리오는 1개만 선택합니다.
- 모든 target 은 같은 시나리오 코드를 사용합니다.
- 다만 target 별 delta 가 다를 수 있으므로, 필요 없는 step 은 그 target 의 실행 계획에서 제외됩니다.

### 권장 시나리오 선정

- 감지된 변경 조합과 정확히 일치하는 시나리오가 있으면 그 시나리오를 권장합니다.
- 정확 일치가 없으면 감지된 변경을 모두 포함하는 가장 작은 상위 시나리오를 권장합니다.
- 동률이면 step 수가 적은 시나리오를 우선합니다.

### 선택 가능 시나리오 범위

- modal data table 에는 감지된 변경 조합을 모두 커버하는 시나리오만 표시됩니다.
- 필요한 변경의 일부만 처리하는 하위 시나리오는 선택 목록에 나오지 않습니다.

## Register 전용 시나리오 목록

시나리오 YAML 은 아래 위치에 있습니다.

- 개발 리소스: `/home/donggeon/workspace_nori/nori-tc-ui-backend/ui/tc-ui-core/src/main/resources/deploy-scenarios`
- classpath index: `/home/donggeon/workspace_nori/nori-tc-ui-backend/ui/tc-ui-core/src/main/resources/deploy-scenarios/scenarios.index`

현재 Register 전용 시나리오 코드는 아래 7개입니다.

| 코드 | 의미 |
| --- | --- |
| `REGISTER_MODEL_ONLY` | Model 변경 |
| `REGISTER_EQP_PARAM_ONLY` | Eqp Param 변경 |
| `REGISTER_MODEL_EQP_PARAM` | Model + Eqp Param 변경 |
| `REGISTER_MODEL_BUSINESS_JAR` | Model + Business Jar 변경 |
| `REGISTER_MODEL_BUSINESS_JAR_EQP_PARAM` | Model + Business Jar + Eqp Param 변경 |
| `REGISTER_BUSINESS_JAR_ONLY` | Business Jar 변경 |
| `REGISTER_BUSINESS_JAR_EQP_PARAM` | Business Jar + Eqp Param 변경 |

각 YAML 파일은 `eventTypes: [EQP_REGISTER_DEPLOY]` 로 등록되어 있습니다.

## Backend API

### 1. Scenario Preview API

- Method: `POST`
- Path: `/api/deploys/register/scenario-preview`

요청 예시:

```json
{
  "commonRequest": {
    "interfaceType": "SOCKET",
    "uiMessage": {
      "modelInfo": {
        "modelVersionKey": 1002
      },
      "businessJar": {
        "jarRef": 5001
      }
    }
  },
  "targets": [
    {
      "eqpId": "PHOTO_001",
      "uiMessageOverride": {
        "eqpParams": {
          "version": "EQP_PARAM_V2"
        }
      }
    },
    {
      "eqpId": "PHOTO_002",
      "uiMessageOverride": {
        "eqpParams": {
          "version": "EQP_PARAM_V1"
        }
      }
    }
  ]
}
```

응답 예시:

```json
{
  "removedEqpIds": ["PHOTO_002"],
  "remainingEqpIds": ["PHOTO_001"],
  "unionChangeKinds": ["MODEL_CHANGED", "BUSINESS_JAR_CHANGED", "EQP_PARAM_CHANGED"],
  "recommendedScenarioCode": "REGISTER_MODEL_BUSINESS_JAR_EQP_PARAM",
  "recommendedReason": "감지된 변경 조합과 정확히 일치합니다.",
  "scenarios": [
    {
      "code": "REGISTER_MODEL_BUSINESS_JAR_EQP_PARAM",
      "name": "Model + Business Jar + Eqp Param 변경",
      "summary": "Model, Business Jar, Eqp Param 변경을 모두 반영한다.",
      "version": 1,
      "stepCount": 4,
      "coversChangeKinds": [
        "MODEL_CHANGED",
        "BUSINESS_JAR_CHANGED",
        "EQP_PARAM_CHANGED"
      ],
      "recommended": true,
      "selectable": true,
      "reason": "감지된 변경 조합과 정확히 일치합니다."
    }
  ]
}
```

### 2. Deploy Create API

- Method: `POST`
- Path: `/api/deploys`

중요한 점은 `commonRequest.resolvedScenarioCode` 를 반드시 함께 보내야 한다는 점입니다.

요청 예시:

```json
{
  "deployName": "PHOTO_MODEL 1.0.2 Deploy",
  "eventType": "EQP_REGISTER_DEPLOY",
  "executionPolicy": "BEST_EFFORT",
  "commonRequest": {
    "interfaceType": "SOCKET",
    "uiMessage": {
      "modelInfo": {
        "modelVersionKey": 1002
      },
      "businessJar": {
        "jarRef": 5001
      }
    },
    "resolvedScenarioCode": "REGISTER_MODEL_BUSINESS_JAR_EQP_PARAM"
  },
  "targets": [
    {
      "eqpId": "PHOTO_001",
      "uiMessageOverride": {
        "eqpParams": {
          "version": "EQP_PARAM_V2"
        }
      }
    }
  ]
}
```

## Backend 동작 방식

### preview 단계

- backend 는 각 target 에 대해 현재 상태를 조회합니다.
- `commonRequest.uiMessage` 와 `targets[].uiMessageOverride` 를 merge 합니다.
- Register deploy 전용 정규화 단계에서 누락된 값은 현재 상태로 보정합니다.
  - `modelInfo.modelVersionKey`
  - `businessJar.jarRef`
  - `eqpParams.version`
- 이 보정 덕분에 입력하지 않은 항목은 `현재 값 유지` 로 해석됩니다.

### plan 단계

- create 요청의 `resolvedScenarioCode` 로 실제 시나리오를 찾습니다.
- 선택한 시나리오가 현재 delta 를 모두 커버하는지 다시 검증합니다.
- target 별 delta 에 맞지 않는 step 은 `resolvedStepPlan` 에 포함하지 않습니다.
- 따라서 모든 target 은 같은 시나리오를 쓰지만, 실행되는 step 집합은 target 별로 달라질 수 있습니다.

## 파일 위치 요약

### backend

- controller: `/home/donggeon/workspace_nori/nori-tc-ui-backend/ui/adapter/tc-ui-web-adapter/src/main/java/com/nori/tc/ui/adapters/web/controller/DeployController.java`
- preview request DTO: `/home/donggeon/workspace_nori/nori-tc-ui-backend/ui/adapter/tc-ui-web-adapter/src/main/java/com/nori/tc/ui/adapters/web/dto/request/DeployRegisterScenarioPreviewRequest.java`
- preview response DTO: `/home/donggeon/workspace_nori/nori-tc-ui-backend/ui/adapter/tc-ui-web-adapter/src/main/java/com/nori/tc/ui/adapters/web/dto/response/DeployRegisterScenarioPreviewResponse.java`
- planner: `/home/donggeon/workspace_nori/nori-tc-ui-backend/ui/tc-ui-core/src/main/java/com/nori/tc/ui/core/service/DeployPlanningService.java`
- event type: `/home/donggeon/workspace_nori/nori-tc-ui-backend/ui/tc-ui-core/src/main/java/com/nori/tc/ui/core/model/UiCommandEventType.java`
- register scenarios: `/home/donggeon/workspace_nori/nori-tc-ui-backend/ui/tc-ui-core/src/main/resources/deploy-scenarios/register-*.yaml`

### frontend

- register panel: `/home/donggeon/workspace_nori/nori-tc-ui-front/src/features/deploy/components/DeployRegisterPanel.tsx`
- preview hook: `/home/donggeon/workspace_nori/nori-tc-ui-front/src/features/deploy/hooks/useDeployRegisterScenarioPreview.ts`
- api client: `/home/donggeon/workspace_nori/nori-tc-ui-front/src/features/deploy/api/deploy.api.ts`
- state store: `/home/donggeon/workspace_nori/nori-tc-ui-front/src/features/deploy/stores/deploy-ui.store.ts`

## 운영 시 주의 사항

- runtime 환경에서 `./runtime/ui-backend/deploy-scenarios` 디렉터리가 존재하면 classpath 리소스보다 runtime 파일이 우선합니다.
- 새 Register scenario YAML 을 추가한 경우 `scenarios.index` 도 함께 수정해야 합니다.
- preview 와 create 사이에 사용자가 선택 값을 다시 바꾸면 preview 결과와 create 입력이 달라질 수 있으므로, 현재 UI 는 modal 에서 scenario 선택 후 바로 create 하는 흐름을 사용합니다.
- 운영 DB 권한 반영 시에는 전체 sample seed SQL 을 다시 실행하면 안 됩니다.
- sample_data SQL 은 최신 정본 상태를 유지합니다.
- `POST /api/deploys/register/scenario-preview` 권한 추가는 비파괴성 patch SQL로만 반영해야 합니다.
- patch 경로: `/home/donggeon/workspace_nori/nori-tc-exec/docs/db_table/patch/postgres_2026_04_01_add_deploy_preview_permission.sql`

## 검증 결과

- backend: `./gradlew test`
- frontend: `pnpm lint`
- frontend: `pnpm test:unit`
- frontend: `pnpm build`

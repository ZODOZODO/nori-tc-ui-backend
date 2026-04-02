# Deploy Register Scenario Selection Backend Impact Report

## 날짜

- 2026-04-01

## 요청 요약

- `Register` deploy 를 기존 `EQP_UPDATE` 의미와 분리된 별도 deploy 도메인으로 구현
- deploy 생성 전에 변경사항을 계산하고 권장 시나리오와 선택 가능한 시나리오 목록을 preview 로 제공
- 사용자가 선택한 시나리오 1개를 기준으로 deploy 를 생성하되, target 별 필요 없는 step 은 skip 되도록 planning

## reference docs

- `/home/donggeon/workspace_nori/nori-tc-ui-backend/docs/design/2026-04-01-deploy-register-scenario-selection-design.md`

## impacted repository

- `nori-tc-ui-backend`

## local governance files

- `/home/donggeon/workspace_nori/nori-tc-ui-backend/AGENTS.md`
- `/home/donggeon/workspace_nori/nori-tc-exec/harness/docs/repo-agent-governance.md`

## impacted modules

- `ui/tc-ui-core`
- `ui/adapter/tc-ui-web-adapter`
- `ui/adapter/tc-ui-db-adapter`

## expected changed files

- `ui/tc-ui-core/src/main/java/com/nori/tc/ui/core/model/UiCommandEventType.java`
- `ui/tc-ui-core/src/main/java/com/nori/tc/ui/core/deploy/DeployPlanCommand.java`
- `ui/tc-ui-core/src/main/java/com/nori/tc/ui/core/service/DeployPlanningService.java`
- `ui/tc-ui-core/src/main/java/com/nori/tc/ui/core/service/DeployServiceCommandSupport.java`
- `ui/tc-ui-core/src/main/java/com/nori/tc/ui/core/service/DeployScenarioCatalogService.java`
- `ui/tc-ui-core/src/main/resources/deploy-scenarios/scenarios.index`
- `ui/tc-ui-core/src/main/resources/deploy-scenarios/register-*.yaml`
- `ui/adapter/tc-ui-web-adapter/src/main/java/com/nori/tc/ui/adapters/web/controller/DeployController.java`
- `ui/adapter/tc-ui-web-adapter/src/main/java/com/nori/tc/ui/adapters/web/dto/request/*.java`
- `ui/adapter/tc-ui-web-adapter/src/main/java/com/nori/tc/ui/adapters/web/dto/response/*.java`
- `ui/adapter/tc-ui-db-adapter/src/main/java/com/nori/tc/ui/adapter/db/DeployJdbcStoreSupport.java`
- `ui/tc-ui-core/src/test/java/com/nori/tc/ui/core/service/DeployPlanningServiceTest.java`
- `ui/tc-ui-core/src/test/java/com/nori/tc/ui/core/service/DeployServiceTest.java`
- `ui/adapter/tc-ui-web-adapter/src/test/java/com/nori/tc/ui/adapters/web/controller/DeployControllerTest.java`

## non-goals

- deploy history / monitor 전체 UX 재설계
- `tc_deploy_scenario_catalog_meta` 를 scenario definition 저장소로 변경
- 운영자 자유 조합형 scenario editor 추가
- gateway connection update 용 `EQP_UPDATE` planner 재설계

## risk points

1. 상위 deploy event type 을 새로 추가하면 저장, 조회, planner, 실행 경로에서 누락된 switch 문이 있으면 런타임 오류가 날 수 있습니다.
2. `resolvedScenarioCode` 가 현재 create request DTO 에 반영되지 않아 request 모델과 planner 를 함께 보강하지 않으면 front 연동이 절반만 동작할 수 있습니다.
3. preview 결과와 실제 create 시점 입력이 달라질 수 있으므로 create 단계에서 scenario coverage 를 다시 검증하지 않으면 잘못된 deploy 가 생성될 수 있습니다.
4. 같은 scenario code 를 공유하더라도 target 별 step 집합은 달라질 수 있어, resolved step plan 생성 규칙을 잘못 잡으면 no-op target 이 잘못 실행될 수 있습니다.

## verification plan

- `./gradlew test`

# Deploy Register Backend Phase 1 Impact Report

## 날짜

- 2026-03-31

## 요청 요약

- `Register` 화면을 backend 실제 조회 데이터와 연결하기 위한 1차 구현
- 신규 `GET /api/deploys/register/targets` read API 추가
- 기존 `GET /api/eqp/options`, `GET /api/eqp/{eqpId}/param-versions` 재사용

## reference docs

- `/home/donggeon/workspace_nori/nori-tc-ui-front/docs/mcp_command/05-deploy-register-api-spec.md`
- `/home/donggeon/workspace_nori/nori-tc-ui-front/docs/mcp_command/06-deploy-register-backend-phase1.md`

## impacted repository

- `nori-tc-ui-backend`

## local governance files

- `/home/donggeon/workspace_nori/nori-tc-ui-backend/AGENTS.md`
- `/home/donggeon/workspace_nori/nori-tc-exec/harness/docs/repo-agent-governance.md`

## impacted modules

- `ui/adapter/tc-ui-web-adapter`
- `ui/tc-ui-core`
- `ui/adapter/tc-ui-db-adapter`

## expected changed files

- `ui/adapter/tc-ui-web-adapter/src/main/java/com/nori/tc/ui/adapters/web/controller/DeployController.java`
- `ui/adapter/tc-ui-web-adapter/src/main/java/com/nori/tc/ui/adapters/web/dto/response/DeployRegisterTargetPageResponse.java`
- `ui/tc-ui-core/src/main/java/com/nori/tc/ui/core/service/DeployRegisterQueryService.java`
- `ui/tc-ui-core/src/main/java/com/nori/tc/ui/core/port/db/DeployRegisterQueryPort.java`
- `ui/tc-ui-core/src/main/java/com/nori/tc/ui/core/deploy/DeployRegisterTargetView.java`
- `ui/adapter/tc-ui-db-adapter/src/main/java/com/nori/tc/ui/adapter/db/JpaDeployRegisterQueryPort.java`
- `ui/adapter/tc-ui-web-adapter/src/test/java/com/nori/tc/ui/adapters/web/controller/DeployControllerTest.java`
- `ui/tc-ui-core/src/test/java/com/nori/tc/ui/core/service/DeployRegisterQueryServiceTest.java`

## non-goals

- `POST /api/deploys` 계약 변경
- deploy 생성 / polling 로직 변경
- deploy history 조회 API 추가
- Business Jar upload API 추가
- 기존 `/api/eqp`, `/api/model` 계약 변경

## risk points

1. `Register` 검색에 필요한 `modelName`, `modelVersion`, `businessJarFileName`, `appliedParamVersion` 조합 과정에서 기존 DB support 반환값과 필드명이 어긋날 수 있습니다.
2. `EqpManagementDbSupport` 재사용 시 scan 기반 메모리 조합 비용과 정렬/페이지네이션 처리 순서를 잘못 잡으면 응답 일관성이 깨질 수 있습니다.
3. `DeployController` 안에 create/polling API가 이미 있으므로 register read API 추가 시 controller 책임이 과해지지 않도록 service/port 경계를 분리해야 합니다.

## verification plan

- `./gradlew test`

# Deploy Create Backend Phase 2 Impact Report

## 날짜

- 2026-03-31

## 요청 요약

- `Register` 의 `Deploy` 버튼을 실제 `POST /api/deploys` 와 연결하는 2차 구현을 준비
- 기본 권장안은 기존 deploy create / summary / targets 계약을 그대로 재사용
- backend 는 신규 API 추가보다 기존 계약 안정성 확인과 필요 시 최소 보강이 중심

## reference docs

- `/home/donggeon/workspace_nori/nori-tc-ui-front/docs/mcp_command/05-deploy-register-api-spec.md`
- `/home/donggeon/workspace_nori/nori-tc-ui-front/docs/mcp_command/08-deploy-create-phase2.md`

## impacted repository

- `nori-tc-ui-backend`

## local governance files

- `/home/donggeon/workspace_nori/nori-tc-ui-backend/AGENTS.md`
- `/home/donggeon/workspace_nori/nori-tc-exec/harness/docs/repo-agent-governance.md`

## impacted modules

- `ui/adapter/tc-ui-web-adapter`
- `ui/tc-ui-core`

## expected changed files

- `없음 (권장안 기준, 기존 create/polling 계약 재사용)`
- `ui/adapter/tc-ui-web-adapter/src/test/java/com/nori/tc/ui/adapters/web/controller/DeployControllerTest.java`
- `ui/tc-ui-core/src/test/java/com/nori/tc/ui/core/service/DeployPlanningServiceTest.java`
- `ui/tc-ui-core/src/test/java/com/nori/tc/ui/core/service/DeployServiceTest.java`
- `필요 시 ui/adapter/tc-ui-web-adapter/src/main/java/com/nori/tc/ui/adapters/web/controller/DeployController.java`

## non-goals

- 신규 deploy history list API 추가
- `GET /api/deploys/register/targets` 재설계
- Business Jar upload API 추가
- deploy domain 전체 구조 변경

## risk points

1. front 가 생성 payload에 포함하려는 model/jar 부가 필드와 backend planner 최소 필드 사이에 차이가 있어 과도한 필드 의존이 생길 수 있습니다.
2. create 직후 polling 응답이 `PLANNING` 또는 빈 target page 상태를 잠시 보일 수 있으므로 초기 UX 기대값을 맞춰야 합니다.
3. 권장안상 backend 변경이 없더라도 테스트와 에러 메시지 안정성이 부족하면 front 2차 연동에서 회귀가 드러날 수 있습니다.

## verification plan

- `./gradlew test`

# Deploy Actions Backend Phase 4 Impact Report

## 날짜

- 2026-03-31

## 요청 요약

- `/deploy` 의 쓰기 액션을 위한 backend API `cancel`, `redeploy`, `delete` 추가
- 상태별 허용/거부 규칙을 backend 에서 강제하고, actor/감사 정보와 에러 응답 정책을 정리
- 액션 결과가 `Deploy` / `Deploy_History` / 상세 polling 흐름과 모순되지 않도록 server 상태 전이를 고정

## reference docs

- `/home/donggeon/workspace_nori/nori-tc-ui-front/docs/mcp_command/09-deploy-history-phase3.md`
- `/home/donggeon/workspace_nori/nori-tc-ui-front/docs/mcp_command/10-deploy-actions-phase4.md`

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
- `ui/adapter/tc-ui-web-adapter/src/main/java/com/nori/tc/ui/adapters/web/dto/response/DeployActionResponse.java`
- `ui/tc-ui-core/src/main/java/com/nori/tc/ui/core/service/DeployService.java`
- `ui/tc-ui-core/src/main/java/com/nori/tc/ui/core/port/db/DeployStorePort.java`
- `ui/adapter/tc-ui-db-adapter/src/main/java/com/nori/tc/ui/adapter/db/DeployJdbcStorePort.java`
- `ui/adapter/tc-ui-web-adapter/src/test/java/com/nori/tc/ui/adapters/web/controller/DeployControllerTest.java`
- `ui/tc-ui-core/src/test/java/com/nori/tc/ui/core/service/DeployServiceTest.java`

## non-goals

- `GET /api/deploys` 목록 계약 재설계
- register 조회 API 재설계
- Business Jar upload API 확장
- deploy domain 전체 구조 대개편

## risk points

1. `Cancel`, `Redeploy`, `Delete` 의 허용 상태가 backend 에서 강제되지 않으면 front 숨김 처리만으로는 잘못된 액션 호출을 막을 수 없습니다.
2. `Redeploy` 가 기존 `deployKey` 를 재사용하지 않고 새 deploy 를 생성한다는 규칙이 흔들리면 history 와 active deploy 의 의미가 섞입니다.
3. `Delete` 를 soft delete 로 볼지 hard delete 로 볼지 정책이 모호하면 감사 추적과 목록 제외 규칙이 충돌할 수 있습니다.

## verification plan

- `./gradlew test`

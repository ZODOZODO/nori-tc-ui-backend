# Deploy History Backend Phase 3 Impact Report

## 날짜

- 2026-03-31

## 요청 요약

- `/deploy` 의 `Deploy` / `Deploy_History` 탭을 위한 server 목록 API `GET /api/deploys` 추가
- non-terminal / terminal 목록을 `terminalOnly` 기준으로 분리하고 날짜/모델/사용자 필터를 backend 계약으로 고정
- 목록 응답에 최소 summary 정보와 pagination 계약을 포함

## reference docs

- `/home/donggeon/workspace_nori/nori-tc-ui-front/docs/mcp_command/05-deploy-register-api-spec.md`
- `/home/donggeon/workspace_nori/nori-tc-ui-front/docs/mcp_command/09-deploy-history-phase3.md`

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
- `ui/adapter/tc-ui-web-adapter/src/main/java/com/nori/tc/ui/adapters/web/dto/response/DeployHistoryPageResponse.java`
- `ui/tc-ui-core/src/main/java/com/nori/tc/ui/core/service/DeployService.java`
- `ui/tc-ui-core/src/main/java/com/nori/tc/ui/core/port/db/DeployStorePort.java`
- `ui/tc-ui-core/src/main/java/com/nori/tc/ui/core/deploy/DeployHistoryView.java`
- `ui/adapter/tc-ui-db-adapter/src/main/java/com/nori/tc/ui/adapter/db/DeployJdbcStorePort.java`
- `ui/adapter/tc-ui-db-adapter/src/main/java/com/nori/tc/ui/adapter/db/DeployJdbcStoreSupport.java`
- `ui/adapter/tc-ui-web-adapter/src/test/java/com/nori/tc/ui/adapters/web/controller/DeployControllerTest.java`
- `ui/tc-ui-core/src/test/java/com/nori/tc/ui/core/service/DeployServiceTest.java`

## non-goals

- server `redeploy` API 추가
- server `delete` API 추가
- server `cancel` API 추가
- create / summary / targets 계약 재설계

## risk points

1. `toDate` inclusive 정책과 DB query 의 exclusive upper bound 변환을 명확히 맞추지 않으면 날짜 검색 결과가 하루씩 밀릴 수 있습니다.
2. JSON 필드 추출(`desired_common_state_json`)과 target summary 집계를 한 목록 query 에 함께 싣는 만큼, null 필드와 성능 저하를 함께 고려해야 합니다.
3. `terminalOnly=false` 목록에 terminal row 가 섞이거나 반대로 `terminalOnly=true` 목록에서 누락이 생기면 프론트 탭 의미가 바로 무너집니다.

## verification plan

- `./gradlew test`

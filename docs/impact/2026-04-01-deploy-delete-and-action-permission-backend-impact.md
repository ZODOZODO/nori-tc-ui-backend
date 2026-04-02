# Deploy Delete And Action Permission Backend Impact Report

## 날짜

- 2026-04-01

## 요청 요약

- `Deploy` 탭의 실행 전 deploy 삭제 요구에 맞춰 backend delete 허용 상태를 조정
- `POST /api/deploys/{deployKey}/start` 가 권한 캐시에서 차단되던 문제를 해결하기 위해 API permission `REGEX` 매칭을 실제 인가 로직에 반영
- deploy action API용 DB permission seed/patch와 backend 인가 해석이 같은 정책을 보도록 정리

## reference docs

- `/home/donggeon/workspace_nori/nori-tc-ui-front/docs/mcp_command/11-deploy-ready-delete-and-action-permission-fix.md`

## impacted repository

- `nori-tc-ui-backend`

## impacted modules

- `ui/tc-ui-core`
- `ui/adapter/tc-ui-web-adapter`

## expected changed files

- `ui/tc-ui-core/src/main/java/com/nori/tc/ui/core/service/DeployService.java`
- `ui/tc-ui-core/src/test/java/com/nori/tc/ui/core/service/DeployServiceTest.java`
- `ui/adapter/tc-ui-web-adapter/src/main/java/com/nori/tc/ui/adapters/web/security/UiApiPermissionCache.java`
- `ui/adapter/tc-ui-web-adapter/src/test/java/com/nori/tc/ui/adapters/web/security/UiApiPermissionCacheTest.java`

## risk points

1. delete 허용 상태를 넓히면서 `RUNNING` 까지 열리면 실행 중 trace/state 정리가 깨질 수 있습니다.
2. `REGEX` permission 해석이 없으면 DB에 permission 을 추가해도 start/hold/delete API가 계속 차단됩니다.
3. 잘못된 정규식이 등록되면 허용 누락이 생길 수 있으므로 invalid regex 는 안전하게 무시해야 합니다.

## verification plan

- `./gradlew test`

# Admin Full Permission Backend Impact Report

## 날짜

- 2026-04-02

## 요청 요약

- ADMIN 그룹은 신규/수정 권한을 포함해 항상 모든 UI 권한을 보유해야 하므로, 권한 생성/수정 런타임 경로에서 ADMIN 매핑 누락이 재발하지 않게 보강
- 기존 seed/patch로 초기 상태를 맞춘 뒤에도 permission admin API를 통해 생성된 권한이 ADMIN에 자동 연결되지 않던 구조를 수정

## reference docs

- `/home/donggeon/workspace_nori/nori-tc-exec/docs/db_table/sample_data/postgres_insert_sample_data.sql`
- `/home/donggeon/workspace_nori/nori-tc-exec/docs/db_table/sample_data/postgres_insert_user_ui_sample_data.sql`

## impacted repository

- `nori-tc-ui-backend`

## impacted modules

- `ui/tc-ui-core`
- `ui/adapter/tc-ui-db-adapter`
- `ui/adapter/tc-ui-web-adapter`

## expected changed files

- `ui/tc-ui-core/src/main/java/com/nori/tc/ui/core/port/db/GroupCrudPort.java`
- `ui/adapter/tc-ui-db-adapter/src/main/java/com/nori/tc/ui/adapter/db/JpaGroupCrudPort.java`
- `ui/adapter/tc-ui-web-adapter/src/main/java/com/nori/tc/ui/adapters/web/application/admin/PermissionAdminFacade.java`
- `ui/adapter/tc-ui-web-adapter/src/test/java/com/nori/tc/ui/adapters/web/application/admin/PermissionAdminFacadeTest.java`

## risk points

1. ADMIN 그룹 조회 경로가 없으면 권한 저장 후 자동 매핑을 보정할 수 없습니다.
2. permission upsert 이후 ADMIN 매핑을 누락하면 운영 중 새 API/Page 권한이 즉시 차단될 수 있습니다.
3. ADMIN 그룹이 비정상적으로 삭제된 환경에서는 자동 보정이 불가능하므로 운영 DB seed/patch 상태를 함께 확인해야 합니다.

## verification plan

- `./gradlew :ui:adapter:tc-ui-web-adapter:test --tests 'com.nori.tc.ui.adapters.web.application.admin.PermissionAdminFacadeTest'`

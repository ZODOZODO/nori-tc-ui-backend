# Deploy Register Target Query Null Excluded Key Fix Impact

## 날짜

- 2026-04-01

## 배경

- `GET /api/deploys/register/targets` 에서 active deploy 충돌 정보를 조합할 때
  `excludedDeployKey=null` 인 기본 검색 경로가 PostgreSQL JDBC 바인딩 오류로 실패했습니다.
- 운영 로그 기준 예외는 아래와 같습니다.
  - `org.postgresql.util.PSQLException: ERROR: could not determine data type of parameter $6`

## 원인

- `DeployJdbcStoreSupport.findActiveEqpConflicts()` 가 아래 조건을 고정 SQL로 사용했습니다.
  - `(:excludedDeployKey IS NULL OR d.deploy_key <> :excludedDeployKey)`
- `excludedDeployKey=null` 일 때 PostgreSQL 이 `? IS NULL` 파라미터의 타입을 결정하지 못해
  register target 조회 전체가 `BadSqlGrammarException` 으로 실패했습니다.

## 조치

- `excludedDeployKey` 가 `null` 이면 self 제외 조건을 SQL에 붙이지 않도록 분기했습니다.
- `excludedDeployKey` 가 있을 때만 `AND d.deploy_key <> :excludedDeployKey` 를 추가합니다.
- db-adapter 단위 테스트를 추가해 null / non-null 두 경로의 SQL 구성을 고정했습니다.

## 영향 범위

- backend only
- 대상 API
  - `GET /api/deploys/register/targets`
- 연관 기능
  - register 화면의 `Target Equipment Search`
  - active deploy 충돌 badge / 선차단 표시

## 검증

- `./gradlew test`

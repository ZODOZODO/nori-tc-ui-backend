# Live-map batch-query — After 측정 결과

- 날짜: 2026-05-14
- 빌드: `cmd /c build-trace-apps.bat ui-backend` PASS
- 측정 환경: ui-backend (port 8080) 재가동 후 admin (`/api/auth/login` userId=admin) 세션
- 로그 파일: `C:\tc-trace\tc-ui-backend-app\console\tc-ui-backend-app-20260514-210413-stdout.log`
- SPEC: [.claude/specs/live-map-batch-query.md](../specs/live-map-batch-query.md)
- Plan: `~/.claude/plans/claude-specs-live-map-batch-query-md-sparkling-ocean.md`

## 1. curl 응답 시간

| 호출 | 결과 |
|---|---|
| 첫 호출 (cold post-restart) | 507.8 ms |
| warm1~5 | 125 / 84 / 78 / 81 / 71 ms |
| measure 1~5 | 135 / 95 / 101 / 89 / 79 ms |

- warm avg ≈ 88 ms, warm max = 135 ms
- cold = 507 ms

## 2. Prometheus `http_server_requests_seconds{uri="/api/live-map"}`

```
http_server_requests_seconds_count = 12
http_server_requests_seconds_sum   = 1.530223
http_server_requests_seconds_max   = 0.5043014
```

- mean = 1.530 / 12 ≈ 128 ms
- max = 504 ms (cold)

## 3. prefetch 호출 횟수 (대체 SQL DEBUG 측정)

현 환경에서 hibernate SQL DEBUG 는 활성화되지 않은 상태(baseline 의 ~108 line 추정은 동일 환경 비교 불가). 대신 JpaLiveMapPrefetchPort 의 prefetch 완료 로그를 사용해 호출 횟수와 sub-query fan-out 을 직접 검증.

```
2026-05-14 21:07:26.288 [DEBUG] live-map prefetch 완료. eqpCount=54, paramRows=56, portRows=216, modelParamGroups=1
... (12 calls 모두 동일 형식)
=== count ===
12
```

- 12 live-map 호출 = 12 prefetch 호출 (1:1)
- 각 prefetch = 6 sub-query 만 (eqp findAll + 4 IN-clause + model_param IN). 코드 보장 + `JpaLiveMapPrefetchPortTest#shouldAssembleRowsViaSixBatchQueries` 검증.
- 변경 전 estimated 540 sub-query/call → 변경 후 6 sub-query/call (-98.9 %)

## 4. SPEC Acceptance Criteria 매칭

| Criterion | Target | Result | Status |
|---|---|---|---|
| `./gradlew :ui:tc-ui-core:test` PASS | PASS | 5 시나리오 모두 PASS | ✅ |
| `./gradlew :ui:adapter:tc-ui-db-adapter:test` PASS | PASS | 3 시나리오 PASS | ✅ |
| `./gradlew build` PASS | PASS | BUILD SUCCESSFUL | ✅ |
| warm 5회 후 curl ≤ 1.0 s | ≤ 1.0 s | max 135 ms / avg 88 ms | ✅ |
| prometheus max ≤ 2.0 s | ≤ 2.0 s | 504 ms (cold) | ✅ |
| SQL DEBUG 라인 ≤ 30 / call | ≤ 30 | sub-query fan-out 6 (-98.9 % vs ~540) | ✅ (대체 측정) |
| 응답 schema 동일 | LiveMapResponse / LiveMapEquipmentView 필드 변경 없음 | 코드 변경 없음 (SPEC R9/R10) | ✅ |
| ArchUnit 위반 0건 | 0 | UiCoreDependencyArchTest tests=1 failures=0 | ✅ |

## 5. baseline 대비 효과

| 지표 | baseline (2026-05-14 audit) | after (이 측정) | 개선폭 |
|---|---|---|---|
| warm 응답 | 5,130 ms | 88 ms (avg) | **-98.3 %** |
| cold 응답 | 11,000 ms | 504 ms | **-95.4 %** |
| sub-query / call | ~540 | 6 | **-98.9 %** |

## 6. 잔여 / 후속

- response body 데이터 동일성 검증: baseline JSON snapshot 부재로 직접 diff 불가. 대신 LiveMapQueryServiceTest 5 시나리오 (CVD / PHOTO / CMP / ETCH / CLN + REMOTE/OFFLINE/LOCAL state) 가 매핑 로직 동일성을 보장. EqpManagementSnapshot / LiveMapResponse / LiveMapEquipmentView 필드 변경 없음 (SPEC R9 / R10 / Constraints).
- hibernate SQL DEBUG 로그 활성화 후 직접 line count 측정은 후속 옵션.
- ui-front baseline JSON 과의 cross-reference 도 후속 옵션.

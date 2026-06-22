package com.nori.tc.ui.adapters.web.security;

import com.nori.tc.ui.domain.auth.UserPrincipal;
import com.nori.tc.ui.domain.common.user.UiPermissionMatchType;
import com.nori.tc.ui.domain.common.user.UiPermissionResourceType;
import com.nori.tc.ui.domain.useradmin.UiPermission;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AC-7 [R-7 권한] — 시나리오 삭제 권한 분리 검증(closed-by-default + ADMIN 명시 매핑 전제).
 *
 * <p>시드 권한:
 * {@code LIVE_MAP_DEMO_RUN_WRITE}(PREFIX /api/live-map/demo-runs, POST) +
 * {@code LIVE_MAP_DEMO_RUN_SCENARIO_DELETE}(PREFIX /api/live-map/demo-runs/scenarios, DELETE).
 * DELETE 는 WRITE(POST)에 매칭되지 않으므로 별도 권한이 필요하다 — SCENARIO_DELETE 미보유면 closed-by-default 차단,
 * 보유(ADMIN 명시 매핑)면 통과. 업로드(POST)는 기존 WRITE 로 통과(신규 매핑 0). 자동 grant 가정 금지(권한을 명시 부여).</p>
 */
class LiveMapScenarioPermissionTest {

    private static final String DEMO_RUN_WRITE = "LIVE_MAP_DEMO_RUN_WRITE";
    private static final String SCENARIO_DELETE = "LIVE_MAP_DEMO_RUN_SCENARIO_DELETE";

    private UiApiPermissionCache seededCache() {
        final UiApiPermissionCache cache = new UiApiPermissionCache(() -> List.of(
                permission(DEMO_RUN_WRITE, UiPermissionMatchType.PREFIX, "/api/live-map/demo-runs", "POST"),
                permission(SCENARIO_DELETE, UiPermissionMatchType.PREFIX, "/api/live-map/demo-runs/scenarios", "DELETE")
        ));
        cache.loadPermissions();
        return cache;
    }

    @Test
    void userWithoutScenarioDeleteCannotDeleteScenario() {
        final UiApiPermissionCache cache = seededCache();
        // WRITE 만 보유(업로드 가능)하지만 SCENARIO_DELETE 미보유.
        final UserPrincipal writeOnlyUser = new UserPrincipal(2L, "operator", Set.of(DEMO_RUN_WRITE));

        assertFalse(cache.isAuthorized(writeOnlyUser, "DELETE", "/api/live-map/demo-runs/scenarios/normal-flow"),
                "SCENARIO_DELETE 미보유 → DELETE 차단(closed-by-default)");
    }

    @Test
    void userWithScenarioDeleteCanDeleteScenario() {
        final UiApiPermissionCache cache = seededCache();
        // ADMIN 명시 매핑을 모사 — SCENARIO_DELETE 보유.
        final UserPrincipal adminUser = new UserPrincipal(1L, "admin", Set.of(DEMO_RUN_WRITE, SCENARIO_DELETE));

        assertTrue(cache.isAuthorized(adminUser, "DELETE", "/api/live-map/demo-runs/scenarios/normal-flow"),
                "SCENARIO_DELETE 보유 → DELETE 통과");
    }

    @Test
    void uploadIsCoveredByExistingDemoRunWrite() {
        final UiApiPermissionCache cache = seededCache();
        final UserPrincipal writeUser = new UserPrincipal(2L, "operator", Set.of(DEMO_RUN_WRITE));

        // 업로드 POST /api/live-map/demo-runs/scenarios 는 기존 WRITE(PREFIX /api/live-map/demo-runs, POST)가 커버.
        assertTrue(cache.isAuthorized(writeUser, "POST", "/api/live-map/demo-runs/scenarios"),
                "업로드 POST 는 기존 LIVE_MAP_DEMO_RUN_WRITE 로 통과(신규 매핑 0)");
    }

    private static UiPermission permission(
            final String permCode,
            final UiPermissionMatchType matchType,
            final String resource,
            final String httpMethod
    ) {
        final OffsetDateTime now = OffsetDateTime.now();
        return new UiPermission(
                1L,
                permCode,
                permCode,
                UiPermissionResourceType.API,
                matchType,
                resource,
                httpMethod,
                permCode,
                true,
                now,
                now,
                "TEST",
                "TEST"
        );
    }
}

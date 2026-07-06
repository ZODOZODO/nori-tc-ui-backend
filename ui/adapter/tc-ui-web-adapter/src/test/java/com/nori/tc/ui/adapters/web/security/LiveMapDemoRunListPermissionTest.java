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
 * AC-B3 [C4 권한] — 목록 {@code GET /api/live-map/demo-runs} 가 기존 {@code LIVE_MAP_DEMO_RUN_READ}
 * (PREFIX /api/live-map/demo-runs, GET)로 커버됨을 확인한다(신규 권한 매핑 0). 단건 {@code /{runId}}·scenarios GET 과
 * 동일 PREFIX 라 목록도 같은 READ 권한에 자연히 포함된다. 미보유면 closed-by-default 차단, 보유면 통과.
 */
class LiveMapDemoRunListPermissionTest {

    private static final String DEMO_RUN_READ = "LIVE_MAP_DEMO_RUN_READ";
    private static final String LIST_PATH = "/api/live-map/demo-runs";

    private UiApiPermissionCache seededCache() {
        final UiApiPermissionCache cache = new UiApiPermissionCache(() -> List.of(
                permission(DEMO_RUN_READ, UiPermissionMatchType.PREFIX, "/api/live-map/demo-runs", "GET")
        ));
        cache.loadPermissions();
        return cache;
    }

    @Test
    void userWithoutDemoRunReadCannotListRuns() {
        final UiApiPermissionCache cache = seededCache();
        final UserPrincipal noReadUser = new UserPrincipal(2L, "operator", Set.of());

        assertFalse(cache.isAuthorized(noReadUser, "GET", LIST_PATH),
                "READ 미보유 → 목록 GET 차단(closed-by-default)");
    }

    @Test
    void userWithDemoRunReadCanListRuns() {
        final UiApiPermissionCache cache = seededCache();
        final UserPrincipal readUser = new UserPrincipal(1L, "viewer", Set.of(DEMO_RUN_READ));

        assertTrue(cache.isAuthorized(readUser, "GET", LIST_PATH),
                "READ 보유 → 목록 GET 통과(기존 LIVE_MAP_DEMO_RUN_READ 로 커버, 신규 매핑 0)");
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

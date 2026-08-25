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
 * AC2 — live-map 레이아웃 write enforce(이중 차단의 백엔드 절반) 검증.
 *
 * <p>Phase 2 시드 권한:
 * {@code LIVE_MAP_LAYOUT_WRITE}(PREFIX /api/live-map/layout, 전 메서드, ADMIN) +
 * {@code LIVE_MAP_LAYOUT_READ}(PREFIX /api/live-map/layout, GET 한정, 전 로그인 그룹).
 * ANY-of 매칭이므로 READ 를 GET 으로 한정해야 PUT/POST/DELETE 가 WRITE 에만 매칭되어
 * 비-admin(WRITE 미보유)이 차단된다.</p>
 */
class LiveMapLayoutPermissionTest {

    private static final String WRITE = "LIVE_MAP_LAYOUT_WRITE";
    private static final String READ = "LIVE_MAP_LAYOUT_READ";

    private UiApiPermissionCache seededCache() {
        final UiApiPermissionCache cache = new UiApiPermissionCache(() -> List.of(
                permission(WRITE, UiPermissionMatchType.PREFIX, "/api/live-map/layout", null),
                permission(READ, UiPermissionMatchType.PREFIX, "/api/live-map/layout", "GET")
        ),
        com.nori.tc.ui.core.coordination.UiPermissionCatalogGuard.disabled());
        cache.loadPermissions();
        return cache;
    }

    @Test
    void readOnlyUserCanGetButCannotWriteLayout() {
        final UiApiPermissionCache cache = seededCache();
        final UserPrincipal readUser = new UserPrincipal(2L, "operator", Set.of(READ));

        // GET 은 READ(GET) 매칭으로 허용
        assertTrue(cache.isAuthorized(readUser, "GET", "/api/live-map/layout"));
        assertTrue(cache.isAuthorized(readUser, "GET", "/api/live-map/layout/objects"));

        // PUT/POST/DELETE 는 WRITE(전 메서드)에만 매칭 → WRITE 미보유 사용자 차단
        assertFalse(cache.isAuthorized(readUser, "PUT", "/api/live-map/layout"));
        assertFalse(cache.isAuthorized(readUser, "POST", "/api/live-map/layout/objects"));
        assertFalse(cache.isAuthorized(readUser, "DELETE", "/api/live-map/layout/objects/3"));
    }

    @Test
    void writeUserCanPerformAllLayoutOperations() {
        final UiApiPermissionCache cache = seededCache();
        final UserPrincipal adminUser = new UserPrincipal(1L, "admin", Set.of(WRITE));

        assertTrue(cache.isAuthorized(adminUser, "GET", "/api/live-map/layout"));
        assertTrue(cache.isAuthorized(adminUser, "PUT", "/api/live-map/layout"));
        assertTrue(cache.isAuthorized(adminUser, "POST", "/api/live-map/layout/objects"));
        assertTrue(cache.isAuthorized(adminUser, "PUT", "/api/live-map/layout/objects/3"));
        assertTrue(cache.isAuthorized(adminUser, "DELETE", "/api/live-map/layout/objects/3"));
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

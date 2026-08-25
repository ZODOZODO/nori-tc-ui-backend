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

class UiApiPermissionCacheTest {

    @Test
    void regexPermissionMatchesDynamicDeployActionPath() {
        final UiApiPermissionCache permissionCache = new UiApiPermissionCache(() -> List.of(
                permission(
                        "DEPLOY_ACTION",
                        UiPermissionMatchType.REGEX,
                        "^/api/deploys/[0-9]+/(start|hold|cancel|redeploy)$",
                        "POST"
                )
        ),
        com.nori.tc.ui.core.coordination.UiPermissionCatalogGuard.disabled());
        permissionCache.loadPermissions();

        final UserPrincipal principal = new UserPrincipal(1L, "admin", Set.of("DEPLOY_ACTION"));

        assertTrue(permissionCache.isAuthorized(principal, "POST", "/api/deploys/7/start"));
        assertFalse(permissionCache.isAuthorized(principal, "POST", "/api/deploys/register/scenario-preview"));
    }

    @Test
    void invalidRegexPermissionDoesNotAuthorizeRequest() {
        final UiApiPermissionCache permissionCache = new UiApiPermissionCache(() -> List.of(
                permission(
                        "DEPLOY_ACTION",
                        UiPermissionMatchType.REGEX,
                        "[invalid-regex",
                        "POST"
                )
        ),
        com.nori.tc.ui.core.coordination.UiPermissionCatalogGuard.disabled());
        permissionCache.loadPermissions();

        final UserPrincipal principal = new UserPrincipal(1L, "admin", Set.of("DEPLOY_ACTION"));

        assertFalse(permissionCache.isAuthorized(principal, "POST", "/api/deploys/7/start"));
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

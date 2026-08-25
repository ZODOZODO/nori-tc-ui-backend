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
 * W3-5 (AC-W3-6) — 권한 코드 기반 그룹 RBAC 의 권한별 접근 거부/허용 자동 검증.
 *
 * <p>기존 {@link LiveMapLayoutPermissionTest}/{@link EqpPollingGroupPermissionTest}/
 * {@link LiveMapScenarioPermissionTest} 가 다루지 않는 permCode 인 {@code USER_READ}(사용자 관리 조회) +
 * {@code DLQ_READ}(DLQ 모니터링 조회) 게이팅을 신규로 검증한다(중복 금지).</p>
 *
 * <p>시드 권한(closed-by-default 전제):
 * {@code USER_READ}(PREFIX /api/user, GET) + {@code DLQ_READ}(PREFIX /api/dlq, GET).
 * 두 endpoint 는 실제 {@code UserController @RequestMapping("/api/user")} GET / {@code DlqController
 * @RequestMapping("/api/dlq")} GET 에 대응한다. DLQ_READ 시드(PREFIX /api/dlq, GET)는 repo seed
 * (postgres_insert_sample_data.sql:768)와 동일하며, USER_READ 는 프론트 permission.ts 의 사용자 페이지
 * 게이팅 코드(USER_READ_PERMISSION) 의미를 그대로 모사한다.</p>
 *
 * <p>{@link UiSecurityConfig} 의 AuthorizationManager 가 호출하는
 * {@link UiApiPermissionCache#isAuthorized} 를 직접 검증한다.</p>
 */
class UiApiPermissionGatingTest {

    private static final String USER_READ = "USER_READ";
    private static final String DLQ_READ = "DLQ_READ";

    private static final String USER_LIST_URI = "/api/user";
    private static final String DLQ_LIST_URI = "/api/dlq/gateway";

    private UiApiPermissionCache seededCache() {
        final UiApiPermissionCache cache = new UiApiPermissionCache(() -> List.of(
                permission(USER_READ, UiPermissionMatchType.PREFIX, "/api/user", "GET"),
                permission(DLQ_READ, UiPermissionMatchType.PREFIX, "/api/dlq", "GET")
        ),
        com.nori.tc.ui.core.coordination.UiPermissionCatalogGuard.disabled());
        cache.loadPermissions();
        return cache;
    }

    @Test
    void userWithoutUserReadIsDeniedUserListAccess() {
        final UiApiPermissionCache cache = seededCache();
        final UserPrincipal noUserRead = new UserPrincipal(2L, "operator", Set.of(DLQ_READ));

        assertFalse(cache.isAuthorized(noUserRead, "GET", USER_LIST_URI),
                "USER_READ 미보유 → 사용자 목록 조회 차단(closed-by-default)");
    }

    @Test
    void userWithUserReadIsAllowedUserListAccess() {
        final UiApiPermissionCache cache = seededCache();
        final UserPrincipal userReader = new UserPrincipal(1L, "admin", Set.of(USER_READ));

        assertTrue(cache.isAuthorized(userReader, "GET", USER_LIST_URI),
                "USER_READ 보유 → 사용자 목록 조회 허용");
    }

    @Test
    void userWithoutDlqReadIsDeniedDlqListAccess() {
        final UiApiPermissionCache cache = seededCache();
        final UserPrincipal noDlqRead = new UserPrincipal(2L, "operator", Set.of(USER_READ));

        assertFalse(cache.isAuthorized(noDlqRead, "GET", DLQ_LIST_URI),
                "DLQ_READ 미보유 → DLQ 목록 조회 차단(closed-by-default)");
    }

    @Test
    void userWithDlqReadIsAllowedDlqListAccess() {
        final UiApiPermissionCache cache = seededCache();
        final UserPrincipal dlqReader = new UserPrincipal(1L, "admin", Set.of(DLQ_READ));

        assertTrue(cache.isAuthorized(dlqReader, "GET", DLQ_LIST_URI),
                "DLQ_READ 보유 → DLQ 목록 조회 허용");
    }

    @Test
    void unmappedUriIsDeniedByClosedDefaultEvenWithAllPermissions() {
        final UiApiPermissionCache cache = seededCache();
        final UserPrincipal superUser = new UserPrincipal(1L, "admin", Set.of(USER_READ, DLQ_READ));

        // 권한 카탈로그에 미등록(매칭 0)인 경로 → 보유 권한과 무관하게 차단.
        assertFalse(cache.isAuthorized(superUser, "GET", "/api/comm-log"),
                "카탈로그 미등록 경로 → closed-by-default 차단");
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

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
 * L1357 — PLC 폴링 그룹 start/end 엔드포인트 인가 검증.
 *
 * <p>그룹 엔드포인트({@code POST /api/eqp/{id}/polling-group/{group}/start|end})는 기존 설비 변경 권한
 * 시드를 그대로 재사용한다(seed 변경 불요): {@code EQP_WRITE}(PREFIX /api/eqp, 전 메서드) +
 * {@code EQP_READ}(PREFIX /api/eqp, GET). closed-by-default 라 POST 는 EQP_WRITE 에만 매칭되므로
 * EQP_WRITE 미보유(OPERATOR=EQP_READ만) 는 403, EQP_WRITE 보유는 허용된다. {@code /api/eqp/{id}/start} 가드와 동일.</p>
 *
 * <p>{@link LiveMapLayoutPermissionTest} 패턴 — {@link UiSecurityConfig} 의 AuthorizationManager 가 호출하는
 * {@link UiApiPermissionCache#isAuthorized} 를 직접 검증한다.</p>
 */
class EqpPollingGroupPermissionTest {

    private static final String EQP_WRITE = "EQP_WRITE";
    private static final String EQP_READ = "EQP_READ";

    private static final String START_URI = "/api/eqp/PLC_001/polling-group/EQP_STATUS_REPORT/start";
    private static final String END_URI = "/api/eqp/PLC_001/polling-group/EQP_STATUS_REPORT/end";

    private UiApiPermissionCache seededCache() {
        final UiApiPermissionCache cache = new UiApiPermissionCache(() -> List.of(
                permission(EQP_WRITE, UiPermissionMatchType.PREFIX, "/api/eqp", null),
                permission(EQP_READ, UiPermissionMatchType.PREFIX, "/api/eqp", "GET")
        ),
        com.nori.tc.ui.core.coordination.UiPermissionCatalogGuard.disabled());
        cache.loadPermissions();
        return cache;
    }

    @Test
    void operatorWithoutEqpWriteIsForbiddenFromGroupStartAndEnd() {
        final UiApiPermissionCache cache = seededCache();
        final UserPrincipal operator = new UserPrincipal(2L, "operator", Set.of(EQP_READ));

        // GET 조회는 EQP_READ 로 허용되지만,
        assertTrue(cache.isAuthorized(operator, "GET", "/api/eqp"));
        // 그룹 start/end 는 POST → EQP_WRITE 에만 매칭 → EQP_WRITE 미보유 OPERATOR 차단(403).
        assertFalse(cache.isAuthorized(operator, "POST", START_URI));
        assertFalse(cache.isAuthorized(operator, "POST", END_URI));
    }

    @Test
    void userWithEqpWriteCanInvokeGroupStartAndEnd() {
        final UiApiPermissionCache cache = seededCache();
        final UserPrincipal writer = new UserPrincipal(1L, "admin", Set.of(EQP_WRITE));

        assertTrue(cache.isAuthorized(writer, "POST", START_URI));
        assertTrue(cache.isAuthorized(writer, "POST", END_URI));
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

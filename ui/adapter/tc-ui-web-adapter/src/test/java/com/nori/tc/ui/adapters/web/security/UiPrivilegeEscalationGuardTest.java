package com.nori.tc.ui.adapters.web.security;

import com.nori.tc.ui.core.exception.UiForbiddenException;
import com.nori.tc.ui.core.model.PagedResponse;
import com.nori.tc.ui.core.model.UiPageRequest;
import com.nori.tc.ui.core.port.db.GroupPermissionMappingPort;
import com.nori.tc.ui.core.port.db.PermissionCrudPort;
import com.nori.tc.ui.core.port.db.PermissionPort;
import com.nori.tc.ui.domain.auth.UserPrincipal;
import com.nori.tc.ui.domain.common.user.UiPermissionMatchType;
import com.nori.tc.ui.domain.common.user.UiPermissionResourceType;
import com.nori.tc.ui.domain.useradmin.UiPermission;
import com.nori.tc.ui.domain.useradmin.UiUserGroupPermission;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * S08 E-2 — 권한 상승 경로가 서버측에서 차단되는지 검증합니다.
 *
 * <p>불변식: actor 는 자신이 보유하지 않은 권한을 남에게 부여할 수도, 그 권한 보유자를 탈취할 수도 없다.</p>
 */
class UiPrivilegeEscalationGuardTest {

    private static final long PERM_ID_GROUP_WRITE = 11L;
    private static final long PERM_ID_PERMISSION_WRITE = 12L;
    private static final long ADMIN_GROUP_ID = 1L;
    private static final long OPERATOR_GROUP_ID = 2L;

    private final Map<Long, String> permCodesByPermId = new HashMap<>(Map.of(
            PERM_ID_GROUP_WRITE, "GROUP_WRITE",
            PERM_ID_PERMISSION_WRITE, "PERMISSION_WRITE"
    ));
    private final Map<Long, List<Long>> permIdsByGroupId = new HashMap<>(Map.of(
            ADMIN_GROUP_ID, List.of(PERM_ID_GROUP_WRITE, PERM_ID_PERMISSION_WRITE),
            OPERATOR_GROUP_ID, List.of(PERM_ID_GROUP_WRITE)
    ));
    private final Map<Long, Set<String>> permCodesByUserPk = new HashMap<>(Map.of(
            99L, Set.of("GROUP_WRITE", "PERMISSION_WRITE", "USER_INFO_WRITE")
    ));

    private final UiPrivilegeEscalationGuard guard = new UiPrivilegeEscalationGuard(
            groupPermissionMappingPort(),
            permissionCrudPort(),
            permissionPort()
    );

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("S08 E-2: GROUP_WRITE 만 가진 사용자는 PERMISSION_WRITE 를 부여할 수 없습니다")
    void grantIsRejectedWhenActorDoesNotHoldTargetPermission() {
        authenticateAs(5L, "group-manager", Set.of("GROUP_WRITE"));

        assertThrows(UiForbiddenException.class,
                () -> guard.requireGrantablePermission(PERM_ID_PERMISSION_WRITE));
    }

    @Test
    @DisplayName("S08 E-2: 본인이 보유한 권한은 부여할 수 있습니다(정상 경로 보존)")
    void grantIsAllowedWhenActorHoldsTargetPermission() {
        authenticateAs(5L, "group-manager", Set.of("GROUP_WRITE"));

        assertDoesNotThrow(() -> guard.requireGrantablePermission(PERM_ID_GROUP_WRITE));
    }

    @Test
    @DisplayName("S08 E-2: USER_INFO_WRITE 보유자는 자신을 ADMIN 그룹에 편입할 수 없습니다")
    void groupAssignIsRejectedWhenGroupHoldsWiderPermissions() {
        authenticateAs(5L, "user-manager", Set.of("USER_INFO_WRITE"));

        assertThrows(UiForbiddenException.class, () -> guard.requireAssignableGroup(ADMIN_GROUP_ID));
    }

    @Test
    @DisplayName("S08 E-2: 그룹 권한이 actor 권한의 부분집합이면 편입할 수 있습니다(정상 경로 보존)")
    void groupAssignIsAllowedWhenGroupPermissionsAreSubset() {
        authenticateAs(5L, "admin", Set.of("GROUP_WRITE", "PERMISSION_WRITE", "USER_INFO_WRITE"));

        assertDoesNotThrow(() -> guard.requireAssignableGroup(OPERATOR_GROUP_ID));
    }

    @Test
    @DisplayName("S08 E-2: 자신보다 넓은 권한을 가진 사용자의 비밀번호는 재설정할 수 없습니다")
    void passwordResetIsRejectedForWiderPrivilegedUser() {
        authenticateAs(5L, "user-manager", Set.of("USER_INFO_WRITE"));

        assertThrows(UiForbiddenException.class, () -> guard.requireResettablePassword(99L));
    }

    @Test
    @DisplayName("S08 E-2: 본인 비밀번호 재설정은 허용합니다")
    void passwordResetIsAllowedForSelf() {
        authenticateAs(99L, "admin", Set.of("GROUP_WRITE", "PERMISSION_WRITE", "USER_INFO_WRITE"));

        assertDoesNotThrow(() -> guard.requireResettablePassword(99L));
    }

    @Test
    @DisplayName("S08 E-2: 인증 컨텍스트가 없으면 권한 부여를 거부합니다")
    void grantIsRejectedWithoutAuthenticatedActor() {
        assertThrows(UiForbiddenException.class,
                () -> guard.requireGrantablePermission(PERM_ID_GROUP_WRITE));
    }

    private static void authenticateAs(final long userPk, final String userId, final Set<String> permissionCodes) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new UserPrincipal(userPk, userId, permissionCodes),
                null,
                AuthorityUtils.createAuthorityList("ROLE_USER")));
    }

    private GroupPermissionMappingPort groupPermissionMappingPort() {
        return new GroupPermissionMappingPort() {
            @Override
            public com.nori.tc.ui.domain.useradmin.UiUserGroupPermission upsert(
                    final com.nori.tc.ui.core.model.UiUserGroupPermissionUpsertCommand command
            ) {
                throw new UnsupportedOperationException("이 테스트에서 사용하지 않습니다.");
            }

            @Override
            public Optional<UiUserGroupPermission> findByGroupIdPermId(final long groupId, final long permId) {
                throw new UnsupportedOperationException("이 테스트에서 사용하지 않습니다.");
            }

            @Override
            public PagedResponse<UiUserGroupPermission> findAllByGroupId(
                    final long groupId,
                    final UiPageRequest pageRequest
            ) {
                final List<UiUserGroupPermission> mappings = permIdsByGroupId
                        .getOrDefault(groupId, List.of())
                        .stream()
                        .map(permId -> new UiUserGroupPermission(
                                permId, groupId, permId, OffsetDateTime.parse("2026-08-04T00:00:00Z"), "SYSTEM"))
                        .toList();
                return new PagedResponse<>(mappings, 0, Math.max(1, mappings.size()), mappings.size());
            }

            @Override
            public void deleteByGroupIdPermId(final long groupId, final long permId) {
                throw new UnsupportedOperationException("이 테스트에서 사용하지 않습니다.");
            }
        };
    }

    private PermissionCrudPort permissionCrudPort() {
        return new PermissionCrudPort() {
            @Override
            public UiPermission upsert(final com.nori.tc.ui.core.model.UiPermissionUpsertCommand command) {
                throw new UnsupportedOperationException("이 테스트에서 사용하지 않습니다.");
            }

            @Override
            public Optional<UiPermission> findByPermId(final long permId) {
                return Optional.ofNullable(permCodesByPermId.get(permId)).map(permCode -> new UiPermission(
                        permId,
                        permCode,
                        permCode,
                        UiPermissionResourceType.API,
                        UiPermissionMatchType.PREFIX,
                        "/api/x",
                        null,
                        null,
                        true,
                        OffsetDateTime.parse("2026-08-04T00:00:00Z"),
                        OffsetDateTime.parse("2026-08-04T00:00:00Z"),
                        "SYSTEM",
                        "SYSTEM"
                ));
            }

            @Override
            public PagedResponse<UiPermission> findAll(final UiPageRequest pageRequest) {
                throw new UnsupportedOperationException("이 테스트에서 사용하지 않습니다.");
            }

            @Override
            public void deleteByPermId(final long permId) {
                throw new UnsupportedOperationException("이 테스트에서 사용하지 않습니다.");
            }
        };
    }

    private PermissionPort permissionPort() {
        return userPk -> permCodesByUserPk.getOrDefault(userPk, Set.of());
    }
}

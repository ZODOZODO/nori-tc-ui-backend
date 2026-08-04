package com.nori.tc.ui.adapters.web.application.admin;

import com.nori.tc.ui.adapters.web.security.UiPrivilegeEscalationGuard;
import com.nori.tc.ui.core.exception.UiForbiddenException;
import com.nori.tc.ui.core.port.db.AuditLogPort;
import com.nori.tc.ui.core.port.db.GroupCrudPort;
import com.nori.tc.ui.core.port.db.GroupPermissionMappingPort;
import com.nori.tc.ui.core.port.db.PermissionCrudPort;
import com.nori.tc.ui.core.port.db.PermissionPort;
import com.nori.tc.ui.core.port.db.UserCrudPort;
import com.nori.tc.ui.core.port.db.UserGroupMappingPort;
import com.nori.tc.ui.domain.auth.UserPrincipal;
import com.nori.tc.ui.domain.common.user.UiPermissionMatchType;
import com.nori.tc.ui.domain.common.user.UiPermissionResourceType;
import com.nori.tc.ui.domain.useradmin.UiPermission;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S08 E-2 — 권한 부여 API 가 승격 경로로 쓰이지 않는지 facade 경계에서 검증합니다.
 *
 * <p>{@code GROUP_WRITE} 하나가 {@code /api/group} 아래 9개 엔드포인트를 전부 덮으므로,
 * "그룹 관리만" 부여받은 사용자도 권한 부여 API 에 도달한다. 그 상태에서 매핑이 저장되면
 * 스스로 ADMIN 급 권한을 획득한다.</p>
 */
class GroupAdminFacadeEscalationTest {

    private static final long PERM_ID_PERMISSION_WRITE = 12L;

    private final GroupPermissionMappingPort groupPermissionMappingPort = mock(GroupPermissionMappingPort.class);
    private final PermissionCrudPort permissionCrudPort = mock(PermissionCrudPort.class);
    private final GroupAdminFacade facade = new GroupAdminFacade(
            mock(GroupCrudPort.class),
            groupPermissionMappingPort,
            mock(UserGroupMappingPort.class),
            mock(UserCrudPort.class),
            mock(GroupAdminAssembler.class),
            mock(AuditLogPort.class),
            new UiPrivilegeEscalationGuard(groupPermissionMappingPort, permissionCrudPort, PermissionPort.noop()));

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("S08 E-2: GROUP_WRITE 만 가진 사용자의 PERMISSION_WRITE 부여는 저장되지 않습니다")
    void addPermissionMappingIsRejectedForSelfEscalation() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new UserPrincipal(5L, "group-manager", Set.of("GROUP_WRITE")),
                null,
                AuthorityUtils.createAuthorityList("ROLE_USER")));
        when(permissionCrudPort.findByPermId(PERM_ID_PERMISSION_WRITE)).thenReturn(Optional.of(new UiPermission(
                PERM_ID_PERMISSION_WRITE,
                "PERMISSION_WRITE",
                "PERMISSION_WRITE",
                UiPermissionResourceType.API,
                UiPermissionMatchType.PREFIX,
                "/api/permission",
                null,
                null,
                true,
                OffsetDateTime.parse("2026-08-04T00:00:00Z"),
                OffsetDateTime.parse("2026-08-04T00:00:00Z"),
                "SYSTEM",
                "SYSTEM"
        )));

        assertThrows(UiForbiddenException.class,
                () -> facade.addPermissionMapping(9L, PERM_ID_PERMISSION_WRITE));

        verify(groupPermissionMappingPort, never()).upsert(any());
    }
}

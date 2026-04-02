package com.nori.tc.ui.adapters.web.application.admin;

import com.nori.tc.ui.adapters.web.dto.request.PermissionUpsertRequest;
import com.nori.tc.ui.adapters.web.dto.response.PermissionInfoResponse;
import com.nori.tc.ui.core.port.db.GroupCrudPort;
import com.nori.tc.ui.core.port.db.GroupPermissionMappingPort;
import com.nori.tc.ui.core.port.db.PermissionCrudPort;
import com.nori.tc.ui.domain.common.user.UiPermissionMatchType;
import com.nori.tc.ui.domain.common.user.UiPermissionResourceType;
import com.nori.tc.ui.domain.useradmin.UiPermission;
import com.nori.tc.ui.domain.useradmin.UiUserGroup;
import com.nori.tc.ui.domain.useradmin.UiUserGroupPermission;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatcher;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PermissionAdminFacadeTest {

    private static final long ADMIN_GROUP_ID = 11L;
    private static final long PERMISSION_ID = 101L;

    @Mock
    private PermissionCrudPort permissionCrudPort;

    @Mock
    private GroupCrudPort groupCrudPort;

    @Mock
    private GroupPermissionMappingPort groupPermissionMappingPort;

    private final PermissionAdminAssembler assembler = new PermissionAdminAssembler();

    @Test
    @DisplayName("권한 생성 시 ADMIN 그룹에 권한 매핑을 자동 부여합니다.")
    void createAddsPermissionToAdminGroup() {
        final PermissionAdminFacade facade = new PermissionAdminFacade(
                permissionCrudPort,
                groupCrudPort,
                groupPermissionMappingPort,
                assembler
        );
        final PermissionUpsertRequest request = request("CREATOR", "CREATOR");
        final UiPermission savedPermission = permission("CREATOR", "CREATOR");

        when(permissionCrudPort.upsert(any())).thenReturn(savedPermission);
        when(groupCrudPort.findByGroupCode("ADMIN")).thenReturn(Optional.of(adminGroup()));
        when(groupPermissionMappingPort.upsert(any())).thenReturn(mapping("CREATOR"));

        final PermissionInfoResponse response = facade.create(request);

        assertEquals(PERMISSION_ID, response.permId());
        verify(groupPermissionMappingPort).upsert(argThat(matchesAdminMapping(PERMISSION_ID, "CREATOR")));
    }

    @Test
    @DisplayName("권한 수정 시 ADMIN 그룹에 권한 매핑을 자동 보정합니다.")
    void updateReconcilesPermissionToAdminGroup() {
        final PermissionAdminFacade facade = new PermissionAdminFacade(
                permissionCrudPort,
                groupCrudPort,
                groupPermissionMappingPort,
                assembler
        );
        final PermissionUpsertRequest request = request("SYSTEM", "ADMIN_USER");
        final UiPermission existingPermission = permission("SYSTEM", "SYSTEM");
        final UiPermission updatedPermission = permission("SYSTEM", "ADMIN_USER");

        when(permissionCrudPort.findByPermId(PERMISSION_ID)).thenReturn(Optional.of(existingPermission));
        when(permissionCrudPort.upsert(any())).thenReturn(updatedPermission);
        when(groupCrudPort.findByGroupCode("ADMIN")).thenReturn(Optional.of(adminGroup()));
        when(groupPermissionMappingPort.upsert(any())).thenReturn(mapping("ADMIN_USER"));

        final Optional<PermissionInfoResponse> response = facade.update(PERMISSION_ID, request);

        assertTrue(response.isPresent());
        assertEquals(PERMISSION_ID, response.get().permId());
        verify(groupPermissionMappingPort).upsert(argThat(matchesAdminMapping(PERMISSION_ID, "ADMIN_USER")));
    }

    @Test
    @DisplayName("ADMIN 그룹이 없으면 자동 매핑은 생략하고 권한 저장은 유지합니다.")
    void createSkipsAutoGrantWhenAdminGroupMissing() {
        final PermissionAdminFacade facade = new PermissionAdminFacade(
                permissionCrudPort,
                groupCrudPort,
                groupPermissionMappingPort,
                assembler
        );
        final PermissionUpsertRequest request = request("SYSTEM", "SYSTEM");
        final UiPermission savedPermission = permission("SYSTEM", "SYSTEM");

        when(permissionCrudPort.upsert(any())).thenReturn(savedPermission);
        when(groupCrudPort.findByGroupCode("ADMIN")).thenReturn(Optional.empty());

        final PermissionInfoResponse response = facade.create(request);

        assertEquals(PERMISSION_ID, response.permId());
        verify(groupPermissionMappingPort, never()).upsert(any());
    }

    private static PermissionUpsertRequest request(final String createdBy, final String updatedBy) {
        return new PermissionUpsertRequest(
                "DEPLOY_ACTION",
                "Deploy Action",
                UiPermissionResourceType.API,
                UiPermissionMatchType.REGEX,
                "^/api/deploys/[0-9]+/start$",
                "POST",
                "deploy start",
                true,
                createdBy,
                updatedBy
        );
    }

    private static UiPermission permission(final String createdBy, final String updatedBy) {
        final OffsetDateTime now = OffsetDateTime.now();
        return new UiPermission(
                PERMISSION_ID,
                "DEPLOY_ACTION",
                "Deploy Action",
                UiPermissionResourceType.API,
                UiPermissionMatchType.REGEX,
                "^/api/deploys/[0-9]+/start$",
                "POST",
                "deploy start",
                true,
                now,
                now,
                createdBy,
                updatedBy
        );
    }

    private static UiUserGroup adminGroup() {
        final OffsetDateTime now = OffsetDateTime.now();
        return new UiUserGroup(
                ADMIN_GROUP_ID,
                "ADMIN",
                "관리자",
                "모든 권한 보유",
                true,
                now,
                now
        );
    }

    private static UiUserGroupPermission mapping(final String grantedBy) {
        return new UiUserGroupPermission(
                501L,
                ADMIN_GROUP_ID,
                PERMISSION_ID,
                OffsetDateTime.now(),
                grantedBy
        );
    }

    private static ArgumentMatcher<com.nori.tc.ui.core.model.UiUserGroupPermissionUpsertCommand> matchesAdminMapping(
            final long permId,
            final String grantedBy
    ) {
        return command -> command != null
                && command.groupId() == ADMIN_GROUP_ID
                && command.permId() == permId
                && grantedBy.equals(command.grantedBy())
                && command.grantedAt() != null;
    }
}

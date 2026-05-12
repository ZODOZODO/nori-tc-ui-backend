package com.nori.tc.ui.adapters.web.application.admin;

import com.nori.tc.ui.adapters.web.dto.request.GroupUpsertRequest;
import com.nori.tc.ui.adapters.web.dto.response.GroupInfoResponse;
import com.nori.tc.ui.adapters.web.dto.response.GroupMemberResponse;
import com.nori.tc.ui.adapters.web.dto.response.GroupPermissionMappingResponse;
import com.nori.tc.ui.core.model.PagedResponse;
import com.nori.tc.ui.core.model.UiUserGroupPermissionUpsertCommand;
import com.nori.tc.ui.core.model.UiUserGroupUpsertCommand;
import com.nori.tc.ui.domain.useradmin.UiManagedUser;
import com.nori.tc.ui.domain.useradmin.UiUserGroup;
import com.nori.tc.ui.domain.useradmin.UiUserGroupMember;
import com.nori.tc.ui.domain.useradmin.UiUserGroupPermission;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Group 관리 요청/응답 변환을 담당합니다.
 */
@Component
public class GroupAdminAssembler {

    public UiUserGroupUpsertCommand toCreateCommand(final GroupUpsertRequest request) {
        return new UiUserGroupUpsertCommand(
                null,
                request.groupCode(),
                request.groupName(),
                request.description(),
                resolveIsActive(request.isActive())
        );
    }

    public UiUserGroupUpsertCommand toUpdateCommand(final long groupId, final GroupUpsertRequest request) {
        return new UiUserGroupUpsertCommand(
                groupId,
                request.groupCode(),
                request.groupName(),
                request.description(),
                resolveIsActive(request.isActive())
        );
    }

    public UiUserGroupPermissionUpsertCommand toPermissionMappingCommand(
            final long groupId,
            final long permId,
            final String grantedBy
    ) {
        return new UiUserGroupPermissionUpsertCommand(groupId, permId, null, grantedBy);
    }

    public PagedResponse<GroupInfoResponse> toGroupPage(final PagedResponse<UiUserGroup> page) {
        final List<GroupInfoResponse> items = page.items().stream()
                .map(this::toGroupInfoResponse)
                .toList();
        return PagedResponse.of(items, page.offset(), page.limit(), page.count());
    }

    public GroupInfoResponse toGroupInfoResponse(final UiUserGroup group) {
        return new GroupInfoResponse(
                group.groupId(),
                group.groupCode(),
                group.groupName(),
                group.description(),
                group.isActive(),
                group.createdAt(),
                group.updatedAt()
        );
    }

    public PagedResponse<GroupPermissionMappingResponse> toGroupPermissionPage(
            final PagedResponse<UiUserGroupPermission> page
    ) {
        final List<GroupPermissionMappingResponse> items = page.items().stream()
                .map(this::toGroupPermissionMappingResponse)
                .toList();
        return PagedResponse.of(items, page.offset(), page.limit(), page.count());
    }

    public GroupPermissionMappingResponse toGroupPermissionMappingResponse(
            final UiUserGroupPermission mapping
    ) {
        return new GroupPermissionMappingResponse(
                mapping.ugpKey(),
                mapping.groupId(),
                mapping.permId(),
                mapping.grantedAt(),
                mapping.grantedBy()
        );
    }

    public PagedResponse<GroupMemberResponse> toGroupMemberPage(
            final PagedResponse<UiUserGroupMember> page,
            final Map<Long, UiManagedUser> usersByUserPk
    ) {
        final List<GroupMemberResponse> items = page.items().stream()
                .map(mapping -> toGroupMemberResponse(mapping, usersByUserPk.get(mapping.userPk())))
                .toList();
        return PagedResponse.of(items, page.offset(), page.limit(), page.count());
    }

    public GroupMemberResponse toGroupMemberResponse(
            final UiUserGroupMember mapping,
            final UiManagedUser user
    ) {
        return new GroupMemberResponse(
                mapping.userPk(),
                user == null ? null : user.userId(),
                user == null ? null : user.userName(),
                user == null ? null : user.company(),
                user == null ? null : user.department()
        );
    }

    private static boolean resolveIsActive(final Boolean isActive) {
        return isActive == null || isActive;
    }
}

package com.nori.tc.ui.adapters.web.application.admin;

import com.nori.tc.ui.adapters.web.dto.request.GroupUpsertRequest;
import com.nori.tc.ui.adapters.web.dto.response.GroupInfoResponse;
import com.nori.tc.ui.adapters.web.dto.response.GroupPermissionMappingResponse;
import com.nori.tc.ui.core.model.PagedResponse;
import com.nori.tc.ui.core.model.UiPageRequest;
import com.nori.tc.ui.core.port.db.GroupCrudPort;
import com.nori.tc.ui.core.port.db.GroupPermissionMappingPort;
import com.nori.tc.ui.domain.useradmin.UiUserGroupPermission;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;

/**
 * Group 관리 use-case를 controller 밖으로 이동시키는 facade 입니다.
 */
@Component
public class GroupAdminFacade {

    private static final String DEFAULT_GRANTED_BY = "SYSTEM";

    private final GroupCrudPort groupCrudPort;
    private final GroupPermissionMappingPort groupPermissionMappingPort;
    private final GroupAdminAssembler assembler;

    public GroupAdminFacade(
            final GroupCrudPort groupCrudPort,
            final GroupPermissionMappingPort groupPermissionMappingPort,
            final GroupAdminAssembler assembler
    ) {
        this.groupCrudPort = Objects.requireNonNull(groupCrudPort, "groupCrudPort is null");
        this.groupPermissionMappingPort = Objects.requireNonNull(groupPermissionMappingPort, "groupPermissionMappingPort is null");
        this.assembler = Objects.requireNonNull(assembler, "assembler is null");
    }

    public PagedResponse<GroupInfoResponse> list(final UiPageRequest pageRequest) {
        return assembler.toGroupPage(groupCrudPort.findAll(pageRequest));
    }

    public Optional<GroupInfoResponse> findByGroupId(final long groupId) {
        return groupCrudPort.findByGroupId(groupId)
                .map(assembler::toGroupInfoResponse);
    }

    public GroupInfoResponse create(final GroupUpsertRequest request) {
        return assembler.toGroupInfoResponse(groupCrudPort.upsert(assembler.toCreateCommand(request)));
    }

    public Optional<GroupInfoResponse> update(final long groupId, final GroupUpsertRequest request) {
        return groupCrudPort.findByGroupId(groupId)
                .map(existing -> assembler.toGroupInfoResponse(
                        groupCrudPort.upsert(assembler.toUpdateCommand(existing.groupId(), request))
                ));
    }

    public void delete(final long groupId) {
        groupCrudPort.deleteByGroupId(groupId);
    }

    public Optional<PagedResponse<GroupPermissionMappingResponse>> listPermissions(
            final long groupId,
            final UiPageRequest pageRequest
    ) {
        if (groupCrudPort.findByGroupId(groupId).isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(assembler.toGroupPermissionPage(
                groupPermissionMappingPort.findAllByGroupId(groupId, pageRequest)
        ));
    }

    public GroupPermissionMappingResponse addPermissionMapping(final long groupId, final long permId) {
        final UiUserGroupPermission saved = groupPermissionMappingPort.upsert(
                assembler.toPermissionMappingCommand(groupId, permId, DEFAULT_GRANTED_BY)
        );
        return assembler.toGroupPermissionMappingResponse(saved);
    }

    public void removePermissionMapping(final long groupId, final long permId) {
        groupPermissionMappingPort.deleteByGroupIdPermId(groupId, permId);
    }
}

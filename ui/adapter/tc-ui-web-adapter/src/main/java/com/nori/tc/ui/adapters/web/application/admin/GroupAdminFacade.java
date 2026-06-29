package com.nori.tc.ui.adapters.web.application.admin;

import com.nori.tc.ui.adapters.web.dto.request.GroupUpsertRequest;
import com.nori.tc.ui.adapters.web.dto.response.GroupInfoResponse;
import com.nori.tc.ui.adapters.web.dto.response.GroupMemberResponse;
import com.nori.tc.ui.adapters.web.dto.response.GroupPermissionMappingResponse;
import com.nori.tc.ui.core.model.PagedResponse;
import com.nori.tc.ui.core.model.UiPageRequest;
import com.nori.tc.ui.core.port.db.AuditLogPort;
import com.nori.tc.ui.core.port.db.GroupCrudPort;
import com.nori.tc.ui.core.port.db.GroupPermissionMappingPort;
import com.nori.tc.ui.core.port.db.UserCrudPort;
import com.nori.tc.ui.core.port.db.UserGroupMappingPort;
import com.nori.tc.ui.domain.audit.UiAuditEvent;
import com.nori.tc.ui.domain.auth.UserPrincipal;
import com.nori.tc.ui.domain.useradmin.UiManagedUser;
import com.nori.tc.ui.domain.useradmin.UiUserGroupMember;
import com.nori.tc.ui.domain.useradmin.UiUserGroupPermission;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Group 관리 use-case를 controller 밖으로 이동시키는 facade 입니다.
 */
@Component
public class GroupAdminFacade {

    private static final String DEFAULT_GRANTED_BY = "SYSTEM";
    private static final String UNKNOWN_ACTOR = "SYSTEM";

    private final GroupCrudPort groupCrudPort;
    private final GroupPermissionMappingPort groupPermissionMappingPort;
    private final UserGroupMappingPort userGroupMappingPort;
    private final UserCrudPort userCrudPort;
    private final GroupAdminAssembler assembler;
    private final AuditLogPort auditLogPort;

    public GroupAdminFacade(
            final GroupCrudPort groupCrudPort,
            final GroupPermissionMappingPort groupPermissionMappingPort,
            final UserGroupMappingPort userGroupMappingPort,
            final UserCrudPort userCrudPort,
            final GroupAdminAssembler assembler,
            final AuditLogPort auditLogPort
    ) {
        this.groupCrudPort = Objects.requireNonNull(groupCrudPort, "groupCrudPort is null");
        this.groupPermissionMappingPort = Objects.requireNonNull(groupPermissionMappingPort, "groupPermissionMappingPort is null");
        this.userGroupMappingPort = Objects.requireNonNull(userGroupMappingPort, "userGroupMappingPort is null");
        this.userCrudPort = Objects.requireNonNull(userCrudPort, "userCrudPort is null");
        this.assembler = Objects.requireNonNull(assembler, "assembler is null");
        this.auditLogPort = Objects.requireNonNull(auditLogPort, "auditLogPort is null");
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
        auditLogPort.record(UiAuditEvent.permissionGrant(currentActor(), permissionTarget(groupId, permId)));
        return assembler.toGroupPermissionMappingResponse(saved);
    }

    public void removePermissionMapping(final long groupId, final long permId) {
        groupPermissionMappingPort.deleteByGroupIdPermId(groupId, permId);
        auditLogPort.record(UiAuditEvent.permissionRevoke(currentActor(), permissionTarget(groupId, permId)));
    }

    private static String permissionTarget(final long groupId, final long permId) {
        return "group:" + groupId + "/perm:" + permId;
    }

    /**
     * 현재 인증된 사용자(권한 변경 actor)를 SecurityContext 에서 해석합니다.
     *
     * <p>토큰 인증을 거치지 않은 경로(부재/익명)는 {@link #UNKNOWN_ACTOR} 로 기록합니다.</p>
     *
     * @return actor 식별자(로그인 ID) 또는 "SYSTEM"
     */
    private static String currentActor() {
        final Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof UserPrincipal principal) {
            return principal.userId();
        }
        return UNKNOWN_ACTOR;
    }

    public Optional<PagedResponse<GroupMemberResponse>> listUsersByGroupId(
            final long groupId,
            final UiPageRequest pageRequest
    ) {
        if (groupCrudPort.findByGroupId(groupId).isEmpty()) {
            return Optional.empty();
        }
        final PagedResponse<UiUserGroupMember> mappings = userGroupMappingPort.findAllByGroupId(groupId, pageRequest);
        final Map<Long, UiManagedUser> usersByUserPk = mappings.items().stream()
                .map(UiUserGroupMember::userPk)
                .distinct()
                .map(userCrudPort::findByUserPk)
                .flatMap(Optional::stream)
                .collect(Collectors.toMap(UiManagedUser::userPk, Function.identity()));
        return Optional.of(assembler.toGroupMemberPage(mappings, usersByUserPk));
    }
}

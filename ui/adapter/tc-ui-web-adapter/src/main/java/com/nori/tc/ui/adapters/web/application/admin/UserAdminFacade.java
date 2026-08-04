package com.nori.tc.ui.adapters.web.application.admin;

import com.nori.tc.ui.adapters.web.dto.request.UserPasswordResetRequest;
import com.nori.tc.ui.adapters.web.dto.request.UserUpsertRequest;
import com.nori.tc.ui.adapters.web.dto.response.UserGroupMappingResponse;
import com.nori.tc.ui.adapters.web.dto.response.UserGroupMembershipResponse;
import com.nori.tc.ui.adapters.web.dto.response.UserInfoResponse;
import com.nori.tc.ui.adapters.web.dto.response.UserListItemResponse;
import com.nori.tc.ui.adapters.web.security.UiPasswordPolicyService;
import com.nori.tc.ui.adapters.web.security.UiPrivilegeEscalationGuard;
import com.nori.tc.ui.core.model.PagedResponse;
import com.nori.tc.ui.core.model.UiPageRequest;
import com.nori.tc.ui.core.port.db.GroupCrudPort;
import com.nori.tc.ui.core.port.db.UserCrudPort;
import com.nori.tc.ui.core.port.db.UserGroupMappingPort;
import com.nori.tc.ui.domain.useradmin.UiManagedUser;
import com.nori.tc.ui.domain.useradmin.UiUserGroup;
import com.nori.tc.ui.domain.useradmin.UiUserGroupMember;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * User 관리 use-case를 HTTP 계층 밖으로 이동시키는 facade 입니다.
 */
@Component
public class UserAdminFacade {

    private static final String DEFAULT_GRANTED_BY = "SYSTEM";

    private final UserCrudPort userCrudPort;
    private final UserGroupMappingPort userGroupMappingPort;
    private final GroupCrudPort groupCrudPort;
    private final UiPasswordPolicyService passwordPolicyService;
    private final UserAdminAssembler assembler;
    private final UiPrivilegeEscalationGuard escalationGuard;

    public UserAdminFacade(
            final UserCrudPort userCrudPort,
            final UserGroupMappingPort userGroupMappingPort,
            final GroupCrudPort groupCrudPort,
            final UiPasswordPolicyService passwordPolicyService,
            final UserAdminAssembler assembler,
            final UiPrivilegeEscalationGuard escalationGuard
    ) {
        this.userCrudPort = Objects.requireNonNull(userCrudPort, "userCrudPort is null");
        this.userGroupMappingPort = Objects.requireNonNull(userGroupMappingPort, "userGroupMappingPort is null");
        this.groupCrudPort = Objects.requireNonNull(groupCrudPort, "groupCrudPort is null");
        this.passwordPolicyService = Objects.requireNonNull(passwordPolicyService, "passwordPolicyService is null");
        this.assembler = Objects.requireNonNull(assembler, "assembler is null");
        this.escalationGuard = Objects.requireNonNull(escalationGuard, "escalationGuard is null");
    }

    public PagedResponse<UserListItemResponse> list(final UiPageRequest pageRequest) {
        final PagedResponse<UiManagedUser> userPage = userCrudPort.findAll(pageRequest);

        final List<Long> userPks = userPage.items().stream()
                .map(UiManagedUser::userPk)
                .toList();
        final List<UiUserGroupMember> mappings = userGroupMappingPort.findAllByUserPkIn(userPks);

        final Map<Long, UiUserGroup> groupsByGroupId = mappings.stream()
                .map(UiUserGroupMember::groupId)
                .distinct()
                .map(groupCrudPort::findByGroupId)
                .flatMap(Optional::stream)
                .collect(Collectors.toMap(UiUserGroup::groupId, Function.identity()));

        final Map<Long, List<UserListItemResponse.GroupSummary>> groupsByUserPk = new HashMap<>();
        for (UiUserGroupMember mapping : mappings) {
            final UiUserGroup group = groupsByGroupId.get(mapping.groupId());
            if (group == null) {
                continue;
            }
            groupsByUserPk
                    .computeIfAbsent(mapping.userPk(), key -> new ArrayList<>())
                    .add(assembler.toGroupSummary(group));
        }

        return assembler.toUserListItemPage(userPage, groupsByUserPk);
    }

    public Optional<UserInfoResponse> findByUserPk(final long userPk) {
        return userCrudPort.findByUserPk(userPk)
                .map(assembler::toUserInfoResponse);
    }

    public UserInfoResponse create(final UserUpsertRequest request) {
        final String passwordHash = passwordPolicyService.encodeNewPassword(
                assembler.requireCreatePassword(request.password())
        );
        return assembler.toUserInfoResponse(
                userCrudPort.upsert(assembler.toCreateCommand(request, passwordHash))
        );
    }

    public Optional<UserInfoResponse> update(final long userPk, final UserUpsertRequest request) {
        return userCrudPort.findByUserPk(userPk)
                .map(existing -> updateExisting(userPk, request, existing));
    }

    public void delete(final long userPk) {
        userCrudPort.deleteByUserPk(userPk);
    }

    public UserGroupMappingResponse addGroupMapping(final long userPk, final long groupId) {
        // 편입은 그룹의 permCode 전부를 대상 사용자에게 주는 것과 같다 — 부여와 같은 규칙을 적용한다(S08 E-2).
        escalationGuard.requireAssignableGroup(groupId);
        final UiUserGroupMember saved = userGroupMappingPort.upsert(
                assembler.toUserGroupMemberCommand(userPk, groupId, DEFAULT_GRANTED_BY)
        );
        return assembler.toUserGroupMappingResponse(saved);
    }

    public void removeGroupMapping(final long userPk, final long groupId) {
        userGroupMappingPort.deleteByUserPkAndGroupId(userPk, groupId);
    }

    public Optional<PagedResponse<UserGroupMembershipResponse>> listGroupsByUserPk(
            final long userPk,
            final UiPageRequest pageRequest
    ) {
        if (userCrudPort.findByUserPk(userPk).isEmpty()) {
            return Optional.empty();
        }
        final PagedResponse<UiUserGroupMember> mappings = userGroupMappingPort.findAllByUserPk(userPk, pageRequest);
        final Map<Long, UiUserGroup> groupsByGroupId = mappings.items().stream()
                .map(UiUserGroupMember::groupId)
                .distinct()
                .map(groupCrudPort::findByGroupId)
                .flatMap(Optional::stream)
                .collect(Collectors.toMap(UiUserGroup::groupId, Function.identity()));
        return Optional.of(assembler.toUserGroupMembershipPage(mappings, groupsByGroupId));
    }

    public boolean resetPassword(final long userPk, final UserPasswordResetRequest request) {
        final Optional<UiManagedUser> optionalExisting = userCrudPort.findByUserPk(userPk);
        if (optionalExisting.isEmpty()) {
            return false;
        }
        // 재설정은 대상 계정 탈취와 같다 — 대상보다 좁은 권한으로 admin 계정을 뺏을 수 없게 한다(S08 E-2).
        escalationGuard.requireResettablePassword(userPk);

        final String passwordHash = passwordPolicyService.encodeChangedPassword(userPk, request.newPassword());
        userCrudPort.upsert(assembler.toPasswordResetCommand(request, optionalExisting.get(), passwordHash));
        return true;
    }

    private UserInfoResponse updateExisting(
            final long userPk,
            final UserUpsertRequest request,
            final UiManagedUser existing
    ) {
        final boolean passwordChanged = assembler.hasPassword(request.password());
        final String passwordHash = passwordChanged
                ? passwordPolicyService.encodeChangedPassword(userPk, request.password())
                : existing.passwordHash();
        return assembler.toUserInfoResponse(
                userCrudPort.upsert(assembler.toUpdateCommand(userPk, request, existing, passwordHash, passwordChanged))
        );
    }
}

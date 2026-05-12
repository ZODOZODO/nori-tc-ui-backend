package com.nori.tc.ui.adapters.web.application.admin;

import com.nori.tc.ui.adapters.web.dto.request.UserPasswordResetRequest;
import com.nori.tc.ui.adapters.web.dto.request.UserUpsertRequest;
import com.nori.tc.ui.adapters.web.dto.response.UserGroupMappingResponse;
import com.nori.tc.ui.adapters.web.dto.response.UserGroupMembershipResponse;
import com.nori.tc.ui.adapters.web.dto.response.UserInfoResponse;
import com.nori.tc.ui.adapters.web.security.UiPasswordPolicyService;
import com.nori.tc.ui.core.model.PagedResponse;
import com.nori.tc.ui.core.model.UiPageRequest;
import com.nori.tc.ui.core.port.db.GroupCrudPort;
import com.nori.tc.ui.core.port.db.UserCrudPort;
import com.nori.tc.ui.core.port.db.UserGroupMappingPort;
import com.nori.tc.ui.domain.useradmin.UiManagedUser;
import com.nori.tc.ui.domain.useradmin.UiUserGroup;
import com.nori.tc.ui.domain.useradmin.UiUserGroupMember;
import org.springframework.stereotype.Component;

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

    public UserAdminFacade(
            final UserCrudPort userCrudPort,
            final UserGroupMappingPort userGroupMappingPort,
            final GroupCrudPort groupCrudPort,
            final UiPasswordPolicyService passwordPolicyService,
            final UserAdminAssembler assembler
    ) {
        this.userCrudPort = Objects.requireNonNull(userCrudPort, "userCrudPort is null");
        this.userGroupMappingPort = Objects.requireNonNull(userGroupMappingPort, "userGroupMappingPort is null");
        this.groupCrudPort = Objects.requireNonNull(groupCrudPort, "groupCrudPort is null");
        this.passwordPolicyService = Objects.requireNonNull(passwordPolicyService, "passwordPolicyService is null");
        this.assembler = Objects.requireNonNull(assembler, "assembler is null");
    }

    public PagedResponse<UserInfoResponse> list(final UiPageRequest pageRequest) {
        return assembler.toUserPage(userCrudPort.findAll(pageRequest));
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

        final String passwordHash = passwordPolicyService.encodeNewPassword(request.newPassword());
        userCrudPort.upsert(assembler.toPasswordResetCommand(request, optionalExisting.get(), passwordHash));
        return true;
    }

    private UserInfoResponse updateExisting(
            final long userPk,
            final UserUpsertRequest request,
            final UiManagedUser existing
    ) {
        final String passwordHash = assembler.hasPassword(request.password())
                ? passwordPolicyService.encodeNewPassword(request.password())
                : existing.passwordHash();
        return assembler.toUserInfoResponse(
                userCrudPort.upsert(assembler.toUpdateCommand(userPk, request, existing, passwordHash))
        );
    }
}

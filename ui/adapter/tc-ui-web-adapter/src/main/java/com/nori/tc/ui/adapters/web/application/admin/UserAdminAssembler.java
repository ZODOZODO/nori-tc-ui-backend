package com.nori.tc.ui.adapters.web.application.admin;

import com.nori.tc.ui.adapters.web.dto.request.UserPasswordResetRequest;
import com.nori.tc.ui.adapters.web.dto.request.UserUpsertRequest;
import com.nori.tc.ui.adapters.web.dto.response.UserGroupMappingResponse;
import com.nori.tc.ui.adapters.web.dto.response.UserGroupMembershipResponse;
import com.nori.tc.ui.adapters.web.dto.response.UserInfoResponse;
import com.nori.tc.ui.core.exception.UiBadRequestException;
import com.nori.tc.ui.core.model.PagedResponse;
import com.nori.tc.ui.core.model.UiUserGroupMemberUpsertCommand;
import com.nori.tc.ui.core.model.UiUserUpsertCommand;
import com.nori.tc.ui.domain.auth.UiUserStatus;
import com.nori.tc.ui.domain.useradmin.UiManagedUser;
import com.nori.tc.ui.domain.useradmin.UiUserGroup;
import com.nori.tc.ui.domain.useradmin.UiUserGroupMember;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * User 관리 요청/응답 변환과 입력 정규화를 담당합니다.
 */
@Component
public class UserAdminAssembler {

    public String requireCreatePassword(final String rawPassword) {
        if (!hasText(rawPassword)) {
            throw new UiBadRequestException("사용자 생성 시 password는 필수입니다.");
        }
        return rawPassword.trim();
    }

    public boolean hasPassword(final String rawPassword) {
        return hasText(rawPassword);
    }

    public UiUserUpsertCommand toCreateCommand(
            final UserUpsertRequest request,
            final String passwordHash
    ) {
        return toUpsertCommand(null, request, passwordHash, resolveCreateStatus(request.status()));
    }

    public UiUserUpsertCommand toUpdateCommand(
            final long userPk,
            final UserUpsertRequest request,
            final UiManagedUser existing,
            final String passwordHash
    ) {
        final UiUserStatus status = request.status() == null
                ? UiUserStatus.valueOf(existing.status().name())
                : request.status();
        return toUpsertCommand(userPk, request, passwordHash, status);
    }

    public UiUserUpsertCommand toPasswordResetCommand(
            final UserPasswordResetRequest request,
            final UiManagedUser existing,
            final String passwordHash
    ) {
        return new UiUserUpsertCommand(
                existing.userPk(),
                existing.company(),
                existing.department(),
                existing.userName(),
                existing.userId(),
                existing.userIdNorm(),
                passwordHash,
                existing.email(),
                UiUserStatus.valueOf(existing.status().name()),
                existing.createdBy(),
                request.updatedBy()
        );
    }

    public UiUserGroupMemberUpsertCommand toUserGroupMemberCommand(
            final long userPk,
            final long groupId,
            final String grantedBy
    ) {
        return new UiUserGroupMemberUpsertCommand(
                null,
                userPk,
                groupId,
                null,
                grantedBy
        );
    }

    public PagedResponse<UserInfoResponse> toUserPage(final PagedResponse<UiManagedUser> page) {
        final List<UserInfoResponse> items = page.items().stream()
                .map(this::toUserInfoResponse)
                .toList();
        return PagedResponse.of(items, page.offset(), page.limit(), page.count());
    }

    public UserInfoResponse toUserInfoResponse(final UiManagedUser user) {
        return new UserInfoResponse(
                user.userPk(),
                user.company(),
                user.department(),
                user.userName(),
                user.userId(),
                user.userIdNorm(),
                user.email(),
                user.status(),
                user.createdAt(),
                user.updatedAt(),
                user.createdBy(),
                user.updatedBy()
        );
    }

    public UserGroupMappingResponse toUserGroupMappingResponse(final UiUserGroupMember mapping) {
        return new UserGroupMappingResponse(
                mapping.ugmKey(),
                mapping.userPk(),
                mapping.groupId(),
                mapping.grantedAt(),
                mapping.grantedBy()
        );
    }

    public PagedResponse<UserGroupMembershipResponse> toUserGroupMembershipPage(
            final PagedResponse<UiUserGroupMember> page,
            final Map<Long, UiUserGroup> groupsByGroupId
    ) {
        final List<UserGroupMembershipResponse> items = page.items().stream()
                .map(mapping -> toUserGroupMembershipResponse(mapping, groupsByGroupId.get(mapping.groupId())))
                .toList();
        return PagedResponse.of(items, page.offset(), page.limit(), page.count());
    }

    public UserGroupMembershipResponse toUserGroupMembershipResponse(
            final UiUserGroupMember mapping,
            final UiUserGroup group
    ) {
        return new UserGroupMembershipResponse(
                mapping.userPk(),
                mapping.groupId(),
                group == null ? null : group.groupCode(),
                group == null ? null : group.groupName()
        );
    }

    private UiUserUpsertCommand toUpsertCommand(
            final Long userPk,
            final UserUpsertRequest request,
            final String passwordHash,
            final UiUserStatus status
    ) {
        final String normalizedUserId = normalizeUserId(request.userId());
        return new UiUserUpsertCommand(
                userPk,
                request.company(),
                request.department(),
                request.userName(),
                normalizedUserId,
                normalizedUserId.toLowerCase(Locale.ROOT),
                passwordHash,
                request.email(),
                status,
                request.createdBy(),
                request.updatedBy()
        );
    }

    private static UiUserStatus resolveCreateStatus(final UiUserStatus status) {
        return status == null ? UiUserStatus.ACTIVE : status;
    }

    private static String normalizeUserId(final String userId) {
        if (!hasText(userId)) {
            throw new UiBadRequestException("userId는 비어 있을 수 없습니다.");
        }
        return userId.trim();
    }

    private static boolean hasText(final String text) {
        return text != null && !text.trim().isEmpty();
    }
}

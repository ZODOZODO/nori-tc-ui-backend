package com.nori.tc.ui.adapter.db.mapper;

import com.nori.tc.db.domain.user.TcUiPermission;
import com.nori.tc.db.domain.user.TcUserGroup;
import com.nori.tc.db.domain.user.TcUserGroupMember;
import com.nori.tc.db.domain.user.TcUserGroupPermission;
import com.nori.tc.db.domain.user.TcUserInfo;
import com.nori.tc.ui.domain.useradmin.UiManagedUser;
import com.nori.tc.ui.domain.useradmin.UiPermission;
import com.nori.tc.ui.domain.useradmin.UiUserGroup;
import com.nori.tc.ui.domain.useradmin.UiUserGroupMember;
import com.nori.tc.ui.domain.useradmin.UiUserGroupPermission;

/**
 * DB 사용자/권한 관리 레코드와 UI 내부 모델 간 ACL 매퍼입니다.
 */
public final class UiUserAdminDbMapper {

    private UiUserAdminDbMapper() {
    }

    public static UiManagedUser toDomain(final TcUserInfo source) {
        if (source == null) {
            return null;
        }
        return new UiManagedUser(
                source.userPk(),
                source.company(),
                source.department(),
                source.userName(),
                source.userId(),
                source.userIdNorm(),
                source.passwordHash(),
                source.email(),
                UiDomainEnumMapper.toUiUserStatus(source.status()),
                source.createdAt(),
                source.updatedAt(),
                source.createdBy(),
                source.updatedBy()
        );
    }

    public static UiUserGroup toDomain(final TcUserGroup source) {
        if (source == null) {
            return null;
        }
        return new UiUserGroup(
                source.groupId(),
                source.groupCode(),
                source.groupName(),
                source.description(),
                source.isActive(),
                source.createdAt(),
                source.updatedAt()
        );
    }

    public static UiUserGroupMember toDomain(final TcUserGroupMember source) {
        if (source == null) {
            return null;
        }
        return new UiUserGroupMember(
                source.ugmKey(),
                source.userPk(),
                source.groupId(),
                source.grantedAt(),
                source.grantedBy()
        );
    }

    public static UiUserGroupPermission toDomain(final TcUserGroupPermission source) {
        if (source == null) {
            return null;
        }
        return new UiUserGroupPermission(
                source.ugpKey(),
                source.groupId(),
                source.permId(),
                source.grantedAt(),
                source.grantedBy()
        );
    }

    public static UiPermission toDomain(final TcUiPermission source) {
        if (source == null) {
            return null;
        }
        return new UiPermission(
                source.permId(),
                source.permCode(),
                source.permName(),
                UiDomainEnumMapper.toUiPermissionResourceType(source.resourceType()),
                UiDomainEnumMapper.toUiPermissionMatchType(source.matchType()),
                source.resource(),
                source.httpMethod(),
                source.description(),
                source.isActive(),
                source.createdAt(),
                source.updatedAt(),
                source.createdBy(),
                source.updatedBy()
        );
    }
}

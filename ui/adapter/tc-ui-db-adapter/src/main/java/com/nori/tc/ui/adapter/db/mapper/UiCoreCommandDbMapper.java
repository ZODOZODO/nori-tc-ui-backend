package com.nori.tc.ui.adapter.db.mapper;

import com.nori.tc.db.core.common.PageRequest;
import com.nori.tc.db.core.model.upsert.UpsertTcModel;
import com.nori.tc.db.core.user.upsert.UpsertTcUiPermission;
import com.nori.tc.db.core.user.upsert.UpsertTcUserGroup;
import com.nori.tc.db.core.user.upsert.UpsertTcUserGroupMember;
import com.nori.tc.db.core.user.upsert.UpsertTcUserGroupPermission;
import com.nori.tc.db.core.user.upsert.UpsertTcUserInfo;
import com.nori.tc.ui.core.model.UiModelUpsertCommand;
import com.nori.tc.ui.core.model.UiPageRequest;
import com.nori.tc.ui.core.model.UiPermissionUpsertCommand;
import com.nori.tc.ui.core.model.UiUserGroupMemberUpsertCommand;
import com.nori.tc.ui.core.model.UiUserGroupPermissionUpsertCommand;
import com.nori.tc.ui.core.model.UiUserGroupUpsertCommand;
import com.nori.tc.ui.core.model.UiUserUpsertCommand;

/**
 * UI Core 의미 모델을 DB 저장소 계약으로 변환하는 ACL 매퍼입니다.
 */
public final class UiCoreCommandDbMapper {

    private UiCoreCommandDbMapper() {
    }

    public static PageRequest toDbPageRequest(final UiPageRequest source) {
        if (source == null) {
            return PageRequest.defaultPage();
        }
        return PageRequest.of(source.offset(), source.limit());
    }

    public static UpsertTcModel toDbCommand(final UiModelUpsertCommand source) {
        if (source == null) {
            return null;
        }
        return new UpsertTcModel(
                source.modelVersionKey(),
                source.modelName(),
                source.parentModel(),
                source.modelVersion(),
                UiDomainEnumMapper.toDbProtocolType(source.commInterface()),
                UiDomainEnumMapper.toDbModelStatus(source.status()),
                source.description(),
                source.maker(),
                source.createdBy(),
                source.updatedBy()
        );
    }

    public static UpsertTcUserInfo toDbCommand(final UiUserUpsertCommand source) {
        if (source == null) {
            return null;
        }
        return new UpsertTcUserInfo(
                source.userPk(),
                source.company(),
                source.department(),
                source.userName(),
                source.userId(),
                source.userIdNorm(),
                source.passwordHash(),
                source.email(),
                UiDomainEnumMapper.toDbUserStatus(source.status()),
                source.createdBy(),
                source.updatedBy()
        );
    }

    public static UpsertTcUserGroup toDbCommand(final UiUserGroupUpsertCommand source) {
        if (source == null) {
            return null;
        }
        return new UpsertTcUserGroup(
                source.groupId(),
                source.groupCode(),
                source.groupName(),
                source.description(),
                source.isActive()
        );
    }

    public static UpsertTcUiPermission toDbCommand(final UiPermissionUpsertCommand source) {
        if (source == null) {
            return null;
        }
        return new UpsertTcUiPermission(
                source.permId(),
                source.permCode(),
                source.permName(),
                UiDomainEnumMapper.toDbPermissionResourceType(source.resourceType()),
                UiDomainEnumMapper.toDbPermissionMatchType(source.matchType()),
                source.resource(),
                source.httpMethod(),
                source.description(),
                source.isActive(),
                source.createdBy(),
                source.updatedBy()
        );
    }

    public static UpsertTcUserGroupMember toDbCommand(final UiUserGroupMemberUpsertCommand source) {
        if (source == null) {
            return null;
        }
        return new UpsertTcUserGroupMember(
                source.ugmKey(),
                source.userPk(),
                source.groupId(),
                source.grantedAt(),
                source.grantedBy()
        );
    }

    public static UpsertTcUserGroupPermission toDbCommand(final UiUserGroupPermissionUpsertCommand source) {
        if (source == null) {
            return null;
        }
        return new UpsertTcUserGroupPermission(
                source.groupId(),
                source.permId(),
                source.grantedAt(),
                source.grantedBy()
        );
    }
}

package com.nori.tc.ui.adapter.db.mapper;

import com.nori.tc.db.domain.user.TcUserInfo;
import com.nori.tc.ui.domain.auth.UiUserAccount;
import com.nori.tc.ui.domain.auth.UiUserStatus;

/**
 * DB 사용자 레코드와 UI 내부 사용자 모델 간 ACL 매퍼입니다.
 */
public final class UiUserAccountDbMapper {

    private UiUserAccountDbMapper() {
    }

    public static UiUserAccount toDomain(final TcUserInfo source) {
        if (source == null) {
            return null;
        }
        return new UiUserAccount(
                source.userPk(),
                source.userId(),
                source.userIdNorm(),
                source.passwordHash(),
                UiUserStatus.valueOf(source.status().name())
        );
    }
}

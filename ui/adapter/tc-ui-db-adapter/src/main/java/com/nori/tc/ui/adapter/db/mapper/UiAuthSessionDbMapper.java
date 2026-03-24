package com.nori.tc.ui.adapter.db.mapper;

import com.nori.tc.db.core.user.upsert.UpsertTcUiAuthSession;
import com.nori.tc.db.domain.user.TcUiAuthSession;
import com.nori.tc.ui.domain.auth.UiAuthSessionCreateCommand;
import com.nori.tc.ui.domain.auth.UiAuthSessionSnapshot;
import com.nori.tc.ui.domain.auth.UiAuthTokenFingerprint;

/**
 * 인증 세션 저장소 ACL 매퍼입니다.
 *
 * <p>코어의 raw token을 저장소 경계에서 fingerprint로 치환합니다.</p>
 */
public final class UiAuthSessionDbMapper {

    private UiAuthSessionDbMapper() {
    }

    public static UpsertTcUiAuthSession toStoreCommand(final UiAuthSessionCreateCommand source) {
        if (source == null) {
            return null;
        }
        return new UpsertTcUiAuthSession(
                UiAuthTokenFingerprint.sha256Hex(source.token()),
                source.userPk(),
                source.issuedAt(),
                source.expiresAt(),
                source.lastSeenAt(),
                source.revoked()
        );
    }

    public static UiAuthSessionSnapshot toSnapshot(final TcUiAuthSession source) {
        if (source == null) {
            return null;
        }
        return new UiAuthSessionSnapshot(
                source.userPk(),
                source.issuedAt(),
                source.expiresAt(),
                source.lastSeenAt(),
                source.revoked()
        );
    }
}

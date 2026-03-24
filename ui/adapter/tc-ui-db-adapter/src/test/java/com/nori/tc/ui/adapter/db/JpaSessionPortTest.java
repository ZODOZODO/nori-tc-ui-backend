package com.nori.tc.ui.adapter.db;

import com.nori.tc.db.core.common.PageRequest;
import com.nori.tc.db.core.user.store.TcUiAuthSessionStore;
import com.nori.tc.db.core.user.upsert.UpsertTcUiAuthSession;
import com.nori.tc.db.domain.user.TcUiAuthSession;
import com.nori.tc.ui.domain.auth.UiAuthSessionCreateCommand;
import com.nori.tc.ui.domain.auth.UiAuthTokenFingerprint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JpaSessionPortTest {

    @Test
    @DisplayName("세션 저장과 조회는 raw token 대신 fingerprint를 저장소 키로 사용한다")
    void 세션_저장_조회_fingerprint_사용() {
        final RecordingSessionStore sessionStore = new RecordingSessionStore();
        final JpaSessionPort port = new JpaSessionPort(sessionStore);
        final String rawToken = "RAW-TOKEN-001";
        final OffsetDateTime issuedAt = OffsetDateTime.now().minusMinutes(1);
        final OffsetDateTime expiresAt = OffsetDateTime.now().plusHours(1);

        port.save(new UiAuthSessionCreateCommand(rawToken, 11L, issuedAt, expiresAt, null, false));

        assertEquals(UiAuthTokenFingerprint.sha256Hex(rawToken), sessionStore.lastUpsertCommand.token());
        assertNotEquals(rawToken, sessionStore.lastUpsertCommand.token());

        final Optional<com.nori.tc.ui.domain.auth.UiAuthSessionSnapshot> session = port.findValidByToken(rawToken);
        assertTrue(session.isPresent());
        assertEquals(11L, session.get().userPk());
        assertEquals(UiAuthTokenFingerprint.sha256Hex(rawToken), sessionStore.lastLookupToken);
    }

    @Test
    @DisplayName("세션 revoke와 lastSeen 업데이트도 fingerprint를 사용한다")
    void 세션_revoke_lastSeen_fingerprint_사용() {
        final RecordingSessionStore sessionStore = new RecordingSessionStore();
        final JpaSessionPort port = new JpaSessionPort(sessionStore);
        final String rawToken = "RAW-TOKEN-002";

        port.revoke(rawToken);
        assertEquals(UiAuthTokenFingerprint.sha256Hex(rawToken), sessionStore.lastRevokeToken);

        final OffsetDateTime lastSeenAt = OffsetDateTime.now();
        port.updateLastSeenAt(rawToken, lastSeenAt);
        assertEquals(UiAuthTokenFingerprint.sha256Hex(rawToken), sessionStore.lastUpdateLastSeenToken);
        assertEquals(lastSeenAt, sessionStore.lastSeenAt);
    }

    private static final class RecordingSessionStore implements TcUiAuthSessionStore {
        private UpsertTcUiAuthSession lastUpsertCommand;
        private String lastLookupToken;
        private String lastRevokeToken;
        private String lastUpdateLastSeenToken;
        private OffsetDateTime lastSeenAt;

        @Override
        public TcUiAuthSession upsert(final UpsertTcUiAuthSession command) {
            this.lastUpsertCommand = command;
            return new TcUiAuthSession(
                    command.token(),
                    command.userPk(),
                    command.issuedAt(),
                    command.expiresAt(),
                    command.lastSeenAt(),
                    command.revoked()
            );
        }

        @Override
        public Optional<TcUiAuthSession> findByToken(final String token) {
            return Optional.empty();
        }

        @Override
        public List<TcUiAuthSession> findAllByUserPk(final long userPk, final PageRequest page) {
            return List.of();
        }

        @Override
        public void deleteByToken(final String token) {
        }

        @Override
        public Optional<TcUiAuthSession> findValidByToken(final String token) {
            this.lastLookupToken = token;
            return Optional.of(new TcUiAuthSession(
                    token,
                    11L,
                    OffsetDateTime.now().minusMinutes(1),
                    OffsetDateTime.now().plusHours(1),
                    null,
                    false
            ));
        }

        @Override
        public void revokeByToken(final String token) {
            this.lastRevokeToken = token;
        }

        @Override
        public void updateLastSeenAt(final String token, final OffsetDateTime lastSeenAt) {
            this.lastUpdateLastSeenToken = token;
            this.lastSeenAt = lastSeenAt;
        }
    }
}

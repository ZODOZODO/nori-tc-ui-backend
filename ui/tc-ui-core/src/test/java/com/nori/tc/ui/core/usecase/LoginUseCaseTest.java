package com.nori.tc.ui.core.usecase;

import com.nori.tc.ui.core.auth.UiAuthMetrics;
import com.nori.tc.ui.core.exception.UiAuthenticationException;
import com.nori.tc.ui.core.port.db.AuditLogPort;
import com.nori.tc.ui.core.port.db.PasswordVerifierPort;
import com.nori.tc.ui.core.port.db.SessionPort;
import com.nori.tc.ui.core.port.db.UserPort;
import com.nori.tc.ui.core.properties.UiAuthProperties;
import com.nori.tc.ui.domain.audit.UiAuditAction;
import com.nori.tc.ui.domain.audit.UiAuditEvent;
import com.nori.tc.ui.domain.audit.UiAuditOutcome;
import com.nori.tc.ui.domain.auth.AuthToken;
import com.nori.tc.ui.domain.auth.UiAuthSessionCreateCommand;
import com.nori.tc.ui.domain.auth.UiAuthSessionSnapshot;
import com.nori.tc.ui.domain.auth.UiUserAccount;
import com.nori.tc.ui.domain.auth.UiUserStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LoginUseCase} 단위 테스트입니다.
 *
 * <p>검증 범위:</p>
 * <ul>
 *   <li>정상 로그인 시 토큰 발급 + 세션 저장</li>
 *   <li>사용자 미존재/비밀번호 불일치 오류 메시지 동일성(사용자 열거 방지)</li>
 *   <li>비활성 계정 차단</li>
 * </ul>
 */
class LoginUseCaseTest {

    /**
     * 정상 로그인 시 AuthToken 발급과 세션 저장을 확인합니다.
     */
    @Test
    @DisplayName("정상 로그인 시 AuthToken이 발급되고 세션이 저장된다")
    void 정상_로그인_토큰발급_세션저장() {
        final FakeUserPort userPort = new FakeUserPort();
        final CapturingSessionPort sessionPort = new CapturingSessionPort();
        final StubPasswordVerifier passwordVerifier = new StubPasswordVerifier(true);

        final RecordingAuditLogPort auditLog = new RecordingAuditLogPort();

        final UiUserAccount activeUser = activeUser("testuser", UiUserStatus.ACTIVE);
        userPort.userByUserIdNorm = Optional.of(activeUser);

        final LoginUseCase useCase = new LoginUseCase(
                userPort,
                sessionPort,
                passwordVerifier,
                createAuthProperties(),
                UiAuthMetrics.noop(),
                auditLog
        );

        final AuthToken token = useCase.execute(" TestUser ", "password-raw");

        assertNotNull(token);
        assertEquals(64, token.token().length(), "토큰은 64자여야 합니다.");
        assertEquals(activeUser.userPk(), token.userPk());
        assertTrue(token.expiresAt().isAfter(token.issuedAt()));

        final UiAuthSessionCreateCommand savedSession = sessionPort.lastSavedSession.get();
        assertNotNull(savedSession, "로그인 성공 시 세션 저장이 수행되어야 합니다.");
        assertEquals(token.token(), savedSession.token());
        assertEquals(activeUser.userPk(), savedSession.userPk());
        assertTrue(passwordVerifier.lastRawPassword.equals("password-raw"));
        assertTrue(passwordVerifier.lastEncodedPassword.equals(activeUser.passwordHash()));

        // W1-6: 로그인 성공 시 감사 레코드(actor·작업·결과)가 생성되어야 한다.
        assertEquals(1, auditLog.events.size(), "로그인 성공 시 감사 이벤트 1건 기록");
        final UiAuditEvent recorded = auditLog.events.get(0);
        assertEquals(UiAuditAction.LOGIN, recorded.action());
        assertEquals(UiAuditOutcome.SUCCESS, recorded.outcome());
        assertEquals("testuser", recorded.actor());
        assertNotNull(recorded.occurredAt(), "감사 이벤트에 시각이 기록되어야 한다.");
    }

    /**
     * 사용자 미존재와 비밀번호 불일치가 동일 오류 메시지를 반환하는지 확인합니다.
     */
    @Test
    @DisplayName("사용자 미존재와 비밀번호 불일치는 동일 오류 메시지를 반환한다")
    void 사용자미존재_비밀번호불일치_동일메시지() {
        final RecordingAuditLogPort missingUserAuditLog = new RecordingAuditLogPort();
        final LoginUseCase missingUserUseCase = new LoginUseCase(
                new FakeUserPort(),
                new CapturingSessionPort(),
                new StubPasswordVerifier(true),
                createAuthProperties(),
                UiAuthMetrics.noop(),
                missingUserAuditLog
        );
        final UiAuthenticationException missingUserEx = assertThrows(
                UiAuthenticationException.class,
                () -> missingUserUseCase.execute("unknown", "pw")
        );

        final FakeUserPort existingUserPort = new FakeUserPort();
        existingUserPort.userByUserIdNorm = Optional.of(activeUser("user-01", UiUserStatus.ACTIVE));
        final RecordingAuditLogPort mismatchAuditLog = new RecordingAuditLogPort();
        final LoginUseCase passwordMismatchUseCase = new LoginUseCase(
                existingUserPort,
                new CapturingSessionPort(),
                new StubPasswordVerifier(false),
                createAuthProperties(),
                UiAuthMetrics.noop(),
                mismatchAuditLog
        );
        final UiAuthenticationException passwordMismatchEx = assertThrows(
                UiAuthenticationException.class,
                () -> passwordMismatchUseCase.execute("user-01", "wrong-password")
        );

        assertEquals(missingUserEx.getMessage(), passwordMismatchEx.getMessage());

        // W1-6: 두 실패 경로 모두 LOGIN/FAILURE 감사 레코드를 남긴다.
        assertEquals(1, missingUserAuditLog.events.size());
        assertEquals(UiAuditAction.LOGIN, missingUserAuditLog.events.get(0).action());
        assertEquals(UiAuditOutcome.FAILURE, missingUserAuditLog.events.get(0).outcome());
        assertEquals(1, mismatchAuditLog.events.size());
        assertEquals(UiAuditOutcome.FAILURE, mismatchAuditLog.events.get(0).outcome());
        assertEquals("user-01", mismatchAuditLog.events.get(0).actor());
    }

    /**
     * 비활성 계정은 비밀번호가 맞아도 로그인할 수 없어야 합니다.
     */
    @Test
    @DisplayName("비활성 계정은 로그인이 차단된다")
    void 비활성_계정_로그인_차단() {
        final FakeUserPort userPort = new FakeUserPort();
        userPort.userByUserIdNorm = Optional.of(activeUser("disabled-user", UiUserStatus.DISABLED));

        final RecordingAuditLogPort auditLog = new RecordingAuditLogPort();
        final LoginUseCase useCase = new LoginUseCase(
                userPort,
                new CapturingSessionPort(),
                new StubPasswordVerifier(true),
                createAuthProperties(),
                UiAuthMetrics.noop(),
                auditLog
        );

        final UiAuthenticationException exception = assertThrows(
                UiAuthenticationException.class,
                () -> useCase.execute("disabled-user", "password")
        );
        assertEquals("사용 불가능한 계정입니다.", exception.getMessage());

        // W1-6: 비활성 계정 차단도 LOGIN/FAILURE 감사 레코드를 남긴다.
        assertEquals(1, auditLog.events.size());
        assertEquals(UiAuditAction.LOGIN, auditLog.events.get(0).action());
        assertEquals(UiAuditOutcome.FAILURE, auditLog.events.get(0).outcome());
        assertEquals("inactive_account", auditLog.events.get(0).detail());
    }

    /**
     * 비밀번호 최종 변경 후 90일이 지나면 로그인은 성공하되 만료 플래그가 set 된다.
     */
    @Test
    @DisplayName("비밀번호 변경 후 90일 초과 시 로그인은 성공하고 passwordExpired=true")
    void 비밀번호_만료_플래그_set() {
        final FakeUserPort userPort = new FakeUserPort();
        userPort.userByUserIdNorm = Optional.of(
                userWithPasswordChangedAt("expired-user", OffsetDateTime.now().minusDays(91)));

        final LoginUseCase useCase = new LoginUseCase(
                userPort,
                new CapturingSessionPort(),
                new StubPasswordVerifier(true),
                createAuthProperties(),
                UiAuthMetrics.noop(),
                new RecordingAuditLogPort()
        );

        final AuthToken token = useCase.execute("expired-user", "password-raw");

        assertTrue(token.passwordExpired(), "변경 후 90일 초과 시 passwordExpired=true 여야 한다.");
    }

    /**
     * 비밀번호 최종 변경 후 90일 이내면 만료 플래그가 unset 이다.
     */
    @Test
    @DisplayName("비밀번호 변경 후 90일 이내면 passwordExpired=false")
    void 비밀번호_미만료_플래그_unset() {
        final FakeUserPort userPort = new FakeUserPort();
        userPort.userByUserIdNorm = Optional.of(
                userWithPasswordChangedAt("fresh-user", OffsetDateTime.now().minusDays(89)));

        final LoginUseCase useCase = new LoginUseCase(
                userPort,
                new CapturingSessionPort(),
                new StubPasswordVerifier(true),
                createAuthProperties(),
                UiAuthMetrics.noop(),
                new RecordingAuditLogPort()
        );

        final AuthToken token = useCase.execute("fresh-user", "password-raw");

        assertFalse(token.passwordExpired(), "변경 후 90일 이내면 passwordExpired=false 여야 한다.");
    }

    /**
     * 테스트용 활성/비활성 사용자 도메인 객체를 생성합니다.
     *
     * @param userIdNorm 정규화 사용자 ID
     * @param status 사용자 상태
     * @return 테스트용 TcUserInfo
     */
    private static UiUserAccount activeUser(final String userIdNorm, final UiUserStatus status) {
        // 비밀번호 변경 시각을 최근(now)으로 두어 기본 케이스는 만료되지 않도록 한다.
        return new UiUserAccount(20L, userIdNorm, userIdNorm, "$2a$10$hash", status, OffsetDateTime.now());
    }

    private static UiUserAccount userWithPasswordChangedAt(
            final String userIdNorm,
            final OffsetDateTime passwordChangedAt
    ) {
        return new UiUserAccount(20L, userIdNorm, userIdNorm, "$2a$10$hash", UiUserStatus.ACTIVE, passwordChangedAt);
    }

    /**
     * LoginUseCase 테스트에서 공통으로 사용하는 인증 설정 객체를 생성합니다.
     *
     * <p>본 테스트는 로그인 도메인 로직(sessionTtlHours/tokenCacheTtlSeconds) 검증이 목적이므로,
     * Cookie/CSRF/CORS 관련 신규 필드는 설계 기본값과 동일한 값으로 채워 생성합니다.</p>
     *
     * @return 테스트 전용 UiAuthProperties 인스턴스
     */
    private static UiAuthProperties createAuthProperties() {
        return new UiAuthProperties(
                8,
                300,
                30,
                1000,
                "TC_UI_AUTH",
                "/",
                null,
                true,
                "None",
                "XSRF-TOKEN",
                "X-XSRF-TOKEN",
                List.of("http://localhost:3000")
        );
    }

    /**
     * UserPort 테스트 더블입니다.
     */
    private static final class FakeUserPort implements UserPort {
        private Optional<UiUserAccount> userByUserIdNorm = Optional.empty();

        @Override
        public Optional<UiUserAccount> findByUserIdNorm(final String userIdNorm) {
            return userByUserIdNorm;
        }

        @Override
        public Optional<UiUserAccount> findByUserPk(final long userPk) {
            return Optional.empty();
        }
    }

    /**
     * SessionPort 저장 호출을 관찰하는 테스트 더블입니다.
     */
    private static final class CapturingSessionPort implements SessionPort {
        private final AtomicReference<UiAuthSessionCreateCommand> lastSavedSession = new AtomicReference<>();

        @Override
        public void save(final UiAuthSessionCreateCommand session) {
            lastSavedSession.set(session);
        }

        @Override
        public Optional<UiAuthSessionSnapshot> findValidByToken(final String token) {
            return Optional.empty();
        }

        @Override
        public void revoke(final String token) {
            // not used
        }

        @Override
        public void updateLastSeenAt(final String token, final OffsetDateTime lastSeenAt) {
            // not used
        }
    }

    /**
     * PasswordVerifierPort 결과를 고정 반환하는 테스트 더블입니다.
     */
    private static final class StubPasswordVerifier implements PasswordVerifierPort {
        private final boolean matchResult;
        private String lastRawPassword = "";
        private String lastEncodedPassword = "";

        private StubPasswordVerifier(final boolean matchResult) {
            this.matchResult = matchResult;
        }

        @Override
        public boolean matches(final String rawPassword, final String encodedPassword) {
            this.lastRawPassword = rawPassword;
            this.lastEncodedPassword = encodedPassword;
            return matchResult;
        }
    }

    /**
     * 기록된 감사 이벤트를 그대로 수집하는 AuditLogPort 테스트 더블입니다.
     */
    private static final class RecordingAuditLogPort implements AuditLogPort {
        private final List<UiAuditEvent> events = new ArrayList<>();

        @Override
        public void record(final UiAuditEvent event) {
            events.add(event);
        }
    }
}

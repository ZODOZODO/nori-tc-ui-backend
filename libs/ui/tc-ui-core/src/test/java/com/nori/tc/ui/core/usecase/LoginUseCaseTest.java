package com.nori.tc.ui.core.usecase;

import com.nori.tc.db.domain.common.user.UserStatus;
import com.nori.tc.db.domain.user.TcUiAuthSession;
import com.nori.tc.db.domain.user.TcUserInfo;
import com.nori.tc.ui.core.exception.UiAuthenticationException;
import com.nori.tc.ui.core.port.db.PasswordVerifierPort;
import com.nori.tc.ui.core.port.db.SessionPort;
import com.nori.tc.ui.core.port.db.UserPort;
import com.nori.tc.ui.core.properties.UiAuthProperties;
import com.nori.tc.ui.domain.auth.AuthToken;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

        final TcUserInfo activeUser = new TcUserInfo(
                10L,
                "NORI",
                "DEV",
                "테스트",
                "testuser",
                "testuser",
                "$2a$10$hash",
                "test@nori.com",
                UserStatus.ACTIVE,
                OffsetDateTime.now(),
                OffsetDateTime.now(),
                "SYSTEM",
                "SYSTEM"
        );
        userPort.userByUserIdNorm = Optional.of(activeUser);

        final LoginUseCase useCase = new LoginUseCase(
                userPort,
                sessionPort,
                passwordVerifier,
                createAuthProperties()
        );

        final AuthToken token = useCase.execute(" TestUser ", "password-raw");

        assertNotNull(token);
        assertEquals(64, token.token().length(), "토큰은 64자여야 합니다.");
        assertEquals(activeUser.userPk(), token.userPk());
        assertTrue(token.expiresAt().isAfter(token.issuedAt()));

        final TcUiAuthSession savedSession = sessionPort.lastSavedSession.get();
        assertNotNull(savedSession, "로그인 성공 시 세션 저장이 수행되어야 합니다.");
        assertEquals(token.token(), savedSession.token());
        assertEquals(activeUser.userPk(), savedSession.userPk());
        assertTrue(passwordVerifier.lastRawPassword.equals("password-raw"));
        assertTrue(passwordVerifier.lastEncodedPassword.equals(activeUser.passwordHash()));
    }

    /**
     * 사용자 미존재와 비밀번호 불일치가 동일 오류 메시지를 반환하는지 확인합니다.
     */
    @Test
    @DisplayName("사용자 미존재와 비밀번호 불일치는 동일 오류 메시지를 반환한다")
    void 사용자미존재_비밀번호불일치_동일메시지() {
        final LoginUseCase missingUserUseCase = new LoginUseCase(
                new FakeUserPort(),
                new CapturingSessionPort(),
                new StubPasswordVerifier(true),
                createAuthProperties()
        );
        final UiAuthenticationException missingUserEx = assertThrows(
                UiAuthenticationException.class,
                () -> missingUserUseCase.execute("unknown", "pw")
        );

        final FakeUserPort existingUserPort = new FakeUserPort();
        existingUserPort.userByUserIdNorm = Optional.of(activeUser("user-01", UserStatus.ACTIVE));
        final LoginUseCase passwordMismatchUseCase = new LoginUseCase(
                existingUserPort,
                new CapturingSessionPort(),
                new StubPasswordVerifier(false),
                createAuthProperties()
        );
        final UiAuthenticationException passwordMismatchEx = assertThrows(
                UiAuthenticationException.class,
                () -> passwordMismatchUseCase.execute("user-01", "wrong-password")
        );

        assertEquals(missingUserEx.getMessage(), passwordMismatchEx.getMessage());
    }

    /**
     * 비활성 계정은 비밀번호가 맞아도 로그인할 수 없어야 합니다.
     */
    @Test
    @DisplayName("비활성 계정은 로그인이 차단된다")
    void 비활성_계정_로그인_차단() {
        final FakeUserPort userPort = new FakeUserPort();
        userPort.userByUserIdNorm = Optional.of(activeUser("disabled-user", UserStatus.DISABLED));

        final LoginUseCase useCase = new LoginUseCase(
                userPort,
                new CapturingSessionPort(),
                new StubPasswordVerifier(true),
                createAuthProperties()
        );

        final UiAuthenticationException exception = assertThrows(
                UiAuthenticationException.class,
                () -> useCase.execute("disabled-user", "password")
        );
        assertEquals("사용 불가능한 계정입니다.", exception.getMessage());
    }

    /**
     * 테스트용 활성/비활성 사용자 도메인 객체를 생성합니다.
     *
     * @param userIdNorm 정규화 사용자 ID
     * @param status 사용자 상태
     * @return 테스트용 TcUserInfo
     */
    private static TcUserInfo activeUser(final String userIdNorm, final UserStatus status) {
        return new TcUserInfo(
                20L,
                "NORI",
                "DEV",
                "테스트",
                userIdNorm,
                userIdNorm,
                "$2a$10$hash",
                userIdNorm + "@nori.com",
                status,
                OffsetDateTime.now(),
                OffsetDateTime.now(),
                "SYSTEM",
                "SYSTEM"
        );
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
        private Optional<TcUserInfo> userByUserIdNorm = Optional.empty();

        @Override
        public Optional<TcUserInfo> findByUserIdNorm(final String userIdNorm) {
            return userByUserIdNorm;
        }

        @Override
        public Optional<TcUserInfo> findByUserPk(final long userPk) {
            return Optional.empty();
        }
    }

    /**
     * SessionPort 저장 호출을 관찰하는 테스트 더블입니다.
     */
    private static final class CapturingSessionPort implements SessionPort {
        private final AtomicReference<TcUiAuthSession> lastSavedSession = new AtomicReference<>();

        @Override
        public void save(final TcUiAuthSession session) {
            lastSavedSession.set(session);
        }

        @Override
        public Optional<TcUiAuthSession> findValidByToken(final String token) {
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
}

package com.nori.tc.ui.core.usecase;

import com.nori.tc.db.domain.common.user.UserStatus;
import com.nori.tc.db.domain.user.TcUiAuthSession;
import com.nori.tc.db.domain.user.TcUserInfo;
import com.nori.tc.ui.core.exception.UiAuthenticationException;
import com.nori.tc.ui.core.port.db.PermissionPort;
import com.nori.tc.ui.core.port.db.SessionPort;
import com.nori.tc.ui.core.port.db.UserPort;
import com.nori.tc.ui.core.port.redis.TokenCachePort;
import com.nori.tc.ui.domain.auth.UserPrincipal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ValidateTokenUseCase} 단위 테스트입니다.
 *
 * <p>검증 범위:</p>
 * <ul>
 *   <li>캐시 히트 시 DB 조회 최소화(재검증 주기 내 무조회)</li>
 *   <li>캐시 미스 시 DB 조회 후 캐시 저장</li>
 *   <li>만료/폐기 토큰 거부</li>
 *   <li>lastSeenAt 업데이트 실패 격리</li>
 * </ul>
 */
class ValidateTokenUseCaseTest {

    private static final String TEST_TOKEN = "TOKEN-VALIDATE-001";
    private static final long TEST_USER_PK = 101L;

    /**
     * 캐시 히트 경로에서 재검증 주기 내 DB 재조회가 발생하지 않는지 검증합니다.
     */
    @Test
    @DisplayName("재검증 주기 내 캐시 히트는 DB 재조회 없이 처리된다")
    void 캐시히트_DB_재조회_없음() {
        final FakeSessionPort sessionPort = new FakeSessionPort();
        final FakeUserPort userPort = new FakeUserPort();
        final FakePermissionPort permissionPort = new FakePermissionPort();
        final FakeTokenCachePort cachePort = new FakeTokenCachePort();

        sessionPort.validSessionByToken.put(TEST_TOKEN, validSession(TEST_TOKEN));
        userPort.userByPk.put(TEST_USER_PK, activeUser());
        permissionPort.permissionsByUserPk.put(TEST_USER_PK, Set.of("EQP_MANAGE"));

        final ValidateTokenUseCase useCase = new ValidateTokenUseCase(
                sessionPort, userPort, permissionPort, cachePort
        );

        // 1차 호출: 캐시 미스 -> DB 조회 후 캐시 적재 (nextRevokeCheck 설정됨)
        final UserPrincipal first = useCase.execute(TEST_TOKEN);
        assertNotNull(first);
        final int dbLookupAfterFirst = sessionPort.findValidByTokenCallCount.get();

        // 2차 호출: 동일 토큰 캐시 히트 + 재검증 주기 내 -> DB 재조회 없음
        final UserPrincipal second = useCase.execute(TEST_TOKEN);
        assertEquals(first.userPk(), second.userPk());
        assertEquals(dbLookupAfterFirst, sessionPort.findValidByTokenCallCount.get());
    }

    /**
     * 캐시 미스 시 DB에서 세션/사용자/권한을 조회하고 캐시에 저장해야 합니다.
     */
    @Test
    @DisplayName("캐시 미스 시 DB 조회 후 캐시에 저장된다")
    void 캐시미스_DB조회_캐시저장() {
        final FakeSessionPort sessionPort = new FakeSessionPort();
        final FakeUserPort userPort = new FakeUserPort();
        final FakePermissionPort permissionPort = new FakePermissionPort();
        final FakeTokenCachePort cachePort = new FakeTokenCachePort();

        sessionPort.validSessionByToken.put(TEST_TOKEN, validSession(TEST_TOKEN));
        userPort.userByPk.put(TEST_USER_PK, activeUser());
        permissionPort.permissionsByUserPk.put(TEST_USER_PK, Set.of("AUTH_ME_PERM", "EQP_MANAGE"));

        final ValidateTokenUseCase useCase = new ValidateTokenUseCase(
                sessionPort, userPort, permissionPort, cachePort
        );

        final UserPrincipal principal = useCase.execute(TEST_TOKEN);

        assertEquals(TEST_USER_PK, principal.userPk());
        assertTrue(principal.permissionCodes().contains("AUTH_ME_PERM"));
        assertEquals(1, cachePort.putCallCount.get());
        assertTrue(cachePort.cacheByToken.containsKey(TEST_TOKEN));
    }

    /**
     * DB 유효 세션이 없으면 만료/폐기/미존재 토큰으로 판단해 예외를 던져야 합니다.
     */
    @Test
    @DisplayName("유효 세션이 없으면 인증 예외를 발생시킨다")
    void 만료또는폐기_세션_예외() {
        final ValidateTokenUseCase useCase = new ValidateTokenUseCase(
                new FakeSessionPort(),
                new FakeUserPort(),
                new FakePermissionPort(),
                new FakeTokenCachePort()
        );

        final UiAuthenticationException exception = assertThrows(
                UiAuthenticationException.class,
                () -> useCase.execute(TEST_TOKEN)
        );
        assertEquals("유효하지 않은 세션 토큰입니다.", exception.getMessage());
    }

    /**
     * lastSeenAt 업데이트 실패가 인증 실패로 전파되지 않아야 합니다.
     */
    @Test
    @DisplayName("lastSeenAt 업데이트 실패가 발생해도 인증은 성공한다")
    void lastSeenAt_실패_인증성공() {
        final FakeSessionPort sessionPort = new FakeSessionPort();
        final FakeUserPort userPort = new FakeUserPort();
        final FakePermissionPort permissionPort = new FakePermissionPort();
        final FakeTokenCachePort cachePort = new FakeTokenCachePort();

        sessionPort.validSessionByToken.put(TEST_TOKEN, validSession(TEST_TOKEN));
        sessionPort.throwOnUpdateLastSeenAt = true;
        userPort.userByPk.put(TEST_USER_PK, activeUser());
        permissionPort.permissionsByUserPk.put(TEST_USER_PK, Set.of("EQP_MANAGE"));

        final ValidateTokenUseCase useCase = new ValidateTokenUseCase(
                sessionPort, userPort, permissionPort, cachePort
        );

        final UserPrincipal principal = useCase.execute(TEST_TOKEN);
        assertEquals(TEST_USER_PK, principal.userPk());
    }

    /**
     * 테스트용 활성 사용자 객체를 생성합니다.
     *
     * @return ACTIVE 상태 사용자
     */
    private static TcUserInfo activeUser() {
        return new TcUserInfo(
                TEST_USER_PK,
                "NORI",
                "DEV",
                "테스트",
                "tester",
                "tester",
                "$2a$10$hash",
                "tester@nori.com",
                UserStatus.ACTIVE,
                OffsetDateTime.now(),
                OffsetDateTime.now(),
                "SYSTEM",
                "SYSTEM"
        );
    }

    /**
     * 테스트용 유효 세션을 생성합니다.
     *
     * @param token 세션 토큰
     * @return revoked=false, expiresAt 미래 시각 세션
     */
    private static TcUiAuthSession validSession(final String token) {
        return new TcUiAuthSession(
                token,
                TEST_USER_PK,
                OffsetDateTime.now().minusMinutes(1),
                OffsetDateTime.now().plusHours(8),
                null,
                false
        );
    }

    /**
     * SessionPort 테스트 더블입니다.
     */
    private static final class FakeSessionPort implements SessionPort {
        private final Map<String, TcUiAuthSession> validSessionByToken = new ConcurrentHashMap<>();
        private final AtomicInteger findValidByTokenCallCount = new AtomicInteger();
        private volatile boolean throwOnUpdateLastSeenAt = false;

        @Override
        public void save(final TcUiAuthSession session) {
            // not used
        }

        @Override
        public Optional<TcUiAuthSession> findValidByToken(final String token) {
            findValidByTokenCallCount.incrementAndGet();
            return Optional.ofNullable(validSessionByToken.get(token));
        }

        @Override
        public void revoke(final String token) {
            // not used
        }

        @Override
        public void updateLastSeenAt(final String token, final OffsetDateTime lastSeenAt) {
            if (throwOnUpdateLastSeenAt) {
                throw new RuntimeException("lastSeenAt update failed");
            }
        }
    }

    /**
     * UserPort 테스트 더블입니다.
     */
    private static final class FakeUserPort implements UserPort {
        private final Map<Long, TcUserInfo> userByPk = new ConcurrentHashMap<>();

        @Override
        public Optional<TcUserInfo> findByUserIdNorm(final String userIdNorm) {
            return Optional.empty();
        }

        @Override
        public Optional<TcUserInfo> findByUserPk(final long userPk) {
            return Optional.ofNullable(userByPk.get(userPk));
        }
    }

    /**
     * PermissionPort 테스트 더블입니다.
     */
    private static final class FakePermissionPort implements PermissionPort {
        private final Map<Long, Set<String>> permissionsByUserPk = new ConcurrentHashMap<>();

        @Override
        public Set<String> findPermissionCodesByUserPk(final long userPk) {
            return permissionsByUserPk.getOrDefault(userPk, Set.of());
        }
    }

    /**
     * TokenCachePort 테스트 더블입니다.
     */
    private static final class FakeTokenCachePort implements TokenCachePort {
        private final Map<String, UserPrincipal> cacheByToken = new ConcurrentHashMap<>();
        private final AtomicInteger putCallCount = new AtomicInteger();

        @Override
        public Optional<UserPrincipal> get(final String token) {
            return Optional.ofNullable(cacheByToken.get(token));
        }

        @Override
        public void put(final String token, final UserPrincipal principal) {
            cacheByToken.put(token, principal);
            putCallCount.incrementAndGet();
        }

        @Override
        public void evict(final String token) {
            cacheByToken.remove(token);
        }
    }
}

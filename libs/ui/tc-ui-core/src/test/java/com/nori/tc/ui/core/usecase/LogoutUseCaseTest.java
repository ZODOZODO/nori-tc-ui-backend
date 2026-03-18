package com.nori.tc.ui.core.usecase;

import com.nori.tc.db.domain.user.TcUiAuthSession;
import com.nori.tc.ui.core.port.db.SessionPort;
import com.nori.tc.ui.core.port.redis.TokenCachePort;
import com.nori.tc.ui.domain.auth.UserPrincipal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link LogoutUseCase} 단위 테스트입니다.
 *
 * <p>검증 범위:</p>
 * <ul>
 *   <li>정상 로그아웃 시 DB revoke + 캐시 evict 호출</li>
 *   <li>Redis evict 실패 시 예외 비전파(로그아웃 정상 종료)</li>
 * </ul>
 */
class LogoutUseCaseTest {

    private static final String TEST_TOKEN = "TOKEN-LOGOUT-001";

    /**
     * 정상 로그아웃 시 DB revoke와 캐시 evict가 모두 호출되어야 합니다.
     */
    @Test
    @DisplayName("정상 로그아웃 시 DB revoke와 캐시 evict가 모두 호출된다")
    void 정상_로그아웃_DBRevoke_캐시Evict() {
        final CountingSessionPort sessionPort = new CountingSessionPort();
        final StubTokenCachePort tokenCachePort = new StubTokenCachePort(false);
        final LogoutUseCase useCase = new LogoutUseCase(sessionPort, tokenCachePort);

        useCase.execute(TEST_TOKEN);

        assertEquals(1, sessionPort.revokeCallCount.get());
        assertEquals(1, tokenCachePort.evictCallCount.get());
    }

    /**
     * Redis evict 실패가 발생해도 로그아웃 전체 예외는 전파되면 안 됩니다.
     */
    @Test
    @DisplayName("Redis evict 실패 시에도 로그아웃은 예외 없이 종료된다")
    void redis_evict_실패_예외비전파() {
        final CountingSessionPort sessionPort = new CountingSessionPort();
        final StubTokenCachePort tokenCachePort = new StubTokenCachePort(true);
        final LogoutUseCase useCase = new LogoutUseCase(sessionPort, tokenCachePort);

        assertDoesNotThrow(() -> useCase.execute(TEST_TOKEN));
        assertEquals(1, sessionPort.revokeCallCount.get());
        assertEquals(1, tokenCachePort.evictCallCount.get());
    }

    /**
     * SessionPort 호출 횟수를 기록하는 테스트 더블입니다.
     */
    private static final class CountingSessionPort implements SessionPort {
        private final AtomicInteger revokeCallCount = new AtomicInteger();

        @Override
        public void save(final TcUiAuthSession session) {
            // not used
        }

        @Override
        public Optional<TcUiAuthSession> findValidByToken(final String token) {
            return Optional.empty();
        }

        @Override
        public void revoke(final String token) {
            revokeCallCount.incrementAndGet();
        }

        @Override
        public void updateLastSeenAt(final String token, final OffsetDateTime lastSeenAt) {
            // not used
        }
    }

    /**
     * TokenCachePort 테스트 더블입니다.
     */
    private static final class StubTokenCachePort implements TokenCachePort {
        private final boolean throwOnEvict;
        private final AtomicInteger evictCallCount = new AtomicInteger();

        private StubTokenCachePort(final boolean throwOnEvict) {
            this.throwOnEvict = throwOnEvict;
        }

        @Override
        public Optional<UserPrincipal> get(final String token) {
            return Optional.empty();
        }

        @Override
        public void put(final String token, final UserPrincipal principal) {
            // not used
        }

        @Override
        public void evict(final String token) {
            evictCallCount.incrementAndGet();
            if (throwOnEvict) {
                throw new RuntimeException("Redis evict failed");
            }
        }
    }
}

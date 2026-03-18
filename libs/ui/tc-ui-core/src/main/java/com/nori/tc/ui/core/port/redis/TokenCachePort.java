package com.nori.tc.ui.core.port.redis;

import com.nori.tc.ui.domain.auth.UserPrincipal;

import java.util.Optional;

/**
 * 인증 토큰 캐시 포트입니다.
 *
 * <p>역할:</p>
 * <p>Business Redis(6380)에 토큰 → UserPrincipal 정보를 캐싱하여
 * 매 요청마다 DB 조회 없이 인증 처리를 빠르게 수행합니다.</p>
 *
 * <p>캐시 키 형식:</p>
 * <pre>tc:ui:backend:session:{token}</pre>
 *
 * <p>TTL:</p>
 * <p>{@code tc.ui.backend.auth.token-cache-ttl-seconds} 설정값을 따릅니다 (기본 300초).</p>
 *
 * <p>구현체:</p>
 * <p>tc-ui-redis-adapter의 UiSessionCacheService가 이 인터페이스를 구현합니다.</p>
 */
public interface TokenCachePort {

    /**
     * 캐시에서 토큰에 해당하는 UserPrincipal을 조회합니다.
     *
     * @param token 조회할 세션 토큰
     * @return 캐시에 존재하면 UserPrincipal, 없거나 TTL 만료 시 빈 Optional
     */
    Optional<UserPrincipal> get(String token);

    /**
     * 토큰과 UserPrincipal을 캐시에 저장합니다.
     *
     * <p>ValidateTokenUseCase에서 DB 조회 후 결과를 캐싱하는 데 사용합니다.
     * TTL은 구현체가 설정에서 읽어 적용합니다.</p>
     *
     * @param token     캐시 키로 사용할 세션 토큰
     * @param principal 캐싱할 사용자 인증 정보
     */
    void put(String token, UserPrincipal principal);

    /**
     * 캐시에서 해당 토큰의 항목을 즉시 삭제합니다.
     *
     * <p>LogoutUseCase에서 로그아웃 시 캐시를 무효화하는 데 사용합니다.</p>
     *
     * @param token 삭제할 세션 토큰
     */
    void evict(String token);

    /**
     * 테스트 또는 초기 구성에 사용할 noop(아무 동작 없음) 구현체를 반환합니다.
     *
     * @return 항상 빈 Optional을 반환하고 저장/삭제 동작을 무시하는 noop 구현체
     */
    static TokenCachePort noop() {
        return new TokenCachePort() {
            @Override
            public Optional<UserPrincipal> get(final String token) {
                return Optional.empty();
            }

            @Override
            public void put(final String token, final UserPrincipal principal) {
                // noop: 아무 동작 없음
            }

            @Override
            public void evict(final String token) {
                // noop: 아무 동작 없음
            }
        };
    }
}

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 세션 토큰 유효성 검증 유스케이스입니다.
 *
 * <p>처리 흐름:</p>
 * <ol>
 *   <li>Business Redis 캐시(TokenCachePort)에서 UserPrincipal 조회 → 캐시 히트 시 즉시 반환</li>
 *   <li>캐시 미스: tc_ui_auth_session에서 유효 세션 조회 (token, revoked=false, expiresAt > now)</li>
 *   <li>세션이 없으면 인증 실패 (만료/폐기/미존재)</li>
 *   <li>tc_user_info에서 계정 상태 확인 (ACTIVE 여부)</li>
 *   <li>3-JOIN 쿼리로 활성 권한 코드 목록 로드</li>
 *   <li>UserPrincipal 구성 → Redis 캐시 저장</li>
 *   <li>lastSeenAt 업데이트 (최근 활동 시각 기록)</li>
 * </ol>
 *
 * <p>성능:</p>
 * <p>Redis 캐시 히트 시 DB 조회 없이 반환하므로 매 요청의 인증 오버헤드를 최소화합니다.
 * 캐시 미스 시에만 DB 3-JOIN 조회가 발생합니다.</p>
 */
@Component
public class ValidateTokenUseCase {

    private static final Logger log = LoggerFactory.getLogger(ValidateTokenUseCase.class);
    /**
     * 캐시 히트 토큰에 대해 revoked 재검증을 수행하는 최소 주기(ms)입니다.
     *
     * <p>매 요청마다 DB를 조회하면 캐시 이점이 사라지므로, 토큰별로 일정 주기마다만
     * DB 유효성(revoked/만료)를 재검증합니다.</p>
     */
    private static final long CACHE_HIT_REVOKE_RECHECK_INTERVAL_MS = 30_000L;

    private final SessionPort sessionPort;
    private final UserPort userPort;
    private final PermissionPort permissionPort;
    private final TokenCachePort tokenCachePort;
    private final Map<String, Long> nextRevokeCheckEpochMsByToken = new ConcurrentHashMap<>();

    public ValidateTokenUseCase(
            final SessionPort sessionPort,
            final UserPort userPort,
            final PermissionPort permissionPort,
            final TokenCachePort tokenCachePort
    ) {
        this.sessionPort = sessionPort;
        this.userPort = userPort;
        this.permissionPort = permissionPort;
        this.tokenCachePort = tokenCachePort;
    }

    /**
     * 세션 토큰을 검증하고 인증된 사용자 정보를 반환합니다.
     *
     * <p>UiTokenAuthenticationFilter(tc-ui-web-adapter)가 매 HTTP 요청마다 호출합니다.</p>
     *
     * @param token 검증할 세션 토큰 (인증 쿠키에서 추출한 값)
     * @return 인증된 사용자 정보 (userPk, userId, 권한 코드 집합)
     * @throws UiAuthenticationException 토큰이 유효하지 않거나 계정이 비활성인 경우
     */
    public UserPrincipal execute(final String token) {
        // 1단계: Redis 캐시 조회 - 히트 시 DB 접근 없이 즉시 반환
        final var cached = tokenCachePort.get(token);
        if (cached.isPresent()) {
            verifyRevocationOnCacheHit(token, cached.get().userPk());
            log.trace("토큰 캐시 히트. userPk={}", cached.get().userPk());
            return cached.get();
        }

        log.debug("토큰 캐시 미스 - DB 조회 시작. token={}...", token.substring(0, Math.min(8, token.length())));

        // 2단계: DB에서 유효 세션 조회 (revoked=false, expiresAt > now 조건 포함)
        final TcUiAuthSession session = sessionPort.findValidByToken(token)
                .orElseThrow(() -> {
                    log.warn("유효하지 않은 토큰 - 세션 없음/만료/폐기. token={}...",
                            token.substring(0, Math.min(8, token.length())));
                    return new UiAuthenticationException("유효하지 않은 세션 토큰입니다.");
                });

        // 3단계: 사용자 계정 상태 확인
        final long userPk = session.userPk();
        final TcUserInfo userInfo = userPort.findByUserPk(userPk)
                .orElseThrow(() -> {
                    // 세션은 있으나 사용자가 없는 비정상 상태 (데이터 정합성 문제)
                    log.error("세션은 유효하나 사용자를 찾을 수 없음. userPk={}", userPk);
                    return new UiAuthenticationException("사용자 정보를 찾을 수 없습니다.");
                });

        if (userInfo.status() != UserStatus.ACTIVE) {
            log.warn("토큰 검증 실패 - 비활성 계정. userPk={}, status={}", userPk, userInfo.status());
            throw new UiAuthenticationException("사용 불가능한 계정입니다.");
        }

        // 4단계: 활성 권한 코드 목록 로드 (tc_user_group_member → tc_user_group_permission → tc_ui_permission 3-JOIN)
        final Set<String> permissionCodes = permissionPort.findPermissionCodesByUserPk(userPk);
        log.debug("권한 로드 완료. userPk={}, 권한 수={}", userPk, permissionCodes.size());

        // 5단계: UserPrincipal 구성
        final UserPrincipal principal = new UserPrincipal(userPk, userInfo.userId(), permissionCodes);

        // 6단계: Redis 캐시 저장 (TTL은 UiSessionCacheService 설정에서 적용)
        tokenCachePort.put(token, principal);
        nextRevokeCheckEpochMsByToken.put(token, System.currentTimeMillis() + CACHE_HIT_REVOKE_RECHECK_INTERVAL_MS);
        log.debug("토큰 캐시 저장 완료. userPk={}", userPk);

        // 7단계: 최근 활동 시각 비동기 업데이트
        // - 인증 성공 경로의 응답 지연을 최소화하기 위해 별도 스레드에서 수행합니다.
        // - 업데이트 실패는 인증 실패로 전파하지 않고 WARN 로그로만 관측합니다.
        updateLastSeenAtAsync(token, userPk);

        log.debug("토큰 검증 성공. userPk={}, userId={}", userPk, userInfo.userId());
        return principal;
    }

    /**
     * 캐시 히트 세션에 대해 주기적 DB 유효성 검증(revoked/만료)을 수행합니다.
     *
     * <p>로그아웃 직후 Redis evict가 실패했을 때, 캐시 TTL 동안 revoked 토큰이 통과하는 문제를
     * 줄이기 위해 최소 주기로 DB 유효 세션 여부를 다시 확인합니다.</p>
     *
     * @param token 캐시 히트 토큰
     * @param userPk 로그용 사용자 PK
     */
    private void verifyRevocationOnCacheHit(final String token, final long userPk) {
        final long now = System.currentTimeMillis();
        final long nextCheckAt = nextRevokeCheckEpochMsByToken.getOrDefault(token, 0L);
        if (now < nextCheckAt) {
            return;
        }

        // findValidByToken은 revoked=false + expiresAt > now 조건을 포함하므로
        // 결과가 없으면 "폐기 또는 만료 또는 미존재"로 판단할 수 있습니다.
        if (sessionPort.findValidByToken(token).isEmpty()) {
            nextRevokeCheckEpochMsByToken.remove(token);
            tokenCachePort.evict(token);
            log.warn("캐시 히트 토큰이 DB에서 무효로 확인되어 캐시 제거. userPk={}", userPk);
            throw new UiAuthenticationException("유효하지 않은 세션 토큰입니다.");
        }

        nextRevokeCheckEpochMsByToken.put(token, now + CACHE_HIT_REVOKE_RECHECK_INTERVAL_MS);
        if (log.isDebugEnabled()) {
            log.debug("캐시 히트 세션 DB 유효성 검증 완료. userPk={}, nextCheckInMs={}",
                    userPk, CACHE_HIT_REVOKE_RECHECK_INTERVAL_MS);
        }
    }

    /**
     * lastSeenAt 업데이트를 비동기로 수행합니다.
     *
     * <p>Redis 캐시 미스 경로에서 DB 조회가 이미 수행되므로, 최근 접속 시각 업데이트까지
     * 동기 처리하면 인증 응답 지연이 누적될 수 있습니다. 본 메서드는 업데이트를 백그라운드로
     * 분리하여 인증 성공 응답과 분리합니다.</p>
     *
     * <p>중요: lastSeenAt 업데이트 실패는 관측(로그) 대상이며 인증 실패 사유가 아닙니다.
     * 따라서 예외를 상위로 전파하지 않습니다.</p>
     *
     * @param token 세션 토큰
     * @param userPk 로그 보강용 사용자 PK
     */
    private void updateLastSeenAtAsync(final String token, final long userPk) {
        CompletableFuture.runAsync(() -> {
            try {
                sessionPort.updateLastSeenAt(token, OffsetDateTime.now());
                if (log.isTraceEnabled()) {
                    log.trace("lastSeenAt 비동기 업데이트 완료. userPk={}", userPk);
                }
            } catch (Exception e) {
                log.warn("lastSeenAt 비동기 업데이트 실패 - 인증은 유지됩니다. userPk={}", userPk, e);
            }
        });
    }
}

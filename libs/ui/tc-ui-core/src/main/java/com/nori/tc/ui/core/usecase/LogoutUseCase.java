package com.nori.tc.ui.core.usecase;

import com.nori.tc.ui.core.port.db.SessionPort;
import com.nori.tc.ui.core.port.redis.TokenCachePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 로그아웃(인증 세션 폐기) 유스케이스입니다.
 *
 * <p>처리 흐름:</p>
 * <ol>
 *   <li>tc_ui_auth_session.revoked = true 업데이트 → DB 세션 폐기</li>
 *   <li>Business Redis 캐시에서 해당 토큰 항목 삭제 → 즉시 캐시 무효화</li>
 * </ol>
 *
 * <p>보장:</p>
 * <p>DB 폐기 후 Redis 캐시도 즉시 삭제하므로, 로그아웃 후 동일 토큰으로
 * 인증을 시도하면 DB에서도 캐시에서도 유효한 세션을 찾을 수 없습니다.
 * 두 작업 중 하나가 실패해도 서비스가 중단되지 않도록 예외를 개별 처리합니다.</p>
 */
@Component
public class LogoutUseCase {

    private static final Logger log = LoggerFactory.getLogger(LogoutUseCase.class);

    private final SessionPort sessionPort;
    private final TokenCachePort tokenCachePort;

    public LogoutUseCase(
            final SessionPort sessionPort,
            final TokenCachePort tokenCachePort
    ) {
        this.sessionPort = sessionPort;
        this.tokenCachePort = tokenCachePort;
    }

    /**
     * 세션 토큰을 폐기하여 로그아웃 처리합니다.
     *
     * <p>이미 폐기된 토큰이나 존재하지 않는 토큰에 대한 호출도 안전하게 처리됩니다
     * (멱등성 보장: DB 업데이트 0건 = 정상 종료).</p>
     *
     * @param token 폐기할 세션 토큰 (인증 쿠키에서 추출된 원본 값)
     */
    public void execute(final String token) {
        final String maskedToken = maskToken(token);
        log.debug("로그아웃 처리 시작. token={}...", maskedToken);

        // DB 세션 폐기: revoked = true 업데이트
        // 이미 폐기된 세션이어도 UPDATE 0건으로 정상 종료됩니다.
        sessionPort.revoke(token);
        log.debug("DB 세션 폐기 완료.");

        // Redis 캐시 즉시 삭제: 캐시 TTL이 남아있어도 즉시 무효화합니다.
        // 단, Redis 장애가 발생해도 DB revoke는 이미 완료되었으므로 로그아웃 전체를 실패 처리하지 않습니다.
        try {
            tokenCachePort.evict(token);
            log.debug("Redis 토큰 캐시 삭제 완료.");
        } catch (Exception e) {
            log.error(
                    "Redis 토큰 캐시 제거 실패 - DB revoke는 완료됨. token={}..., "
                            + "캐시 TTL 만료 전까지 인증이 일시적으로 통과할 수 있습니다.",
                    maskedToken,
                    e
            );
        }

        // 토큰 앞 8자리만 로깅합니다(보안: 전체 토큰 노출 방지).
        log.info("로그아웃 완료. token={}...", maskedToken);
    }

    /**
     * 로그 출력용 토큰 마스킹 값을 생성합니다.
     *
     * @param token 원본 토큰
     * @return 앞 8자리(또는 가능한 길이) 마스킹 문자열
     */
    private static String maskToken(final String token) {
        if (token == null || token.isBlank()) {
            return "N/A";
        }
        return token.substring(0, Math.min(8, token.length()));
    }
}

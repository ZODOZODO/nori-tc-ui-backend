package com.nori.tc.ui.domain.auth;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * 발급된 UI 인증 세션 토큰 정보입니다.
 *
 * <p>역할:</p>
 * <p>로그인 성공 시 발급되며, 이후 모든 API 요청의 인증 수단으로 사용됩니다.
 * tc_ui_auth_session 테이블의 한 행과 1:1 대응합니다.</p>
 *
 * <p>생명주기:</p>
 * <ol>
 *   <li>LoginUseCase에서 SecureRandom 기반 토큰 생성 → 이 객체를 인증 필터에 전달</li>
 *   <li>매 요청마다 UiTokenAuthenticationFilter가 token으로 조회하여 유효성 확인</li>
 *   <li>LogoutUseCase에서 revoke 처리 → 이후 동일 token으로 인증 불가</li>
 * </ol>
 *
 * @param token     세션 토큰 문자열 (SecureRandom 64자 alphanumeric, PK)
 * @param userPk    토큰 소유 사용자의 PK (tc_user_info.user_pk)
 * @param issuedAt  토큰 발급 시각
 * @param expiresAt 토큰 만료 시각 (issuedAt + tc.ui.backend.auth.session-ttl-hours)
 */
public record AuthToken(
        String token,
        long userPk,
        OffsetDateTime issuedAt,
        OffsetDateTime expiresAt
) {

    /**
     * 생성 시 필수 필드를 검증하고, 시간 순서의 유효성을 확인합니다.
     */
    public AuthToken {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("token은 필수입니다.");
        }
        if (userPk <= 0L) {
            throw new IllegalArgumentException("userPk는 양수여야 합니다. userPk=" + userPk);
        }
        Objects.requireNonNull(issuedAt, "issuedAt은 null일 수 없습니다.");
        Objects.requireNonNull(expiresAt, "expiresAt은 null일 수 없습니다.");
        if (!expiresAt.isAfter(issuedAt)) {
            // 토큰 발급 즉시 만료되는 경우를 방지합니다.
            throw new IllegalArgumentException(
                    "expiresAt은 issuedAt 이후여야 합니다. issuedAt=" + issuedAt + ", expiresAt=" + expiresAt
            );
        }
    }

    /**
     * 현재 시각 기준으로 토큰이 만료되었는지 확인합니다.
     *
     * <p>인증 필터에서 DB 조회 없이 만료 여부를 빠르게 판단할 때 사용합니다.
     * DB의 expiresAt과 시스템 클럭의 편차가 있을 수 있으므로,
     * 중요한 판단 시에는 DB 조건(expiresAt > now)을 함께 사용하십시오.</p>
     *
     * @return 만료되었으면 true
     */
    public boolean isExpired() {
        return OffsetDateTime.now().isAfter(expiresAt);
    }

    /**
     * 토큰이 아직 유효한지 확인합니다.
     *
     * @return 유효하면 true
     */
    public boolean isValid() {
        return !isExpired();
    }
}

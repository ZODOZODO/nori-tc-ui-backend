package com.nori.tc.ui.domain.auth;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * 인증 세션 저장 명령입니다.
 *
 * <p>코어는 raw token만 알고, 실제 저장소에서는 fingerprint로 변환해 저장합니다.</p>
 *
 * @param token 세션 토큰 원문
 * @param userPk 사용자 PK
 * @param issuedAt 발급 시각
 * @param expiresAt 만료 시각
 * @param lastSeenAt 마지막 접근 시각
 * @param revoked 폐기 여부
 */
public record UiAuthSessionCreateCommand(
        String token,
        long userPk,
        OffsetDateTime issuedAt,
        OffsetDateTime expiresAt,
        OffsetDateTime lastSeenAt,
        boolean revoked
) {

    public UiAuthSessionCreateCommand {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("token은 필수입니다.");
        }
        if (userPk <= 0L) {
            throw new IllegalArgumentException("userPk는 양수여야 합니다. userPk=" + userPk);
        }
        Objects.requireNonNull(issuedAt, "issuedAt은 null일 수 없습니다.");
        Objects.requireNonNull(expiresAt, "expiresAt은 null일 수 없습니다.");
        if (!expiresAt.isAfter(issuedAt)) {
            throw new IllegalArgumentException("expiresAt은 issuedAt 이후여야 합니다.");
        }
    }
}

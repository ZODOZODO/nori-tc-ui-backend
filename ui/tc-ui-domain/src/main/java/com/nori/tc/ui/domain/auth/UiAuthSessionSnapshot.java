package com.nori.tc.ui.domain.auth;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * 인증 세션 조회 결과입니다.
 *
 * <p>토큰 원문은 노출하지 않고, 인증 코어가 필요한 세션 상태만 전달합니다.</p>
 *
 * @param userPk 사용자 PK
 * @param issuedAt 발급 시각
 * @param expiresAt 만료 시각
 * @param lastSeenAt 마지막 접근 시각
 * @param revoked 폐기 여부
 */
public record UiAuthSessionSnapshot(
        long userPk,
        OffsetDateTime issuedAt,
        OffsetDateTime expiresAt,
        OffsetDateTime lastSeenAt,
        boolean revoked
) {

    public UiAuthSessionSnapshot {
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

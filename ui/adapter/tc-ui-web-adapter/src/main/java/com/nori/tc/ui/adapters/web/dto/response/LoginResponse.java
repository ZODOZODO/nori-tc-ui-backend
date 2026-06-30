package com.nori.tc.ui.adapters.web.dto.response;

import java.time.OffsetDateTime;

/**
 * POST /api/auth/login 성공 응답 DTO입니다.
 *
 * <p>보안 정책상 실제 인증 토큰은 응답 본문이 아닌 HttpOnly 쿠키로만 전달합니다.
 * 본 DTO는 UI 화면에서 로그인 상태를 표시할 때 필요한 메타데이터만 포함합니다.</p>
 *
 * @param userPk          로그인한 사용자 PK
 * @param issuedAt        토큰 발급 시각
 * @param expiresAt       토큰 만료 시각
 * @param passwordExpired 비밀번호 만료(변경 권고) 여부. 로그인은 성공하되 UI 가 변경을 안내하는 용도(하드 차단 아님).
 */
public record LoginResponse(
        long userPk,
        OffsetDateTime issuedAt,
        OffsetDateTime expiresAt,
        boolean passwordExpired
) {
}

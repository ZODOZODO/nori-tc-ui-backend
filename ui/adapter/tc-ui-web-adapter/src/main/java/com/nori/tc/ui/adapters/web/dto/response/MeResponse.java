package com.nori.tc.ui.adapters.web.dto.response;

import java.util.Set;

/**
 * GET /api/auth/me 응답 DTO입니다.
 *
 * <p>현재 인증된 사용자의 식별 정보와 보유 권한 코드 목록을 반환합니다.
 * 클라이언트에서 메뉴 노출 여부나 버튼 활성화 판단에 활용합니다.</p>
 *
 * @param userPk          사용자 PK
 * @param userId          사용자 로그인 ID
 * @param permissionCodes 사용자가 보유한 권한 코드 집합
 */
public record MeResponse(
        long userPk,
        String userId,
        Set<String> permissionCodes
) {
}

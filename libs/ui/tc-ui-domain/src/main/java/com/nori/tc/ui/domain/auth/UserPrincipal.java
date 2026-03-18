package com.nori.tc.ui.domain.auth;

import java.util.Set;

/**
 * 인증이 완료된 사용자의 식별 정보와 권한 코드 목록입니다.
 *
 * <p>역할:</p>
 * <p>UiTokenAuthenticationFilter가 토큰 검증 후 생성하며,
 * Spring SecurityContext에 {@code UsernamePasswordAuthenticationToken}으로 등록합니다.
 * 이후 컨트롤러/서비스에서 인가(Authorization) 판단의 기준으로 사용됩니다.</p>
 *
 * <p>권한 코드(permissionCodes):</p>
 * <p>tc_user_group_member → tc_user_group_permission → tc_ui_permission 3-JOIN 쿼리로
 * 로드된 permCode 값들의 집합입니다. Business Redis에 TTL 기반으로 캐시되므로,
 * 권한 변경 시 캐시 TTL(tc.ui.backend.auth.token-cache-ttl-seconds) 이후 반영됩니다.</p>
 *
 * @param userPk          사용자 PK (tc_user_info.user_pk)
 * @param userId          사용자 ID (로그인 ID, 로그/감사 추적용)
 * @param permissionCodes 사용자가 보유한 권한 코드 집합 (불변 Set, tc_ui_permission.perm_code)
 */
public record UserPrincipal(
        long userPk,
        String userId,
        Set<String> permissionCodes
) {

    /**
     * 생성 시 필수 필드를 검증하고, 권한 코드 집합을 불변으로 고정합니다.
     */
    public UserPrincipal {
        if (userPk <= 0L) {
            throw new IllegalArgumentException("userPk는 양수여야 합니다. userPk=" + userPk);
        }
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId는 필수입니다.");
        }
        // null이면 빈 Set으로 초기화, 아니면 불변 복사본으로 교체
        // → 외부에서 참조를 통한 집합 변조를 방지합니다.
        permissionCodes = permissionCodes == null ? Set.of() : Set.copyOf(permissionCodes);
    }

    /**
     * 지정한 권한 코드를 보유하고 있는지 확인합니다.
     *
     * <p>URL 인가 판단 시 {@code tc_ui_permission.perm_code} 값과 비교합니다.</p>
     *
     * @param permCode 확인할 권한 코드 (null/공백이면 항상 false)
     * @return 권한 보유 여부
     */
    public boolean hasPermission(final String permCode) {
        if (permCode == null || permCode.isBlank()) {
            return false;
        }
        return permissionCodes.contains(permCode);
    }

    /**
     * 지정한 권한 코드 중 하나라도 보유하고 있는지 확인합니다.
     *
     * <p>하나의 리소스에 여러 권한 코드를 허용해야 할 때 사용합니다.</p>
     *
     * @param permCodes 확인할 권한 코드 목록 (null/빈 배열이면 항상 false)
     * @return 하나 이상의 권한 보유 여부
     */
    public boolean hasAnyPermission(final String... permCodes) {
        if (permCodes == null || permCodes.length == 0) {
            return false;
        }
        for (final String code : permCodes) {
            if (hasPermission(code)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 권한 코드를 하나도 보유하지 않았는지 확인합니다.
     *
     * @return 권한 없으면 true
     */
    public boolean hasNoPermission() {
        return permissionCodes.isEmpty();
    }
}

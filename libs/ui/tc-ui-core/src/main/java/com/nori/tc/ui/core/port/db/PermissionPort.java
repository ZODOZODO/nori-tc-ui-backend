package com.nori.tc.ui.core.port.db;

import java.util.Set;

/**
 * 사용자 권한 조회 DB 접근 포트입니다.
 *
 * <p>역할:</p>
 * <p>ValidateTokenUseCase가 인증 완료 후 해당 사용자의 모든 권한 코드를 로드하는 데 사용합니다.
 * 3-JOIN 쿼리로 그룹 멤버십 → 그룹 권한 → 권한 코드를 한 번에 조회합니다:</p>
 * <ol>
 *   <li>tc_user_group_member where user_pk = ?  → group_id 목록</li>
 *   <li>tc_user_group_permission where group_id in (...)  → perm_id 목록</li>
 *   <li>tc_ui_permission where perm_id in (...) and is_active = true  → perm_code 목록</li>
 * </ol>
 *
 * <p>구현체:</p>
 * <p>tc-ui-db-adapter의 JpaPermissionPort가 이 인터페이스를 구현합니다.</p>
 */
@FunctionalInterface
public interface PermissionPort {

    /**
     * 사용자 PK에 연결된 모든 활성 권한 코드를 조회합니다.
     *
     * <p>권한 코드(perm_code)는 URL 경로 인가 판단에 사용됩니다.
     * is_active = false인 권한은 결과에서 제외됩니다.</p>
     *
     * @param userPk 사용자 기본 키 (tc_user_info.user_pk)
     * @return 활성 권한 코드 집합, 권한이 없으면 빈 Set
     */
    Set<String> findPermissionCodesByUserPk(long userPk);

    /**
     * 테스트 또는 초기 구성에 사용할 noop(아무 동작 없음) 구현체를 반환합니다.
     *
     * @return 항상 빈 Set을 반환하는 noop 구현체
     */
    static PermissionPort noop() {
        return userPk -> Set.of();
    }
}

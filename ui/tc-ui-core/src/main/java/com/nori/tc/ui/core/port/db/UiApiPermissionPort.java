package com.nori.tc.ui.core.port.db;

import com.nori.tc.db.domain.user.TcUiPermission;

import java.util.List;

/**
 * 활성 API 권한 목록 전체 조회 포트입니다.
 *
 * <p>역할:</p>
 * <p>tc_ui_permission 테이블에서 resourceType=API인 활성 권한 목록을 로드합니다.
 * tc-ui-web-adapter의 {@code UiApiPermissionCache}가 기동 시 이 포트를 호출하여
 * 결과를 메모리에 캐시한 후, 매 HTTP 요청의 URL 인가 판단에 사용합니다.</p>
 *
 * <p>캐시 전략:</p>
 * <p>권한 데이터는 운영 중 변경 빈도가 낮으므로 기동 시 1회 로드합니다.
 * 권한 변경 사항을 반영하려면 애플리케이션을 재시작해야 합니다.
 * (Phase 6 설계 범위: 동적 갱신이 필요한 경우 별도 refresh 엔드포인트 추가 검토)</p>
 *
 * <p>구현체:</p>
 * <p>tc-ui-db-adapter의 {@code JpaApiPermissionPort}가 이 인터페이스를 구현합니다.</p>
 */
public interface UiApiPermissionPort {

    /**
     * 활성 상태(is_active=true)인 API 권한 목록을 전체 조회합니다.
     *
     * <p>반환 목록은 URL 인가 판단에 사용되므로,
     * resourceType=API이고 isActive=true인 항목만 포함합니다.</p>
     *
     * @return 활성 API 권한 목록 (순서 미보장, 비어있을 수 있음)
     */
    List<TcUiPermission> findAllActiveApiPermissions();
}

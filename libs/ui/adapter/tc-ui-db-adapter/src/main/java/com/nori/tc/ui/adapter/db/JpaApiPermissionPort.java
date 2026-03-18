package com.nori.tc.ui.adapter.db;

import com.nori.tc.db.core.common.PageRequest;
import com.nori.tc.db.core.user.store.TcUiPermissionStore;
import com.nori.tc.db.domain.common.user.TcUiPermissionResourceType;
import com.nori.tc.db.domain.user.TcUiPermission;
import com.nori.tc.ui.core.port.db.UiApiPermissionPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Objects;

/**
 * {@link UiApiPermissionPort}의 JPA 기반 구현 어댑터입니다.
 *
 * <p>역할:</p>
 * <p>tc_ui_permission 테이블에서 resourceType=API이고 isActive=true인
 * 권한 목록을 조회합니다. {@code UiApiPermissionCache}가 기동 시 1회 호출하여
 * 결과를 메모리에 캐시합니다.</p>
 *
 * <p>조회 방식:</p>
 * <p>기존 {@link TcUiPermissionStore#findAll(PageRequest)}를 큰 페이지 사이즈로 호출한 후
 * Java 스트림으로 resourceType=API, isActive=true 조건을 필터링합니다.
 * 권한 테이블의 행 수가 수천 건을 초과하는 경우, TcUiPermissionStore에
 * 전용 메서드를 추가하는 방향으로 리팩토링을 권장합니다.</p>
 *
 * <p>NOTE: 이 어댑터는 쓰기 없이 읽기 전용으로만 사용됩니다.</p>
 */
@Repository
public class JpaApiPermissionPort implements UiApiPermissionPort {

    private static final Logger log = LoggerFactory.getLogger(JpaApiPermissionPort.class);

    /**
     * 한 번에 로드할 최대 권한 행 수.
     *
     * <p>운영 환경에서 권한 테이블 행 수가 이 값을 초과하는 경우
     * TcUiPermissionStore에 전용 조회 메서드 추가를 검토하십시오.</p>
     */
    private static final int MAX_PERMISSION_LOAD = 5_000;

    private final TcUiPermissionStore permissionStore;

    /**
     * 필수 의존성을 초기화합니다.
     *
     * @param permissionStore tc_ui_permission Store
     */
    public JpaApiPermissionPort(final TcUiPermissionStore permissionStore) {
        this.permissionStore = Objects.requireNonNull(permissionStore, "permissionStore is null");
    }

    /**
     * 활성 API 권한 목록을 조회합니다.
     *
     * <p>전체 권한 테이블을 페이징 조회한 후 resourceType=API, isActive=true 조건으로 필터링합니다.
     * 반환 목록은 {@code UiApiPermissionCache}가 메모리에 캐시하므로 DB 부하는 기동 시 1회만 발생합니다.</p>
     *
     * @return 활성 API 권한 목록
     */
    @Override
    public List<TcUiPermission> findAllActiveApiPermissions() {
        final List<TcUiPermission> all = permissionStore.findAll(PageRequest.of(0, MAX_PERMISSION_LOAD));

        final List<TcUiPermission> apiPermissions = all.stream()
                .filter(TcUiPermission::isActive)
                .filter(p -> p.resourceType() == TcUiPermissionResourceType.API)
                .toList();

        log.debug("활성 API 권한 로드. 전체={}건, API 활성={}건", all.size(), apiPermissions.size());
        return apiPermissions;
    }
}

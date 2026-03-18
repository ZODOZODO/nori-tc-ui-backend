package com.nori.tc.ui.core.port.db;

import com.nori.tc.db.core.common.PageRequest;
import com.nori.tc.db.core.user.upsert.UpsertTcUiPermission;
import com.nori.tc.db.domain.user.TcUiPermission;
import com.nori.tc.ui.core.model.PagedResponse;

import java.util.Optional;

/**
 * UI 권한(Permission) CRUD 포트입니다.
 *
 * <p>UI 관리 페이지의 권한 정의 관리 기능을 추상화합니다.</p>
 * <ul>
 *   <li>{@code GET /api/permission}</li>
 *   <li>{@code GET /api/permission/{permId}}</li>
 *   <li>{@code POST /api/permission}</li>
 *   <li>{@code PUT /api/permission/{permId}}</li>
 *   <li>{@code DELETE /api/permission/{permId}}</li>
 * </ul>
 *
 * <p>주의 사항:</p>
 * <p>토큰 검증 시 사용자 권한 코드 집합 조회는 기존 {@link PermissionPort}가 담당합니다.</p>
 */
public interface PermissionCrudPort {

    /**
     * 권한 정보를 업서트합니다.
     *
     * @param command 저장/수정 입력 명령
     * @return 저장 후 권한 정보
     */
    TcUiPermission upsert(UpsertTcUiPermission command);

    /**
     * 권한 PK 기준 단건을 조회합니다.
     *
     * @param permId 권한 기본 키
     * @return 조회 결과, 미존재 시 빈 Optional
     */
    Optional<TcUiPermission> findByPermId(long permId);

    /**
     * 권한 목록을 페이지 단위로 조회합니다.
     *
     * @param pageRequest offset/limit 기반 페이지 요청
     * @return 페이지 응답(목록 + 전체 건수)
     */
    PagedResponse<TcUiPermission> findAll(PageRequest pageRequest);

    /**
     * 권한 PK 기준으로 삭제합니다.
     *
     * @param permId 삭제 대상 권한 PK
     */
    void deleteByPermId(long permId);
}

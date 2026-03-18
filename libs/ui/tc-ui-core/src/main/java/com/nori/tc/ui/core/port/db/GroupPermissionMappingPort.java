package com.nori.tc.ui.core.port.db;

import com.nori.tc.db.core.common.PageRequest;
import com.nori.tc.db.core.user.upsert.UpsertTcUserGroupPermission;
import com.nori.tc.db.domain.user.TcUserGroupPermission;
import com.nori.tc.ui.core.model.PagedResponse;

import java.util.Optional;

/**
 * 그룹-권한 매핑(Group Permission Mapping) 관리 포트입니다.
 *
 * <p>본 포트는 그룹 페이지의 권한 매핑 기능과 연결됩니다.</p>
 * <ul>
 *   <li>{@code GET /api/group/{groupId}/permission}</li>
 *   <li>{@code POST /api/group/{groupId}/permission/{permId}}</li>
 *   <li>{@code DELETE /api/group/{groupId}/permission/{permId}}</li>
 * </ul>
 */
public interface GroupPermissionMappingPort {

    /**
     * 그룹-권한 매핑을 업서트합니다.
     *
     * @param command 저장/수정 입력 명령
     * @return 저장 후 매핑 정보
     */
    TcUserGroupPermission upsert(UpsertTcUserGroupPermission command);

    /**
     * (groupId, permId) 유니크 키로 단건을 조회합니다.
     *
     * @param groupId 그룹 PK
     * @param permId 권한 PK
     * @return 조회 결과, 미존재 시 빈 Optional
     */
    Optional<TcUserGroupPermission> findByGroupIdPermId(long groupId, long permId);

    /**
     * 특정 그룹에 연결된 권한 매핑 목록을 페이지 단위로 조회합니다.
     *
     * @param groupId 그룹 PK
     * @param pageRequest offset/limit 기반 페이지 요청
     * @return 페이지 응답(목록 + 전체 건수)
     */
    PagedResponse<TcUserGroupPermission> findAllByGroupId(long groupId, PageRequest pageRequest);

    /**
     * (groupId, permId) 유니크 키 기준으로 매핑을 삭제합니다.
     *
     * @param groupId 그룹 PK
     * @param permId 권한 PK
     */
    void deleteByGroupIdPermId(long groupId, long permId);
}

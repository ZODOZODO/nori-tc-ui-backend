package com.nori.tc.ui.core.port.db;

import com.nori.tc.db.core.common.PageRequest;
import com.nori.tc.db.core.user.upsert.UpsertTcUserGroup;
import com.nori.tc.db.domain.user.TcUserGroup;
import com.nori.tc.ui.core.model.PagedResponse;

import java.util.Optional;

/**
 * 그룹 정보(Group Info) CRUD 포트입니다.
 *
 * <p>UI 관리 페이지의 그룹 조회/등록/수정/삭제 기능을 추상화합니다.</p>
 * <ul>
 *   <li>{@code GET /api/group}</li>
 *   <li>{@code GET /api/group/{groupId}}</li>
 *   <li>{@code POST /api/group}</li>
 *   <li>{@code PUT /api/group/{groupId}}</li>
 *   <li>{@code DELETE /api/group/{groupId}}</li>
 * </ul>
 */
public interface GroupCrudPort {

    /**
     * 그룹 정보를 업서트합니다.
     *
     * @param command 저장/수정 입력 명령
     * @return 저장 후 그룹 정보
     */
    TcUserGroup upsert(UpsertTcUserGroup command);

    /**
     * 그룹 PK 기준 단건을 조회합니다.
     *
     * @param groupId 그룹 기본 키
     * @return 조회 결과, 미존재 시 빈 Optional
     */
    Optional<TcUserGroup> findByGroupId(long groupId);

    /**
     * 그룹 목록을 페이지 단위로 조회합니다.
     *
     * @param pageRequest offset/limit 기반 페이지 요청
     * @return 페이지 응답(목록 + 전체 건수)
     */
    PagedResponse<TcUserGroup> findAll(PageRequest pageRequest);

    /**
     * 그룹 PK 기준으로 삭제합니다.
     *
     * @param groupId 삭제 대상 그룹 PK
     */
    void deleteByGroupId(long groupId);
}

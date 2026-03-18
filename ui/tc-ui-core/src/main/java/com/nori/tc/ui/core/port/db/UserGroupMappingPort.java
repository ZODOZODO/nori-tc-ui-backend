package com.nori.tc.ui.core.port.db;

import com.nori.tc.db.core.common.PageRequest;
import com.nori.tc.db.core.user.upsert.UpsertTcUserGroupMember;
import com.nori.tc.db.domain.user.TcUserGroupMember;
import com.nori.tc.ui.core.model.PagedResponse;

import java.util.Optional;

/**
 * 사용자-그룹 매핑(User Group Membership) 관리 포트입니다.
 *
 * <p>본 포트는 사용자 페이지의 그룹 매핑 기능과 연결됩니다.</p>
 * <ul>
 *   <li>{@code POST /api/user/{userPk}/group/{groupId}}</li>
 *   <li>{@code DELETE /api/user/{userPk}/group/{groupId}}</li>
 * </ul>
 */
public interface UserGroupMappingPort {

    /**
     * 사용자-그룹 매핑을 업서트합니다.
     *
     * @param command 저장/수정 입력 명령
     * @return 저장 후 매핑 정보
     */
    TcUserGroupMember upsert(UpsertTcUserGroupMember command);

    /**
     * (userPk, groupId) 유니크 키로 단건을 조회합니다.
     *
     * @param userPk 사용자 PK
     * @param groupId 그룹 PK
     * @return 조회 결과, 미존재 시 빈 Optional
     */
    Optional<TcUserGroupMember> findByUserPkAndGroupId(long userPk, long groupId);

    /**
     * 특정 사용자에 연결된 그룹 매핑 목록을 페이지 단위로 조회합니다.
     *
     * @param userPk 사용자 PK
     * @param pageRequest offset/limit 기반 페이지 요청
     * @return 페이지 응답(목록 + 전체 건수)
     */
    PagedResponse<TcUserGroupMember> findAllByUserPk(long userPk, PageRequest pageRequest);

    /**
     * (userPk, groupId) 유니크 키 기준으로 매핑을 삭제합니다.
     *
     * @param userPk 사용자 PK
     * @param groupId 그룹 PK
     */
    void deleteByUserPkAndGroupId(long userPk, long groupId);
}

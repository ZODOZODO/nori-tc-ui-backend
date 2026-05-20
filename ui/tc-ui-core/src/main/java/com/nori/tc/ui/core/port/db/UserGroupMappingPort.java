package com.nori.tc.ui.core.port.db;

import com.nori.tc.ui.core.model.PagedResponse;
import com.nori.tc.ui.core.model.UiPageRequest;
import com.nori.tc.ui.core.model.UiUserGroupMemberUpsertCommand;
import com.nori.tc.ui.domain.useradmin.UiUserGroupMember;

import java.util.Collection;
import java.util.List;
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
    UiUserGroupMember upsert(UiUserGroupMemberUpsertCommand command);

    /**
     * (userPk, groupId) 유니크 키로 단건을 조회합니다.
     *
     * @param userPk 사용자 PK
     * @param groupId 그룹 PK
     * @return 조회 결과, 미존재 시 빈 Optional
     */
    Optional<UiUserGroupMember> findByUserPkAndGroupId(long userPk, long groupId);

    /**
     * 특정 사용자에 연결된 그룹 매핑 목록을 페이지 단위로 조회합니다.
     *
     * @param userPk 사용자 PK
     * @param pageRequest offset/limit 기반 페이지 요청
     * @return 페이지 응답(목록 + 전체 건수)
     */
    PagedResponse<UiUserGroupMember> findAllByUserPk(long userPk, UiPageRequest pageRequest);

    /**
     * 여러 사용자 PK 에 매핑된 그룹 멤버를 IN 절로 한 번에 조회합니다.
     *
     * <p>사용자 목록 화면에서 페이지당 사용자들의 소속 그룹을 N+1 없이 받아오기 위해 사용합니다.</p>
     *
     * @param userPks 조회할 사용자 PK 컬렉션 (null/empty 면 빈 리스트 반환)
     * @return 해당 사용자들의 그룹 매핑 목록 (페이징 없음)
     */
    List<UiUserGroupMember> findAllByUserPkIn(Collection<Long> userPks);

    /**
     * 특정 그룹에 속한 사용자 매핑 목록을 페이지 단위로 조회합니다.
     *
     * @param groupId 그룹 PK
     * @param pageRequest offset/limit 기반 페이지 요청
     * @return 페이지 응답(목록 + 전체 건수)
     */
    PagedResponse<UiUserGroupMember> findAllByGroupId(long groupId, UiPageRequest pageRequest);

    /**
     * (userPk, groupId) 유니크 키 기준으로 매핑을 삭제합니다.
     *
     * @param userPk 사용자 PK
     * @param groupId 그룹 PK
     */
    void deleteByUserPkAndGroupId(long userPk, long groupId);
}

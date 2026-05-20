package com.nori.tc.ui.adapters.web.dto.response;

import com.nori.tc.ui.domain.auth.UiUserStatus;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 사용자 목록 응답의 단일 행 DTO 입니다.
 *
 * <p>{@link UserInfoResponse} 의 모든 필드에 추가로 사용자가 속한 그룹 요약 정보를 포함합니다.
 * 단건 조회(GET /api/user/{userPk}) / POST / PUT 응답은 그룹 정보를 포함하지 않는 {@link UserInfoResponse}
 * 를 그대로 사용하고, 목록(GET /api/user) 응답에서만 본 DTO 를 사용합니다.</p>
 *
 * <p>보안 정책상 {@code passwordHash} 는 절대 노출하지 않습니다.</p>
 *
 * @param userPk     사용자 PK
 * @param company    회사명
 * @param department 부서명
 * @param userName   사용자 이름
 * @param userId     로그인 ID 원문
 * @param userIdNorm 로그인 ID 정규화 값
 * @param email      이메일
 * @param status     사용자 상태
 * @param createdAt  생성 시각
 * @param updatedAt  수정 시각
 * @param createdBy  생성자
 * @param updatedBy  수정자
 * @param groups     사용자가 속한 그룹 요약 목록 (소속 없으면 빈 리스트)
 */
public record UserListItemResponse(
        Long userPk,
        String company,
        String department,
        String userName,
        String userId,
        String userIdNorm,
        String email,
        UiUserStatus status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        String createdBy,
        String updatedBy,
        List<GroupSummary> groups
) {

    /**
     * 사용자 목록 행에 포함되는 그룹 요약 정보입니다.
     *
     * @param groupId   그룹 PK
     * @param groupCode 그룹 코드 (예: ADMIN, OPERATOR)
     * @param groupName 그룹 이름
     */
    public record GroupSummary(
            Long groupId,
            String groupCode,
            String groupName
    ) {
    }
}

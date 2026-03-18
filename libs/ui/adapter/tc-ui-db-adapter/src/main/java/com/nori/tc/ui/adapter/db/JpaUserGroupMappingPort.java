package com.nori.tc.ui.adapter.db;

import com.nori.tc.db.core.common.PageRequest;
import com.nori.tc.db.core.user.store.TcUserGroupMemberStore;
import com.nori.tc.db.core.user.upsert.UpsertTcUserGroupMember;
import com.nori.tc.db.domain.user.TcUserGroupMember;
import com.nori.tc.ui.core.exception.UiBadRequestException;
import com.nori.tc.ui.core.exception.UiConflictException;
import com.nori.tc.ui.core.model.PagedResponse;
import com.nori.tc.ui.core.port.db.UserGroupMappingPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link UserGroupMappingPort}의 DB Store 기반 구현체입니다.
 *
 * <p>정책:</p>
 * <ul>
 *   <li>매핑 삭제는 물리 삭제(DELETE)만 수행합니다.</li>
 *   <li>입력값 오류는 {@link UiBadRequestException}(400 대응)으로 변환합니다.</li>
 *   <li>중복/무결성 충돌은 {@link UiConflictException}(409 대응)으로 변환합니다.</li>
 * </ul>
 */
@Repository
public class JpaUserGroupMappingPort implements UserGroupMappingPort {

    private static final Logger log = LoggerFactory.getLogger(JpaUserGroupMappingPort.class);

    private final TcUserGroupMemberStore userGroupMemberStore;

    /**
     * 필수 의존성을 초기화합니다.
     *
     * @param userGroupMemberStore tc_user_group_member Store 포트
     */
    public JpaUserGroupMappingPort(final TcUserGroupMemberStore userGroupMemberStore) {
        this.userGroupMemberStore = Objects.requireNonNull(userGroupMemberStore, "userGroupMemberStore is null");
        log.info("JpaUserGroupMappingPort initialized. source=tc_user_group_member");
    }

    /**
     * 사용자-그룹 매핑을 업서트합니다.
     *
     * @param command 저장/수정 입력
     * @return 저장 후 매핑 정보
     */
    @Override
    public TcUserGroupMember upsert(final UpsertTcUserGroupMember command) {
        if (log.isDebugEnabled()) {
            log.debug("사용자-그룹 매핑 업서트 시작. ugmKey={}, userPk={}, groupId={}",
                    command == null ? null : command.ugmKey(),
                    command == null ? null : command.userPk(),
                    command == null ? null : command.groupId());
        }

        try {
            final TcUserGroupMember saved = userGroupMemberStore.upsert(command);
            if (log.isDebugEnabled()) {
                log.debug("사용자-그룹 매핑 업서트 완료. ugmKey={}, userPk={}, groupId={}",
                        saved.ugmKey(), saved.userPk(), saved.groupId());
            }
            return saved;
        } catch (RuntimeException e) {
            if (UiDbAdapterExceptionSupport.isBadRequest(e)) {
                log.warn("사용자-그룹 매핑 업서트 요청 거부 - 잘못된 입력. reason={}",
                        UiDbAdapterExceptionSupport.resolveMessage(e, "invalid user-group upsert command"));
                throw new UiBadRequestException("사용자-그룹 매핑 저장 입력이 올바르지 않습니다.", e);
            }
            if (UiDbAdapterExceptionSupport.isConflict(e)) {
                log.warn("사용자-그룹 매핑 업서트 충돌. reason={}",
                        UiDbAdapterExceptionSupport.resolveMessage(e, "user-group mapping conflict"));
                throw new UiConflictException("사용자-그룹 매핑 저장 중 충돌이 발생했습니다.", e);
            }
            log.error("사용자-그룹 매핑 업서트 실패.", e);
            throw e;
        }
    }

    /**
     * (userPk, groupId) 유니크 키로 단건을 조회합니다.
     *
     * @param userPk 사용자 PK
     * @param groupId 그룹 PK
     * @return 조회 결과(없으면 빈 Optional)
     */
    @Override
    public Optional<TcUserGroupMember> findByUserPkAndGroupId(final long userPk, final long groupId) {
        if (userPk <= 0 || groupId <= 0) {
            log.warn("사용자-그룹 매핑 단건 조회 요청 거부 - userPk/groupId는 1 이상이어야 합니다. userPk={}, groupId={}",
                    userPk, groupId);
            throw new UiBadRequestException("userPk와 groupId는 1 이상이어야 합니다.");
        }

        if (log.isDebugEnabled()) {
            log.debug("사용자-그룹 매핑 단건 조회 시작. userPk={}, groupId={}", userPk, groupId);
        }

        try {
            final Optional<TcUserGroupMember> result = userGroupMemberStore.findByUserPkAndGroupId(userPk, groupId);
            if (log.isDebugEnabled()) {
                log.debug("사용자-그룹 매핑 단건 조회 완료. userPk={}, groupId={}, found={}",
                        userPk, groupId, result.isPresent());
            }
            return result;
        } catch (RuntimeException e) {
            if (UiDbAdapterExceptionSupport.isBadRequest(e)) {
                log.warn("사용자-그룹 매핑 단건 조회 요청 거부 - 잘못된 입력. userPk={}, groupId={}, reason={}",
                        userPk, groupId, UiDbAdapterExceptionSupport.resolveMessage(e, "invalid userPk/groupId"));
                throw new UiBadRequestException("사용자-그룹 매핑 조회 입력이 올바르지 않습니다.", e);
            }
            log.error("사용자-그룹 매핑 단건 조회 실패. userPk={}, groupId={}", userPk, groupId, e);
            throw e;
        }
    }

    /**
     * 특정 사용자의 그룹 매핑 목록을 페이지 단위로 조회합니다.
     *
     * @param userPk 사용자 PK
     * @param pageRequest offset/limit 요청(없으면 기본 페이지 사용)
     * @return 목록 + count 페이지 응답
     */
    @Override
    public PagedResponse<TcUserGroupMember> findAllByUserPk(final long userPk, final PageRequest pageRequest) {
        if (userPk <= 0) {
            log.warn("사용자-그룹 매핑 목록 조회 요청 거부 - userPk는 1 이상이어야 합니다. userPk={}", userPk);
            throw new UiBadRequestException("userPk는 1 이상이어야 합니다.");
        }
        final PageRequest effectivePage = (pageRequest == null) ? PageRequest.defaultPage() : pageRequest;

        if (log.isDebugEnabled()) {
            log.debug("사용자-그룹 매핑 목록 조회 시작. userPk={}, offset={}, limit={}",
                    userPk, effectivePage.offset(), effectivePage.limit());
        }

        try {
            final List<TcUserGroupMember> items = userGroupMemberStore.findAllByUserPk(userPk, effectivePage);
            final long totalCount = UiDbPagedCountSupport.resolveTotalCount(
                    items,
                    effectivePage,
                    UiDbPagedCountSupport.DEFAULT_COUNT_SCAN_LIMIT,
                    page -> userGroupMemberStore.findAllByUserPk(userPk, page),
                    log,
                    "user-group-mapping"
            );

            if (log.isDebugEnabled()) {
                log.debug("사용자-그룹 매핑 목록 조회 완료. userPk={}, offset={}, limit={}, pageSize={}, totalCount={}",
                        userPk, effectivePage.offset(), effectivePage.limit(), items.size(), totalCount);
            }

            return PagedResponse.of(items, effectivePage.offset(), effectivePage.limit(), totalCount);
        } catch (RuntimeException e) {
            if (UiDbAdapterExceptionSupport.isBadRequest(e)) {
                log.warn("사용자-그룹 매핑 목록 조회 요청 거부 - 잘못된 입력. userPk={}, offset={}, limit={}, reason={}",
                        userPk, effectivePage.offset(), effectivePage.limit(),
                        UiDbAdapterExceptionSupport.resolveMessage(e, "invalid page request"));
                throw new UiBadRequestException("사용자-그룹 매핑 목록 조회 입력이 올바르지 않습니다.", e);
            }
            log.error("사용자-그룹 매핑 목록 조회 실패. userPk={}, offset={}, limit={}",
                    userPk, effectivePage.offset(), effectivePage.limit(), e);
            throw e;
        }
    }

    /**
     * (userPk, groupId) 유니크 키 기준으로 매핑을 물리 삭제합니다.
     *
     * @param userPk 사용자 PK
     * @param groupId 그룹 PK
     */
    @Override
    public void deleteByUserPkAndGroupId(final long userPk, final long groupId) {
        if (userPk <= 0 || groupId <= 0) {
            log.warn("사용자-그룹 매핑 삭제 요청 거부 - userPk/groupId는 1 이상이어야 합니다. userPk={}, groupId={}",
                    userPk, groupId);
            throw new UiBadRequestException("userPk와 groupId는 1 이상이어야 합니다.");
        }

        if (log.isDebugEnabled()) {
            log.debug("사용자-그룹 매핑 삭제 시작. userPk={}, groupId={}", userPk, groupId);
        }

        try {
            userGroupMemberStore.deleteByUserPkAndGroupId(userPk, groupId);
            log.info("사용자-그룹 매핑 삭제 완료. userPk={}, groupId={}", userPk, groupId);
        } catch (RuntimeException e) {
            if (UiDbAdapterExceptionSupport.isBadRequest(e)) {
                log.warn("사용자-그룹 매핑 삭제 요청 거부 - 잘못된 입력. userPk={}, groupId={}, reason={}",
                        userPk, groupId, UiDbAdapterExceptionSupport.resolveMessage(e, "invalid userPk/groupId"));
                throw new UiBadRequestException("사용자-그룹 매핑 삭제 입력이 올바르지 않습니다.", e);
            }
            if (UiDbAdapterExceptionSupport.isConflict(e)) {
                log.warn("사용자-그룹 매핑 삭제 충돌. userPk={}, groupId={}, reason={}",
                        userPk, groupId, UiDbAdapterExceptionSupport.resolveMessage(e, "user-group mapping delete conflict"));
                throw new UiConflictException("사용자-그룹 매핑 삭제 중 충돌이 발생했습니다.", e);
            }
            log.error("사용자-그룹 매핑 삭제 실패. userPk={}, groupId={}", userPk, groupId, e);
            throw e;
        }
    }
}

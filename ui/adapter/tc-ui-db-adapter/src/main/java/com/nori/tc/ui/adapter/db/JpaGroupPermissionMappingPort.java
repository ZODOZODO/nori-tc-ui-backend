package com.nori.tc.ui.adapter.db;

import com.nori.tc.db.core.common.PageRequest;
import com.nori.tc.db.core.user.store.TcUserGroupPermissionStore;
import com.nori.tc.db.core.user.upsert.UpsertTcUserGroupPermission;
import com.nori.tc.db.domain.user.TcUserGroupPermission;
import com.nori.tc.ui.core.exception.UiBadRequestException;
import com.nori.tc.ui.core.exception.UiConflictException;
import com.nori.tc.ui.core.model.PagedResponse;
import com.nori.tc.ui.core.port.db.GroupPermissionMappingPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link GroupPermissionMappingPort}의 DB Store 기반 구현체입니다.
 *
 * <p>정책:</p>
 * <ul>
 *   <li>매핑 삭제는 물리 삭제(DELETE)만 수행합니다.</li>
 *   <li>입력값 오류는 {@link UiBadRequestException}(400 대응)으로 변환합니다.</li>
 *   <li>중복/무결성 충돌은 {@link UiConflictException}(409 대응)으로 변환합니다.</li>
 * </ul>
 */
@Repository
public class JpaGroupPermissionMappingPort implements GroupPermissionMappingPort {

    private static final Logger log = LoggerFactory.getLogger(JpaGroupPermissionMappingPort.class);

    private final TcUserGroupPermissionStore userGroupPermissionStore;

    /**
     * 필수 의존성을 초기화합니다.
     *
     * @param userGroupPermissionStore tc_user_group_permission Store 포트
     */
    public JpaGroupPermissionMappingPort(final TcUserGroupPermissionStore userGroupPermissionStore) {
        this.userGroupPermissionStore = Objects.requireNonNull(userGroupPermissionStore, "userGroupPermissionStore is null");
        log.info("JpaGroupPermissionMappingPort initialized. source=tc_user_group_permission");
    }

    /**
     * 그룹-권한 매핑을 업서트합니다.
     *
     * @param command 저장/수정 입력
     * @return 저장 후 매핑 정보
     */
    @Override
    public TcUserGroupPermission upsert(final UpsertTcUserGroupPermission command) {
        if (log.isDebugEnabled()) {
            log.debug("그룹-권한 매핑 업서트 시작. groupId={}, permId={}",
                    command == null ? null : command.groupId(),
                    command == null ? null : command.permId());
        }

        try {
            final TcUserGroupPermission saved = userGroupPermissionStore.upsert(command);
            if (log.isDebugEnabled()) {
                log.debug("그룹-권한 매핑 업서트 완료. ugpKey={}, groupId={}, permId={}",
                        saved.ugpKey(), saved.groupId(), saved.permId());
            }
            return saved;
        } catch (RuntimeException e) {
            if (UiDbAdapterExceptionSupport.isBadRequest(e)) {
                log.warn("그룹-권한 매핑 업서트 요청 거부 - 잘못된 입력. reason={}",
                        UiDbAdapterExceptionSupport.resolveMessage(e, "invalid group-permission upsert command"));
                throw new UiBadRequestException("그룹-권한 매핑 저장 입력이 올바르지 않습니다.", e);
            }
            if (UiDbAdapterExceptionSupport.isConflict(e)) {
                log.warn("그룹-권한 매핑 업서트 충돌. reason={}",
                        UiDbAdapterExceptionSupport.resolveMessage(e, "group-permission mapping conflict"));
                throw new UiConflictException("그룹-권한 매핑 저장 중 충돌이 발생했습니다.", e);
            }
            log.error("그룹-권한 매핑 업서트 실패.", e);
            throw e;
        }
    }

    /**
     * (groupId, permId) 유니크 키로 단건을 조회합니다.
     *
     * @param groupId 그룹 PK
     * @param permId 권한 PK
     * @return 조회 결과(없으면 빈 Optional)
     */
    @Override
    public Optional<TcUserGroupPermission> findByGroupIdPermId(final long groupId, final long permId) {
        if (groupId <= 0 || permId <= 0) {
            log.warn("그룹-권한 매핑 단건 조회 요청 거부 - groupId/permId는 1 이상이어야 합니다. groupId={}, permId={}",
                    groupId, permId);
            throw new UiBadRequestException("groupId와 permId는 1 이상이어야 합니다.");
        }

        if (log.isDebugEnabled()) {
            log.debug("그룹-권한 매핑 단건 조회 시작. groupId={}, permId={}", groupId, permId);
        }

        try {
            final Optional<TcUserGroupPermission> result = userGroupPermissionStore.findByGroupIdPermId(groupId, permId);
            if (log.isDebugEnabled()) {
                log.debug("그룹-권한 매핑 단건 조회 완료. groupId={}, permId={}, found={}",
                        groupId, permId, result.isPresent());
            }
            return result;
        } catch (RuntimeException e) {
            if (UiDbAdapterExceptionSupport.isBadRequest(e)) {
                log.warn("그룹-권한 매핑 단건 조회 요청 거부 - 잘못된 입력. groupId={}, permId={}, reason={}",
                        groupId, permId, UiDbAdapterExceptionSupport.resolveMessage(e, "invalid groupId/permId"));
                throw new UiBadRequestException("그룹-권한 매핑 조회 입력이 올바르지 않습니다.", e);
            }
            log.error("그룹-권한 매핑 단건 조회 실패. groupId={}, permId={}", groupId, permId, e);
            throw e;
        }
    }

    /**
     * 특정 그룹의 권한 매핑 목록을 페이지 단위로 조회합니다.
     *
     * @param groupId 그룹 PK
     * @param pageRequest offset/limit 요청(없으면 기본 페이지 사용)
     * @return 목록 + count 페이지 응답
     */
    @Override
    public PagedResponse<TcUserGroupPermission> findAllByGroupId(final long groupId, final PageRequest pageRequest) {
        if (groupId <= 0) {
            log.warn("그룹-권한 매핑 목록 조회 요청 거부 - groupId는 1 이상이어야 합니다. groupId={}", groupId);
            throw new UiBadRequestException("groupId는 1 이상이어야 합니다.");
        }
        final PageRequest effectivePage = (pageRequest == null) ? PageRequest.defaultPage() : pageRequest;

        if (log.isDebugEnabled()) {
            log.debug("그룹-권한 매핑 목록 조회 시작. groupId={}, offset={}, limit={}",
                    groupId, effectivePage.offset(), effectivePage.limit());
        }

        try {
            final List<TcUserGroupPermission> items = userGroupPermissionStore.findAllByGroupId(groupId, effectivePage);
            final long totalCount = UiDbPagedCountSupport.resolveTotalCount(
                    items,
                    effectivePage,
                    UiDbPagedCountSupport.DEFAULT_COUNT_SCAN_LIMIT,
                    page -> userGroupPermissionStore.findAllByGroupId(groupId, page),
                    log,
                    "group-permission-mapping"
            );

            if (log.isDebugEnabled()) {
                log.debug("그룹-권한 매핑 목록 조회 완료. groupId={}, offset={}, limit={}, pageSize={}, totalCount={}",
                        groupId, effectivePage.offset(), effectivePage.limit(), items.size(), totalCount);
            }

            return PagedResponse.of(items, effectivePage.offset(), effectivePage.limit(), totalCount);
        } catch (RuntimeException e) {
            if (UiDbAdapterExceptionSupport.isBadRequest(e)) {
                log.warn("그룹-권한 매핑 목록 조회 요청 거부 - 잘못된 입력. groupId={}, offset={}, limit={}, reason={}",
                        groupId, effectivePage.offset(), effectivePage.limit(),
                        UiDbAdapterExceptionSupport.resolveMessage(e, "invalid page request"));
                throw new UiBadRequestException("그룹-권한 매핑 목록 조회 입력이 올바르지 않습니다.", e);
            }
            log.error("그룹-권한 매핑 목록 조회 실패. groupId={}, offset={}, limit={}",
                    groupId, effectivePage.offset(), effectivePage.limit(), e);
            throw e;
        }
    }

    /**
     * (groupId, permId) 유니크 키 기준으로 매핑을 물리 삭제합니다.
     *
     * @param groupId 그룹 PK
     * @param permId 권한 PK
     */
    @Override
    public void deleteByGroupIdPermId(final long groupId, final long permId) {
        if (groupId <= 0 || permId <= 0) {
            log.warn("그룹-권한 매핑 삭제 요청 거부 - groupId/permId는 1 이상이어야 합니다. groupId={}, permId={}",
                    groupId, permId);
            throw new UiBadRequestException("groupId와 permId는 1 이상이어야 합니다.");
        }

        if (log.isDebugEnabled()) {
            log.debug("그룹-권한 매핑 삭제 시작. groupId={}, permId={}", groupId, permId);
        }

        try {
            userGroupPermissionStore.deleteByGroupIdPermId(groupId, permId);
            log.info("그룹-권한 매핑 삭제 완료. groupId={}, permId={}", groupId, permId);
        } catch (RuntimeException e) {
            if (UiDbAdapterExceptionSupport.isBadRequest(e)) {
                log.warn("그룹-권한 매핑 삭제 요청 거부 - 잘못된 입력. groupId={}, permId={}, reason={}",
                        groupId, permId, UiDbAdapterExceptionSupport.resolveMessage(e, "invalid groupId/permId"));
                throw new UiBadRequestException("그룹-권한 매핑 삭제 입력이 올바르지 않습니다.", e);
            }
            if (UiDbAdapterExceptionSupport.isConflict(e)) {
                log.warn("그룹-권한 매핑 삭제 충돌. groupId={}, permId={}, reason={}",
                        groupId, permId, UiDbAdapterExceptionSupport.resolveMessage(e, "group-permission mapping delete conflict"));
                throw new UiConflictException("그룹-권한 매핑 삭제 중 충돌이 발생했습니다.", e);
            }
            log.error("그룹-권한 매핑 삭제 실패. groupId={}, permId={}", groupId, permId, e);
            throw e;
        }
    }
}

package com.nori.tc.ui.adapter.db;

import com.nori.tc.db.core.common.PageRequest;
import com.nori.tc.db.core.user.store.TcUserGroupStore;
import com.nori.tc.db.core.user.upsert.UpsertTcUserGroup;
import com.nori.tc.db.domain.user.TcUserGroup;
import com.nori.tc.ui.core.exception.UiBadRequestException;
import com.nori.tc.ui.core.exception.UiConflictException;
import com.nori.tc.ui.core.model.PagedResponse;
import com.nori.tc.ui.core.port.db.GroupCrudPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link GroupCrudPort}의 DB Store 기반 구현체입니다.
 *
 * <p>정책:</p>
 * <ul>
 *   <li>입력 오류는 {@link UiBadRequestException}(400 대응)으로 변환</li>
 *   <li>중복/무결성 충돌은 {@link UiConflictException}(409 대응)으로 변환</li>
 * </ul>
 */
@Repository
public class JpaGroupCrudPort implements GroupCrudPort {

    private static final Logger log = LoggerFactory.getLogger(JpaGroupCrudPort.class);

    private final TcUserGroupStore userGroupStore;

    /**
     * 필수 의존성을 초기화합니다.
     *
     * @param userGroupStore tc_user_group Store 포트
     */
    public JpaGroupCrudPort(final TcUserGroupStore userGroupStore) {
        this.userGroupStore = Objects.requireNonNull(userGroupStore, "userGroupStore is null");
        log.info("JpaGroupCrudPort initialized. source=tc_user_group");
    }

    /**
     * 그룹 정보를 업서트합니다.
     *
     * @param command 저장/수정 입력
     * @return 저장 후 그룹 정보
     */
    @Override
    public TcUserGroup upsert(final UpsertTcUserGroup command) {
        if (log.isDebugEnabled()) {
            log.debug("그룹 업서트 시작. groupId={}, groupCode={}, isActive={}",
                    command == null ? null : command.groupId(),
                    command == null ? null : command.groupCode(),
                    command != null && command.isActive());
        }

        try {
            final TcUserGroup saved = userGroupStore.upsert(command);
            if (log.isDebugEnabled()) {
                log.debug("그룹 업서트 완료. groupId={}, groupCode={}, isActive={}",
                        saved.groupId(), saved.groupCode(), saved.isActive());
            }
            return saved;
        } catch (RuntimeException e) {
            if (UiDbAdapterExceptionSupport.isBadRequest(e)) {
                log.warn("그룹 업서트 요청 거부 - 잘못된 입력. reason={}",
                        UiDbAdapterExceptionSupport.resolveMessage(e, "invalid group upsert command"));
                throw new UiBadRequestException("그룹 저장 입력이 올바르지 않습니다.", e);
            }
            if (UiDbAdapterExceptionSupport.isConflict(e)) {
                log.warn("그룹 업서트 충돌. reason={}",
                        UiDbAdapterExceptionSupport.resolveMessage(e, "group conflict"));
                throw new UiConflictException("그룹 저장 중 충돌이 발생했습니다.", e);
            }
            log.error("그룹 업서트 실패.", e);
            throw e;
        }
    }

    /**
     * 그룹 PK 기준 단건을 조회합니다.
     *
     * @param groupId 그룹 PK
     * @return 조회 결과(없으면 빈 Optional)
     */
    @Override
    public Optional<TcUserGroup> findByGroupId(final long groupId) {
        if (groupId <= 0) {
            log.warn("그룹 단건 조회 요청 거부 - groupId는 1 이상이어야 합니다. groupId={}", groupId);
            throw new UiBadRequestException("groupId는 1 이상이어야 합니다.");
        }

        if (log.isDebugEnabled()) {
            log.debug("그룹 단건 조회 시작. groupId={}", groupId);
        }

        try {
            final Optional<TcUserGroup> result = userGroupStore.findByGroupId(groupId);
            if (log.isDebugEnabled()) {
                log.debug("그룹 단건 조회 완료. groupId={}, found={}", groupId, result.isPresent());
            }
            return result;
        } catch (RuntimeException e) {
            if (UiDbAdapterExceptionSupport.isBadRequest(e)) {
                log.warn("그룹 단건 조회 요청 거부 - 잘못된 입력. groupId={}, reason={}",
                        groupId, UiDbAdapterExceptionSupport.resolveMessage(e, "invalid groupId"));
                throw new UiBadRequestException("그룹 조회 입력이 올바르지 않습니다.", e);
            }
            log.error("그룹 단건 조회 실패. groupId={}", groupId, e);
            throw e;
        }
    }

    /**
     * 그룹 목록을 페이지 단위로 조회합니다.
     *
     * @param pageRequest offset/limit 요청(없으면 기본 페이지 사용)
     * @return 목록 + count 페이지 응답
     */
    @Override
    public PagedResponse<TcUserGroup> findAll(final PageRequest pageRequest) {
        final PageRequest effectivePage = (pageRequest == null) ? PageRequest.defaultPage() : pageRequest;

        if (log.isDebugEnabled()) {
            log.debug("그룹 목록 조회 시작. offset={}, limit={}", effectivePage.offset(), effectivePage.limit());
        }

        try {
            final List<TcUserGroup> items = userGroupStore.findAll(effectivePage);
            final long totalCount = UiDbPagedCountSupport.resolveTotalCount(
                    items,
                    effectivePage,
                    UiDbPagedCountSupport.DEFAULT_COUNT_SCAN_LIMIT,
                    userGroupStore::findAll,
                    log,
                    "group"
            );

            if (log.isDebugEnabled()) {
                log.debug("그룹 목록 조회 완료. offset={}, limit={}, pageSize={}, totalCount={}",
                        effectivePage.offset(), effectivePage.limit(), items.size(), totalCount);
            }

            return PagedResponse.of(items, effectivePage.offset(), effectivePage.limit(), totalCount);
        } catch (RuntimeException e) {
            if (UiDbAdapterExceptionSupport.isBadRequest(e)) {
                log.warn("그룹 목록 조회 요청 거부 - 잘못된 입력. offset={}, limit={}, reason={}",
                        effectivePage.offset(), effectivePage.limit(),
                        UiDbAdapterExceptionSupport.resolveMessage(e, "invalid page request"));
                throw new UiBadRequestException("그룹 목록 조회 입력이 올바르지 않습니다.", e);
            }
            log.error("그룹 목록 조회 실패. offset={}, limit={}",
                    effectivePage.offset(), effectivePage.limit(), e);
            throw e;
        }
    }

    /**
     * 그룹 PK 기준으로 삭제합니다.
     *
     * @param groupId 삭제 대상 그룹 PK
     */
    @Override
    public void deleteByGroupId(final long groupId) {
        if (groupId <= 0) {
            log.warn("그룹 삭제 요청 거부 - groupId는 1 이상이어야 합니다. groupId={}", groupId);
            throw new UiBadRequestException("groupId는 1 이상이어야 합니다.");
        }

        if (log.isDebugEnabled()) {
            log.debug("그룹 삭제 시작. groupId={}", groupId);
        }

        try {
            userGroupStore.deleteByGroupId(groupId);
            log.info("그룹 삭제 완료. groupId={}", groupId);
        } catch (RuntimeException e) {
            if (UiDbAdapterExceptionSupport.isBadRequest(e)) {
                log.warn("그룹 삭제 요청 거부 - 잘못된 입력. groupId={}, reason={}",
                        groupId, UiDbAdapterExceptionSupport.resolveMessage(e, "invalid groupId"));
                throw new UiBadRequestException("그룹 삭제 입력이 올바르지 않습니다.", e);
            }
            if (UiDbAdapterExceptionSupport.isConflict(e)) {
                log.warn("그룹 삭제 충돌. groupId={}, reason={}",
                        groupId, UiDbAdapterExceptionSupport.resolveMessage(e, "group delete conflict"));
                throw new UiConflictException("그룹 삭제 중 충돌이 발생했습니다.", e);
            }
            log.error("그룹 삭제 실패. groupId={}", groupId, e);
            throw e;
        }
    }
}

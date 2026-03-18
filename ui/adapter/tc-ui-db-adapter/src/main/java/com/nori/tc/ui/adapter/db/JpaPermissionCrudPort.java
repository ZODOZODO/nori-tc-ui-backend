package com.nori.tc.ui.adapter.db;

import com.nori.tc.db.core.common.PageRequest;
import com.nori.tc.db.core.user.store.TcUiPermissionStore;
import com.nori.tc.db.core.user.upsert.UpsertTcUiPermission;
import com.nori.tc.db.domain.user.TcUiPermission;
import com.nori.tc.ui.core.exception.UiBadRequestException;
import com.nori.tc.ui.core.exception.UiConflictException;
import com.nori.tc.ui.core.model.PagedResponse;
import com.nori.tc.ui.core.port.db.PermissionCrudPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link PermissionCrudPort}의 DB Store 기반 구현체입니다.
 *
 * <p>정책:</p>
 * <ul>
 *   <li>입력값 오류는 {@link UiBadRequestException}(400 대응)으로 변환합니다.</li>
 *   <li>중복/무결성 충돌은 {@link UiConflictException}(409 대응)으로 변환합니다.</li>
 * </ul>
 */
@Repository
public class JpaPermissionCrudPort implements PermissionCrudPort {

    private static final Logger log = LoggerFactory.getLogger(JpaPermissionCrudPort.class);

    private final TcUiPermissionStore permissionStore;

    /**
     * 필수 의존성을 초기화합니다.
     *
     * @param permissionStore tc_ui_permission Store 포트
     */
    public JpaPermissionCrudPort(final TcUiPermissionStore permissionStore) {
        this.permissionStore = Objects.requireNonNull(permissionStore, "permissionStore is null");
        log.info("JpaPermissionCrudPort initialized. source=tc_ui_permission");
    }

    /**
     * 권한 정보를 업서트합니다.
     *
     * @param command 저장/수정 입력
     * @return 저장 후 권한 정보
     */
    @Override
    public TcUiPermission upsert(final UpsertTcUiPermission command) {
        if (log.isDebugEnabled()) {
            log.debug("권한 업서트 시작. permId={}, permCode={}, resource={}, httpMethod={}",
                    command == null ? null : command.permId(),
                    command == null ? null : command.permCode(),
                    command == null ? null : command.resource(),
                    command == null ? null : command.httpMethod());
        }

        try {
            final TcUiPermission saved = permissionStore.upsert(command);
            if (log.isDebugEnabled()) {
                log.debug("권한 업서트 완료. permId={}, permCode={}, isActive={}",
                        saved.permId(), saved.permCode(), saved.isActive());
            }
            return saved;
        } catch (RuntimeException e) {
            if (UiDbAdapterExceptionSupport.isBadRequest(e)) {
                log.warn("권한 업서트 요청 거부 - 잘못된 입력. reason={}",
                        UiDbAdapterExceptionSupport.resolveMessage(e, "invalid permission upsert command"));
                throw new UiBadRequestException("권한 저장 입력이 올바르지 않습니다.", e);
            }
            if (UiDbAdapterExceptionSupport.isConflict(e)) {
                log.warn("권한 업서트 충돌. reason={}",
                        UiDbAdapterExceptionSupport.resolveMessage(e, "permission conflict"));
                throw new UiConflictException("권한 저장 중 충돌이 발생했습니다.", e);
            }
            log.error("권한 업서트 실패.", e);
            throw e;
        }
    }

    /**
     * 권한 PK 기준 단건을 조회합니다.
     *
     * @param permId 권한 PK
     * @return 조회 결과(없으면 빈 Optional)
     */
    @Override
    public Optional<TcUiPermission> findByPermId(final long permId) {
        if (permId <= 0) {
            log.warn("권한 단건 조회 요청 거부 - permId는 1 이상이어야 합니다. permId={}", permId);
            throw new UiBadRequestException("permId는 1 이상이어야 합니다.");
        }

        if (log.isDebugEnabled()) {
            log.debug("권한 단건 조회 시작. permId={}", permId);
        }

        try {
            final Optional<TcUiPermission> result = permissionStore.findByPermId(permId);
            if (log.isDebugEnabled()) {
                log.debug("권한 단건 조회 완료. permId={}, found={}", permId, result.isPresent());
            }
            return result;
        } catch (RuntimeException e) {
            if (UiDbAdapterExceptionSupport.isBadRequest(e)) {
                log.warn("권한 단건 조회 요청 거부 - 잘못된 입력. permId={}, reason={}",
                        permId, UiDbAdapterExceptionSupport.resolveMessage(e, "invalid permId"));
                throw new UiBadRequestException("권한 조회 입력이 올바르지 않습니다.", e);
            }
            log.error("권한 단건 조회 실패. permId={}", permId, e);
            throw e;
        }
    }

    /**
     * 권한 목록을 페이지 단위로 조회합니다.
     *
     * @param pageRequest offset/limit 요청(없으면 기본 페이지 사용)
     * @return 목록 + count 페이지 응답
     */
    @Override
    public PagedResponse<TcUiPermission> findAll(final PageRequest pageRequest) {
        final PageRequest effectivePage = (pageRequest == null) ? PageRequest.defaultPage() : pageRequest;

        if (log.isDebugEnabled()) {
            log.debug("권한 목록 조회 시작. offset={}, limit={}", effectivePage.offset(), effectivePage.limit());
        }

        try {
            final List<TcUiPermission> items = permissionStore.findAll(effectivePage);
            final long totalCount = UiDbPagedCountSupport.resolveTotalCount(
                    items,
                    effectivePage,
                    UiDbPagedCountSupport.DEFAULT_COUNT_SCAN_LIMIT,
                    permissionStore::findAll,
                    log,
                    "permission"
            );

            if (log.isDebugEnabled()) {
                log.debug("권한 목록 조회 완료. offset={}, limit={}, pageSize={}, totalCount={}",
                        effectivePage.offset(), effectivePage.limit(), items.size(), totalCount);
            }

            return PagedResponse.of(items, effectivePage.offset(), effectivePage.limit(), totalCount);
        } catch (RuntimeException e) {
            if (UiDbAdapterExceptionSupport.isBadRequest(e)) {
                log.warn("권한 목록 조회 요청 거부 - 잘못된 입력. offset={}, limit={}, reason={}",
                        effectivePage.offset(), effectivePage.limit(),
                        UiDbAdapterExceptionSupport.resolveMessage(e, "invalid page request"));
                throw new UiBadRequestException("권한 목록 조회 입력이 올바르지 않습니다.", e);
            }
            log.error("권한 목록 조회 실패. offset={}, limit={}",
                    effectivePage.offset(), effectivePage.limit(), e);
            throw e;
        }
    }

    /**
     * 권한 PK 기준으로 삭제합니다.
     *
     * @param permId 삭제 대상 권한 PK
     */
    @Override
    public void deleteByPermId(final long permId) {
        if (permId <= 0) {
            log.warn("권한 삭제 요청 거부 - permId는 1 이상이어야 합니다. permId={}", permId);
            throw new UiBadRequestException("permId는 1 이상이어야 합니다.");
        }

        if (log.isDebugEnabled()) {
            log.debug("권한 삭제 시작. permId={}", permId);
        }

        try {
            permissionStore.deleteByPermId(permId);
            log.info("권한 삭제 완료. permId={}", permId);
        } catch (RuntimeException e) {
            if (UiDbAdapterExceptionSupport.isBadRequest(e)) {
                log.warn("권한 삭제 요청 거부 - 잘못된 입력. permId={}, reason={}",
                        permId, UiDbAdapterExceptionSupport.resolveMessage(e, "invalid permId"));
                throw new UiBadRequestException("권한 삭제 입력이 올바르지 않습니다.", e);
            }
            if (UiDbAdapterExceptionSupport.isConflict(e)) {
                log.warn("권한 삭제 충돌. permId={}, reason={}",
                        permId, UiDbAdapterExceptionSupport.resolveMessage(e, "permission delete conflict"));
                throw new UiConflictException("권한 삭제 중 충돌이 발생했습니다.", e);
            }
            log.error("권한 삭제 실패. permId={}", permId, e);
            throw e;
        }
    }
}

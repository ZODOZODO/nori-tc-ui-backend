package com.nori.tc.ui.adapter.db;

import com.nori.tc.db.core.common.PageRequest;
import com.nori.tc.db.core.user.store.TcUiAuthSessionStore;
import com.nori.tc.db.core.user.store.TcUserInfoStore;
import com.nori.tc.db.core.user.upsert.UpsertTcUserInfo;
import com.nori.tc.db.domain.user.TcUiAuthSession;
import com.nori.tc.db.domain.user.TcUserInfo;
import com.nori.tc.ui.core.exception.UiBadRequestException;
import com.nori.tc.ui.core.exception.UiConflictException;
import com.nori.tc.ui.core.model.PagedResponse;
import com.nori.tc.ui.core.port.db.UserCrudPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link UserCrudPort}의 DB Store 기반 구현체입니다.
 *
 * <p>핵심 정책:</p>
 * <ul>
 *   <li>사용자 삭제 전 {@code tc_ui_auth_session} 정리 절차를 반드시 선행합니다.</li>
 *   <li>중복/무결성 충돌은 {@link UiConflictException}(409 대응)으로 변환합니다.</li>
 *   <li>입력값 오류는 {@link UiBadRequestException}(400 대응)으로 변환합니다.</li>
 * </ul>
 */
@Repository
public class JpaUserCrudPort implements UserCrudPort {

    private static final Logger log = LoggerFactory.getLogger(JpaUserCrudPort.class);

    /**
     * 사용자 삭제 전 세션 정리 배치 크기입니다.
     *
     * <p>삭제 대상 사용자의 세션 수가 많을 수 있으므로 고정 배치 단위로 반복 정리합니다.</p>
     */
    private static final int SESSION_CLEANUP_BATCH_LIMIT = 200;

    private final TcUserInfoStore userInfoStore;
    private final TcUiAuthSessionStore authSessionStore;

    /**
     * 필수 의존성을 초기화합니다.
     *
     * @param userInfoStore tc_user_info Store 포트
     * @param authSessionStore tc_ui_auth_session Store 포트
     */
    public JpaUserCrudPort(
            final TcUserInfoStore userInfoStore,
            final TcUiAuthSessionStore authSessionStore
    ) {
        this.userInfoStore = Objects.requireNonNull(userInfoStore, "userInfoStore is null");
        this.authSessionStore = Objects.requireNonNull(authSessionStore, "authSessionStore is null");
        log.info("JpaUserCrudPort initialized. source=tc_user_info + tc_ui_auth_session");
    }

    /**
     * 사용자 정보를 업서트합니다.
     *
     * @param command 저장/수정 입력
     * @return 저장 후 사용자 정보
     */
    @Override
    public TcUserInfo upsert(final UpsertTcUserInfo command) {
        if (log.isDebugEnabled()) {
            log.debug("사용자 업서트 시작. userPk={}, userIdNorm={}, email={}",
                    command == null ? null : command.userPk(),
                    command == null ? null : command.userIdNorm(),
                    command == null ? null : command.email());
        }

        try {
            final TcUserInfo saved = userInfoStore.upsert(command);
            if (log.isDebugEnabled()) {
                log.debug("사용자 업서트 완료. userPk={}, userIdNorm={}, status={}",
                        saved.userPk(), saved.userIdNorm(), saved.status());
            }
            return saved;
        } catch (RuntimeException e) {
            if (UiDbAdapterExceptionSupport.isBadRequest(e)) {
                log.warn("사용자 업서트 요청 거부 - 잘못된 입력. reason={}",
                        UiDbAdapterExceptionSupport.resolveMessage(e, "invalid user upsert command"));
                throw new UiBadRequestException("사용자 저장 입력이 올바르지 않습니다.", e);
            }
            if (UiDbAdapterExceptionSupport.isConflict(e)) {
                log.warn("사용자 업서트 충돌. reason={}",
                        UiDbAdapterExceptionSupport.resolveMessage(e, "user conflict"));
                throw new UiConflictException("사용자 저장 중 충돌이 발생했습니다.", e);
            }
            log.error("사용자 업서트 실패.", e);
            throw e;
        }
    }

    /**
     * 사용자 PK 기준 단건을 조회합니다.
     *
     * @param userPk 사용자 PK
     * @return 조회 결과(없으면 빈 Optional)
     */
    @Override
    public Optional<TcUserInfo> findByUserPk(final long userPk) {
        if (userPk <= 0) {
            log.warn("사용자 단건 조회 요청 거부 - userPk는 1 이상이어야 합니다. userPk={}", userPk);
            throw new UiBadRequestException("userPk는 1 이상이어야 합니다.");
        }

        if (log.isDebugEnabled()) {
            log.debug("사용자 단건 조회 시작. userPk={}", userPk);
        }

        try {
            final Optional<TcUserInfo> result = userInfoStore.findByUserPk(userPk);
            if (log.isDebugEnabled()) {
                log.debug("사용자 단건 조회 완료. userPk={}, found={}", userPk, result.isPresent());
            }
            return result;
        } catch (RuntimeException e) {
            if (UiDbAdapterExceptionSupport.isBadRequest(e)) {
                log.warn("사용자 단건 조회 요청 거부 - 잘못된 입력. userPk={}, reason={}",
                        userPk, UiDbAdapterExceptionSupport.resolveMessage(e, "invalid userPk"));
                throw new UiBadRequestException("사용자 조회 입력이 올바르지 않습니다.", e);
            }
            log.error("사용자 단건 조회 실패. userPk={}", userPk, e);
            throw e;
        }
    }

    /**
     * 사용자 목록을 페이지 단위로 조회합니다.
     *
     * @param pageRequest offset/limit 요청(없으면 기본 페이지 사용)
     * @return 목록 + count 페이지 응답
     */
    @Override
    public PagedResponse<TcUserInfo> findAll(final PageRequest pageRequest) {
        final PageRequest effectivePage = (pageRequest == null) ? PageRequest.defaultPage() : pageRequest;

        if (log.isDebugEnabled()) {
            log.debug("사용자 목록 조회 시작. offset={}, limit={}", effectivePage.offset(), effectivePage.limit());
        }

        try {
            final List<TcUserInfo> items = userInfoStore.findAll(effectivePage);
            final long totalCount = UiDbPagedCountSupport.resolveTotalCount(
                    items,
                    effectivePage,
                    UiDbPagedCountSupport.DEFAULT_COUNT_SCAN_LIMIT,
                    userInfoStore::findAll,
                    log,
                    "user"
            );

            if (log.isDebugEnabled()) {
                log.debug("사용자 목록 조회 완료. offset={}, limit={}, pageSize={}, totalCount={}",
                        effectivePage.offset(), effectivePage.limit(), items.size(), totalCount);
            }

            return PagedResponse.of(items, effectivePage.offset(), effectivePage.limit(), totalCount);
        } catch (RuntimeException e) {
            if (UiDbAdapterExceptionSupport.isBadRequest(e)) {
                log.warn("사용자 목록 조회 요청 거부 - 잘못된 입력. offset={}, limit={}, reason={}",
                        effectivePage.offset(), effectivePage.limit(),
                        UiDbAdapterExceptionSupport.resolveMessage(e, "invalid page request"));
                throw new UiBadRequestException("사용자 목록 조회 입력이 올바르지 않습니다.", e);
            }
            log.error("사용자 목록 조회 실패. offset={}, limit={}",
                    effectivePage.offset(), effectivePage.limit(), e);
            throw e;
        }
    }

    /**
     * 사용자 PK 기준으로 삭제합니다.
     *
     * <p>삭제 순서(강제):</p>
     * <ol>
     *   <li>{@code tc_ui_auth_session} 선정리</li>
     *   <li>{@code tc_user_info} 삭제</li>
     * </ol>
     *
     * @param userPk 삭제 대상 사용자 PK
     */
    @Override
    public void deleteByUserPk(final long userPk) {
        if (userPk <= 0) {
            log.warn("사용자 삭제 요청 거부 - userPk는 1 이상이어야 합니다. userPk={}", userPk);
            throw new UiBadRequestException("userPk는 1 이상이어야 합니다.");
        }

        if (log.isDebugEnabled()) {
            log.debug("사용자 삭제 시작. userPk={}", userPk);
        }

        try {
            final int cleanedSessionCount = cleanupAuthSessionsBeforeUserDelete(userPk);
            userInfoStore.deleteByUserPk(userPk);

            log.info("사용자 삭제 완료. userPk={}, cleanedSessionCount={}", userPk, cleanedSessionCount);
        } catch (RuntimeException e) {
            if (UiDbAdapterExceptionSupport.isBadRequest(e)) {
                log.warn("사용자 삭제 요청 거부 - 잘못된 입력. userPk={}, reason={}",
                        userPk, UiDbAdapterExceptionSupport.resolveMessage(e, "invalid userPk"));
                throw new UiBadRequestException("사용자 삭제 입력이 올바르지 않습니다.", e);
            }
            if (UiDbAdapterExceptionSupport.isConflict(e)) {
                log.warn("사용자 삭제 충돌. userPk={}, reason={}",
                        userPk, UiDbAdapterExceptionSupport.resolveMessage(e, "user delete conflict"));
                throw new UiConflictException("사용자 삭제 중 충돌이 발생했습니다.", e);
            }
            log.error("사용자 삭제 실패. userPk={}", userPk, e);
            throw e;
        }
    }

    /**
     * 사용자 삭제 전 인증 세션을 배치 단위로 정리합니다.
     *
     * <p>핵심 구현 포인트:</p>
     * <ul>
     *   <li>항상 offset=0으로 조회해 삭제로 인한 페이지 이동(skipping) 문제를 방지합니다.</li>
     *   <li>조회 결과가 없어질 때까지 반복하여 세션 잔존을 허용하지 않습니다.</li>
     * </ul>
     *
     * @param userPk 삭제 대상 사용자 PK
     * @return 실제 삭제된 세션 건수
     */
    private int cleanupAuthSessionsBeforeUserDelete(final long userPk) {
        int cleanedCount = 0;
        int loopCount = 0;

        while (true) {
            final List<TcUiAuthSession> sessions = authSessionStore.findAllByUserPk(
                    userPk,
                    PageRequest.of(0, SESSION_CLEANUP_BATCH_LIMIT)
            );

            if (sessions.isEmpty()) {
                if (cleanedCount == 0) {
                    if (log.isDebugEnabled()) {
                        log.debug("사용자 삭제 전 정리할 세션이 없습니다. userPk={}", userPk);
                    }
                } else {
                    log.info("사용자 삭제 전 세션 정리 완료. userPk={}, cleanedSessionCount={}, loops={}",
                            userPk, cleanedCount, loopCount);
                }
                return cleanedCount;
            }

            loopCount++;
            if (log.isTraceEnabled()) {
                log.trace("사용자 세션 정리 배치 처리. userPk={}, loop={}, batchSize={}",
                        userPk, loopCount, sessions.size());
            }

            for (TcUiAuthSession session : sessions) {
                authSessionStore.deleteByToken(session.token());
                cleanedCount++;
            }
        }
    }
}

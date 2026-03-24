package com.nori.tc.ui.adapter.db;

import com.nori.tc.db.core.user.store.TcUiAuthSessionStore;
import com.nori.tc.ui.adapter.db.mapper.UiAuthSessionDbMapper;
import com.nori.tc.ui.core.port.db.SessionPort;
import com.nori.tc.ui.domain.auth.UiAuthSessionCreateCommand;
import com.nori.tc.ui.domain.auth.UiAuthSessionSnapshot;
import com.nori.tc.ui.domain.auth.UiAuthTokenFingerprint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link SessionPort}의 JPA 기반 구현 어댑터입니다.
 *
 * <p>역할:</p>
 * <p>tc_ui_auth_session 테이블의 CRUD를 담당합니다.
 * {@link TcUiAuthSessionStore}를 통해 DB에 접근합니다.</p>
 *
 * <p>인증 흐름에서의 사용 시점:</p>
 * <ul>
 *   <li>로그인: 신규 세션 생성 → {@link #save}</li>
 *   <li>요청 인증: 유효 세션 조회 → {@link #findValidByToken}</li>
 *   <li>로그아웃: 세션 폐기 → {@link #revoke}</li>
 *   <li>활동 기록: 마지막 접근 시각 갱신 → {@link #updateLastSeenAt}</li>
 * </ul>
 */
@Repository
public class JpaSessionPort implements SessionPort {

    private static final Logger log = LoggerFactory.getLogger(JpaSessionPort.class);

    private final TcUiAuthSessionStore sessionStore;

    /**
     * 의존성을 초기화합니다.
     *
     * @param sessionStore tc_ui_auth_session Store
     */
    public JpaSessionPort(final TcUiAuthSessionStore sessionStore) {
        this.sessionStore = Objects.requireNonNull(sessionStore, "sessionStore is null");
        log.info("JpaSessionPort initialized. source=tc_ui_auth_session");
    }

    /**
     * 신규 인증 세션을 저장합니다.
     *
     * <p>로그인 성공 시 {@link com.nori.tc.ui.core.usecase.LoginUseCase}에서 호출합니다.</p>
     *
     * @param session 저장할 세션 Domain Record
     */
    @Override
    public void save(final UiAuthSessionCreateCommand session) {
        log.debug("세션 저장. token={}..., userPk={}, expiresAt={}",
                UiAuthTokenFingerprint.mask(session.token()),
                session.userPk(),
                session.expiresAt());

        sessionStore.upsert(UiAuthSessionDbMapper.toStoreCommand(session));

        log.info("세션 저장 완료. token={}..., userPk={}",
                UiAuthTokenFingerprint.mask(session.token()),
                session.userPk());
    }

    /**
     * 유효한 세션을 토큰으로 조회합니다.
     *
     * <p>조회 조건:
     * <ul>
     *   <li>token 일치</li>
     *   <li>revoked = false (폐기되지 않음)</li>
     *   <li>expires_at > 현재 시각 (만료되지 않음)</li>
     * </ul>
     * </p>
     *
     * @param token 검증할 세션 토큰
     * @return 유효한 세션 Domain Record, 없으면 빈 Optional
     */
    @Override
    public Optional<UiAuthSessionSnapshot> findValidByToken(final String token) {
        log.debug("유효 세션 조회. token={}...", UiAuthTokenFingerprint.mask(token));
        return sessionStore.findValidByToken(UiAuthTokenFingerprint.sha256Hex(token))
                .map(UiAuthSessionDbMapper::toSnapshot);
    }

    /**
     * 세션을 폐기합니다 (revoked = true).
     *
     * <p>로그아웃 시 {@link com.nori.tc.ui.core.usecase.LogoutUseCase}에서 호출합니다.
     * revoked 컬럼만 갱신하므로 SELECT 없이 단일 쿼리로 처리합니다.</p>
     *
     * @param token 폐기할 세션 토큰
     */
    @Override
    public void revoke(final String token) {
        log.debug("세션 폐기 요청. token={}...", UiAuthTokenFingerprint.mask(token));
        sessionStore.revokeByToken(UiAuthTokenFingerprint.sha256Hex(token));
        log.info("세션 폐기 완료. token={}...", UiAuthTokenFingerprint.mask(token));
    }

    /**
     * 마지막 접근 시각을 업데이트합니다.
     *
     * <p>인증 필터({@code UiTokenAuthenticationFilter})에서 매 요청 성공 시 호출됩니다.
     * lastSeenAt 컬럼만 갱신하여 불필요한 Entity 로드를 방지합니다.</p>
     *
     * @param token      갱신할 세션 토큰
     * @param lastSeenAt 기록할 최근 접근 시각
     */
    @Override
    public void updateLastSeenAt(final String token, final OffsetDateTime lastSeenAt) {
        log.trace("lastSeenAt 업데이트. token={}..., lastSeenAt={}",
                UiAuthTokenFingerprint.mask(token),
                lastSeenAt);
        sessionStore.updateLastSeenAt(UiAuthTokenFingerprint.sha256Hex(token), lastSeenAt);
    }
}

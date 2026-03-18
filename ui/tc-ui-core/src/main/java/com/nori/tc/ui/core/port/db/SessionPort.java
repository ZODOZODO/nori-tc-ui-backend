package com.nori.tc.ui.core.port.db;

import com.nori.tc.db.domain.user.TcUiAuthSession;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * 인증 세션(tc_ui_auth_session) DB 접근 포트입니다.
 *
 * <p>역할:</p>
 * <p>로그인 시 세션 저장, 매 요청마다 토큰 유효성 검증, 로그아웃 시 세션 폐기를 담당합니다.
 * 세션 테이블(tc_ui_auth_session)의 CRUD 작업을 추상화합니다.</p>
 *
 * <p>구현체:</p>
 * <p>tc-ui-db-adapter의 JpaSessionPort가 이 인터페이스를 구현합니다.</p>
 */
public interface SessionPort {

    /**
     * 새 인증 세션을 저장합니다.
     *
     * <p>LoginUseCase에서 로그인 성공 후 신규 세션 레코드를 삽입합니다.</p>
     *
     * @param session 저장할 세션 정보 (token, userPk, issuedAt, expiresAt 포함)
     */
    void save(TcUiAuthSession session);

    /**
     * 유효한 세션을 토큰으로 조회합니다.
     *
     * <p>다음 조건을 모두 만족하는 세션만 반환합니다:</p>
     * <ul>
     *   <li>token 값이 일치</li>
     *   <li>revoked = false (폐기되지 않음)</li>
     *   <li>expiresAt > 현재 시각 (만료되지 않음)</li>
     * </ul>
     *
     * @param token 조회할 세션 토큰 (PK)
     * @return 유효한 세션 정보, 없거나 만료/폐기된 경우 빈 Optional
     */
    Optional<TcUiAuthSession> findValidByToken(String token);

    /**
     * 세션을 폐기합니다 (로그아웃).
     *
     * <p>tc_ui_auth_session.revoked = true 로 업데이트합니다.
     * 이후 동일 토큰으로 인증을 시도해도 {@link #findValidByToken}에서 반환되지 않습니다.</p>
     *
     * @param token 폐기할 세션 토큰 (PK)
     */
    void revoke(String token);

    /**
     * 세션의 마지막 접근 시각을 업데이트합니다.
     *
     * <p>매 요청마다 UiTokenAuthenticationFilter가 비동기(@Async)로 호출하여
     * 세션 활동 시간을 기록합니다. 성능을 위해 UPSERT 또는 단순 UPDATE를 사용합니다.</p>
     *
     * @param token      업데이트할 세션 토큰
     * @param lastSeenAt 마지막 접근 시각
     */
    void updateLastSeenAt(String token, OffsetDateTime lastSeenAt);

    /**
     * 테스트 또는 초기 구성에 사용할 noop(아무 동작 없음) 구현체를 반환합니다.
     *
     * @return 아무 동작도 하지 않고 항상 빈 Optional을 반환하는 noop 구현체
     */
    static SessionPort noop() {
        return new SessionPort() {
            @Override
            public void save(final TcUiAuthSession session) {
                // noop: 아무 동작 없음
            }

            @Override
            public Optional<TcUiAuthSession> findValidByToken(final String token) {
                return Optional.empty();
            }

            @Override
            public void revoke(final String token) {
                // noop: 아무 동작 없음
            }

            @Override
            public void updateLastSeenAt(final String token, final OffsetDateTime lastSeenAt) {
                // noop: 아무 동작 없음
            }
        };
    }
}

package com.nori.tc.ui.adapter.db;

import com.nori.tc.db.core.user.store.TcUserInfoStore;
import com.nori.tc.db.domain.user.TcUserInfo;
import com.nori.tc.ui.core.port.db.UserPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.util.Objects;
import java.util.Optional;

/**
 * {@link UserPort}의 DB Store 기반 구현 어댑터입니다.
 *
 * <p>역할:</p>
 * <p>tc_user_info 테이블에서 사용자 정보를 조회합니다.
 * {@link TcUserInfoStore}를 통해 DB에 접근하고, Store가 반환하는 Domain Record를 그대로 사용합니다.</p>
 *
 * <p>사용 시점:</p>
 * <ul>
 *   <li>로그인: userId를 정규화(trim+toLowerCase)한 {@code userIdNorm}으로 사용자 조회</li>
 *   <li>토큰 검증: userPk로 계정 상태(ACTIVE/LOCKED 등) 확인</li>
 * </ul>
 */
@Repository
public class JpaUserPort implements UserPort {

    private static final Logger log = LoggerFactory.getLogger(JpaUserPort.class);

    private final TcUserInfoStore userInfoStore;

    /**
     * 의존성을 초기화합니다.
     *
     * @param userInfoStore tc_user_info Store (기술 중립 추상화)
     */
    public JpaUserPort(final TcUserInfoStore userInfoStore) {
        this.userInfoStore = Objects.requireNonNull(userInfoStore, "userInfoStore is null");
        log.info("JpaUserPort initialized. source=tc_user_info");
    }

    /**
     * 정규화된 사용자 ID로 사용자를 조회합니다.
     *
     * <p>로그인 시 userId를 trim().toLowerCase()로 정규화한 값과 비교합니다.
     * userIdNorm은 DB의 uk_tc_user_info_user_id_norm 유니크 인덱스 대상입니다.</p>
     *
     * @param userIdNorm 정규화된 사용자 ID (trim + toLowerCase 적용)
     * @return 사용자 정보, 없으면 빈 Optional
     */
    @Override
    public Optional<TcUserInfo> findByUserIdNorm(final String userIdNorm) {
        log.debug("사용자 조회 by userIdNorm. userIdNorm={}", userIdNorm);
        return userInfoStore.findByUserIdNorm(userIdNorm);
    }

    /**
     * 사용자 PK로 사용자를 조회합니다.
     *
     * <p>토큰 검증 필터에서 세션의 userPk를 통해 계정 상태를 확인할 때 사용합니다.</p>
     *
     * @param userPk 사용자 PK
     * @return 사용자 정보, 없으면 빈 Optional
     */
    @Override
    public Optional<TcUserInfo> findByUserPk(final long userPk) {
        log.debug("사용자 조회 by userPk. userPk={}", userPk);
        return userInfoStore.findByUserPk(userPk);
    }
}

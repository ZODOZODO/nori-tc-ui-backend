package com.nori.tc.ui.core.port.db;

import com.nori.tc.db.domain.user.TcUserInfo;

import java.util.Optional;

/**
 * 사용자 정보(tc_user_info) DB 접근 포트입니다.
 *
 * <p>역할:</p>
 * <p>LoginUseCase가 사용자 인증 시 ID/비밀번호 검증에 사용하고,
 * ValidateTokenUseCase가 토큰 검증 시 사용자 계정 상태를 확인하는 데 사용합니다.</p>
 *
 * <p>구현체:</p>
 * <p>tc-ui-db-adapter의 JpaUserPort가 이 인터페이스를 구현합니다.</p>
 */
public interface UserPort {

    /**
     * 정규화된 사용자 ID(소문자 변환 + 공백 제거)로 사용자를 조회합니다.
     *
     * <p>로그인 시 입력된 userId를 trim().toLowerCase()한 값(userIdNorm)으로 조회합니다.
     * 대소문자 및 앞뒤 공백을 무시한 일치 검색을 제공합니다.</p>
     *
     * @param userIdNorm 정규화된 사용자 ID (trim + toLowerCase 적용된 값)
     * @return 조회된 사용자 정보, 없으면 빈 Optional
     */
    Optional<TcUserInfo> findByUserIdNorm(String userIdNorm);

    /**
     * 사용자 PK로 사용자를 조회합니다.
     *
     * <p>ValidateTokenUseCase에서 토큰 소유자의 계정 상태(ACTIVE 여부)를
     * 확인하는 데 사용합니다.</p>
     *
     * @param userPk 사용자 기본 키 (tc_user_info.user_pk)
     * @return 조회된 사용자 정보, 없으면 빈 Optional
     */
    Optional<TcUserInfo> findByUserPk(long userPk);

    /**
     * 테스트 또는 초기 구성에 사용할 noop(아무 동작 없음) 구현체를 반환합니다.
     *
     * @return 항상 빈 Optional을 반환하는 noop 구현체
     */
    static UserPort noop() {
        return new UserPort() {
            @Override
            public Optional<TcUserInfo> findByUserIdNorm(final String userIdNorm) {
                return Optional.empty();
            }

            @Override
            public Optional<TcUserInfo> findByUserPk(final long userPk) {
                return Optional.empty();
            }
        };
    }
}

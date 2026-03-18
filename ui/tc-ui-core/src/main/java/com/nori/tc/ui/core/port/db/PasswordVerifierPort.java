package com.nori.tc.ui.core.port.db;

/**
 * 비밀번호 검증 포트입니다.
 *
 * <p>역할:</p>
 * <p>LoginUseCase가 Spring Security의 BCrypt 구현에 직접 의존하지 않도록 추상화합니다.
 * tc-ui-web-adapter에서 BCryptPasswordEncoder를 주입하여 구현합니다.</p>
 *
 * <p>설계 이유:</p>
 * <p>tc-ui-core는 기술 중립 계층이므로 Spring Security를 직접 참조할 수 없습니다.
 * 이 포트를 통해 core는 비밀번호 검증 로직을 모르고 결과(boolean)만 받습니다.</p>
 *
 * <p>구현체:</p>
 * <p>tc-ui-web-adapter의 BcryptPasswordVerifier가 이 인터페이스를 구현합니다.</p>
 */
@FunctionalInterface
public interface PasswordVerifierPort {

    /**
     * 원문 비밀번호와 해시된 비밀번호가 일치하는지 확인합니다.
     *
     * @param rawPassword     사용자가 입력한 원문 비밀번호
     * @param encodedPassword DB에 저장된 BCrypt 해시값 (tc_user_info.password_hash)
     * @return 일치하면 true, 불일치하면 false
     */
    boolean matches(String rawPassword, String encodedPassword);

    /**
     * 테스트 또는 초기 구성에 사용할 noop(아무 동작 없음) 구현체를 반환합니다.
     *
     * <p>항상 false를 반환하므로 모든 로그인을 거부합니다.
     * 실제 운영에서는 반드시 BCrypt 구현체를 주입해야 합니다.</p>
     *
     * @return 항상 false를 반환하는 noop 구현체
     */
    static PasswordVerifierPort noop() {
        return (rawPassword, encodedPassword) -> false;
    }
}

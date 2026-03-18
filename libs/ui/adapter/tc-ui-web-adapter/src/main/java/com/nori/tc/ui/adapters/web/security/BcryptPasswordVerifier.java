package com.nori.tc.ui.adapters.web.security;

import com.nori.tc.ui.core.port.db.PasswordVerifierPort;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * BCrypt 기반 비밀번호 검증 구현체입니다.
 *
 * <p>역할:</p>
 * <p>{@link PasswordVerifierPort}를 구현하여 {@code LoginUseCase}에 BCrypt 비밀번호 검증 기능을
 * 제공합니다. {@code tc-ui-core}가 Spring Security에 직접 의존하지 않도록 이 어댑터에서 구현합니다.</p>
 *
 * <p>사용:</p>
 * <p>{@link org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder}를 내부적으로
 * 사용하며, DB에 저장된 BCrypt 해시값과 사용자가 입력한 원문 비밀번호를 비교합니다.</p>
 */
@Component
public class BcryptPasswordVerifier implements PasswordVerifierPort {

    /**
     * BCryptPasswordEncoder는 상태가 없으므로 필드로 보관하여 재사용합니다.
     * strength(cost factor) 기본값은 10으로, 운영 환경에 적합합니다.
     */
    private final BCryptPasswordEncoder passwordEncoder;

    /**
     * BCryptPasswordEncoder 인스턴스를 초기화합니다.
     */
    public BcryptPasswordVerifier() {
        this.passwordEncoder = new BCryptPasswordEncoder();
    }

    /**
     * 원문 비밀번호와 BCrypt 해시값이 일치하는지 확인합니다.
     *
     * @param rawPassword     사용자가 입력한 원문 비밀번호
     * @param encodedPassword DB에 저장된 BCrypt 해시값 (tc_user_info.password_hash)
     * @return 일치하면 true, 불일치하면 false
     */
    @Override
    public boolean matches(final String rawPassword, final String encodedPassword) {
        return passwordEncoder.matches(rawPassword, encodedPassword);
    }
}

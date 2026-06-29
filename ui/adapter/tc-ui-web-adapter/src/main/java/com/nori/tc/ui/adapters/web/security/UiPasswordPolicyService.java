package com.nori.tc.ui.adapters.web.security;

import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Objects;

/**
 * UI 비밀번호 인코딩 정책을 한 곳에 모읍니다.
 *
 * <p>신규/변경 비밀번호는 {@link PasswordPolicyValidator} 로 길이·복잡도 정책을 강제한 뒤
 * 해시합니다(약한 비밀번호 거부).</p>
 */
public class UiPasswordPolicyService {

    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicyValidator passwordPolicyValidator;

    public UiPasswordPolicyService(final PasswordEncoder passwordEncoder) {
        this.passwordEncoder = Objects.requireNonNull(passwordEncoder, "passwordEncoder is null");
        this.passwordPolicyValidator = new PasswordPolicyValidator();
    }

    /**
     * 신규 비밀번호를 정책(길이·복잡도) 기준으로 검증한 뒤 해시합니다.
     *
     * @param rawPassword 입력 비밀번호
     * @return 저장할 password hash
     * @throws com.nori.tc.ui.core.exception.UiBadRequestException 정책 위반 시
     */
    public String encodeNewPassword(final String rawPassword) {
        passwordPolicyValidator.validate(rawPassword);
        return passwordEncoder.encode(rawPassword);
    }
}

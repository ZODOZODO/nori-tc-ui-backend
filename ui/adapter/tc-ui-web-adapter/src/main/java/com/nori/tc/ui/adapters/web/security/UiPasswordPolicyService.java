package com.nori.tc.ui.adapters.web.security;

import com.nori.tc.ui.core.exception.UiBadRequestException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Objects;

/**
 * UI 비밀번호 인코딩 정책을 한 곳에 모읍니다.
 */
public class UiPasswordPolicyService {

    private final PasswordEncoder passwordEncoder;

    public UiPasswordPolicyService(final PasswordEncoder passwordEncoder) {
        this.passwordEncoder = Objects.requireNonNull(passwordEncoder, "passwordEncoder is null");
    }

    /**
     * 신규 비밀번호를 정책 기준으로 정규화하고 해시합니다.
     *
     * @param rawPassword 입력 비밀번호
     * @return 저장할 password hash
     */
    public String encodeNewPassword(final String rawPassword) {
        if (rawPassword == null || rawPassword.isBlank()) {
            throw new UiBadRequestException("비밀번호는 비어 있을 수 없습니다.");
        }
        return passwordEncoder.encode(rawPassword.trim());
    }
}

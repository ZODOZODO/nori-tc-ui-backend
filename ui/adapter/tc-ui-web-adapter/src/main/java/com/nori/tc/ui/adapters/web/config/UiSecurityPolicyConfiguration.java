package com.nori.tc.ui.adapters.web.config;

import com.nori.tc.ui.adapters.web.security.UiPasswordPolicyService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * UI 보안 정책 bean을 한 곳에서 정의합니다.
 */
@Configuration(proxyBeanMethods = false)
public class UiSecurityPolicyConfiguration {

    @Bean
    PasswordEncoder uiPasswordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    UiPasswordPolicyService uiPasswordPolicyService(final PasswordEncoder uiPasswordEncoder) {
        return new UiPasswordPolicyService(uiPasswordEncoder);
    }
}

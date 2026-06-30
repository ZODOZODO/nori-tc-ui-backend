package com.nori.tc.ui.adapters.web.config;

import com.nori.tc.ui.adapters.web.security.UiPasswordPolicyService;
import com.nori.tc.ui.core.port.db.PasswordHistoryPort;
import com.nori.tc.ui.core.port.db.PasswordVerifierPort;
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
    UiPasswordPolicyService uiPasswordPolicyService(
            final PasswordEncoder uiPasswordEncoder,
            final PasswordHistoryPort passwordHistoryPort,
            final PasswordVerifierPort passwordVerifierPort
    ) {
        return new UiPasswordPolicyService(uiPasswordEncoder, passwordHistoryPort, passwordVerifierPort);
    }
}

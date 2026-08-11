package com.nori.tc.ui.adapters.web.controller;

import org.springframework.context.MessageSource;
import org.springframework.context.support.ResourceBundleMessageSource;

/**
 * standalone MockMvc 컨트롤러 테스트에서 {@code UiApiExceptionHandler} 에 주입할 {@link MessageSource} 헬퍼입니다.
 *
 * <p>운영과 동일한 {@code messages.properties}(base=ko) / {@code messages_en.properties} 를 로드하며,
 * 미정의 코드는 코드 자체를 반환해 메시지 텍스트를 단언하지 않는 테스트에서도 예외 없이 동작합니다.</p>
 *
 * <p>이 헬퍼는 번들을 직접 만들어 주입하므로 <b>부트가 messageSource 빈을 만드는지</b>는 검증하지 못한다.
 * 그 축은 {@code UiMessageSourceAutoConfigurationTest} 가 담당한다.</p>
 */
final class UiControllerTestMessageSource {

    private UiControllerTestMessageSource() {
    }

    static MessageSource create() {
        final ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("messages");
        source.setDefaultEncoding("UTF-8");
        // 운영(spring.messages.fallback-to-system-locale=false)과 동일 조건. 켜두면 ko 전용 번들이 없어
        // JVM 기본 locale 번들(messages_en)로 폴백한다 — 기본 locale 이 en 인 CI 러너에서만 한국어 단언이 깨진다.
        source.setFallbackToSystemLocale(false);
        source.setUseCodeAsDefaultMessage(true);
        return source;
    }
}

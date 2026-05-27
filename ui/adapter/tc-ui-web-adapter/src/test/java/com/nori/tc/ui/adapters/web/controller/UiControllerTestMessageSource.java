package com.nori.tc.ui.adapters.web.controller;

import org.springframework.context.MessageSource;
import org.springframework.context.support.ResourceBundleMessageSource;

/**
 * standalone MockMvc 컨트롤러 테스트에서 {@code UiApiExceptionHandler} 에 주입할 {@link MessageSource} 헬퍼입니다.
 *
 * <p>운영과 동일한 {@code messages_ko/en.properties} 를 로드하며, 미정의 코드는 코드 자체를 반환해
 * 메시지 텍스트를 단언하지 않는 테스트에서도 예외 없이 동작합니다.</p>
 */
final class UiControllerTestMessageSource {

    private UiControllerTestMessageSource() {
    }

    static MessageSource create() {
        final ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("messages");
        source.setDefaultEncoding("UTF-8");
        source.setUseCodeAsDefaultMessage(true);
        return source;
    }
}

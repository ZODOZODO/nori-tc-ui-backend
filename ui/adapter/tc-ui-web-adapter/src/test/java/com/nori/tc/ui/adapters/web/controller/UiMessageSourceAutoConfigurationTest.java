package com.nori.tc.ui.adapters.web.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.MessageSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.MessageSource;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 운영 부트 조건에서 {@code messages} 번들이 실제로 로드되는지 검증합니다.
 *
 * <p>다른 i18n 테스트들은 {@code ResourceBundleMessageSource} 를 손으로 만들어 주입하므로
 * <b>번들 파일이 읽히는지</b>만 보고 <b>부트가 messageSource 빈을 만드는지</b>는 보지 못한다.
 * 그 사각지대에서 실제 결함이 났다 — base {@code messages.properties} 가 없으면
 * {@code MessageSourceAutoConfiguration} 의 {@code ResourceBundleCondition}
 * ({@code classpath*:messages.properties} 존재 검사)이 불일치해 auto-config 가 물러나고,
 * 주입되는 것은 부모 없는 {@code DelegatingMessageSource} 가 된다. 그 결과 모든 로케일에서
 * 메시지 코드 원문("error.internal")이 응답 본문에 그대로 노출됐다.
 *
 * <p>속성값은 {@code nori-tc-exec/apps/tc-ui-backend-app/src/main/resources/application.yaml}
 * 의 {@code spring.messages} 와 동일하게 맞춘다 — 운영과 다른 조건으로 통과하면 의미가 없다.</p>
 */
class UiMessageSourceAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MessageSourceAutoConfiguration.class))
            .withPropertyValues(
                    "spring.messages.basename=messages",
                    "spring.messages.encoding=UTF-8",
                    "spring.messages.fallback-to-system-locale=false"
            );

    @Test
    @DisplayName("운영 속성 그대로 부트하면 ko/en 메시지가 코드가 아닌 본문으로 해석됩니다")
    void resolvesBundledMessagesUnderProductionProperties() {
        contextRunner.run(context -> {
            final MessageSource messageSource = context.getBean(MessageSource.class);

            assertThat(messageSource.getMessage("error.internal", null, Locale.KOREAN))
                    .isEqualTo("요청 처리 중 내부 오류가 발생했습니다.");
            assertThat(messageSource.getMessage("error.internal", null, Locale.ENGLISH))
                    .isEqualTo("An internal error occurred while processing the request.");
        });
    }

    /**
     * 전용 번들이 없는 로케일은 base 번들로 떨어져야 합니다.
     *
     * <p>{@code fallback-to-system-locale=false} 라 시스템 로케일 우회가 없다 — base 가 없으면
     * 여기서 {@code NoSuchMessageException} 이 난다.</p>
     */
    @Test
    @DisplayName("전용 번들이 없는 로케일도 base 번들로 해석됩니다")
    void fallsBackToBaseBundleForUnmappedLocale() {
        contextRunner.run(context -> {
            final MessageSource messageSource = context.getBean(MessageSource.class);

            assertThat(messageSource.getMessage("error.internal", null, Locale.JAPANESE))
                    .isEqualTo("요청 처리 중 내부 오류가 발생했습니다.");
        });
    }
}

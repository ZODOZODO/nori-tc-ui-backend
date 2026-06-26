package com.nori.tc.ui.adapters.web.controller;

import com.nori.tc.ui.adapters.web.controller.support.UiApiExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link UiApiExceptionHandler} 의 i18n 메시지 해석을 검증합니다.
 *
 * <p>요청 {@code Accept-Language} 가 ko / en 일 때 동일 예외에 대한 응답 메시지가
 * {@code messages_ko/en.properties} 기준으로 달라지는지 확인합니다.</p>
 */
class UiApiExceptionHandlerI18nTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new BoomController())
                .setControllerAdvice(new UiApiExceptionHandler(UiControllerTestMessageSource.create()))
                .build();
    }

    @Test
    @DisplayName("Accept-Language=ko 면 내부 오류 메시지를 한국어로 응답한다")
    void shouldResolveInternalErrorMessageInKorean() throws Exception {
        mockMvc.perform(get("/i18n-test/boom").header("Accept-Language", "ko"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.errorCode").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.errorMsg").value("요청 처리 중 내부 오류가 발생했습니다."));
    }

    @Test
    @DisplayName("Accept-Language=en 면 내부 오류 메시지를 영어로 응답한다")
    void shouldResolveInternalErrorMessageInEnglish() throws Exception {
        mockMvc.perform(get("/i18n-test/boom").header("Accept-Language", "en"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.errorCode").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.errorMsg").value("An internal error occurred while processing the request."));
    }

    @Test
    @DisplayName("메시지가 누락된 locale(운영 동일 엄격 소스)에서도 핸들러가 깨지지 않고 ApiResponse 500 을 반환한다")
    void shouldNotBreakWhenMessageMissingForLocale() throws Exception {
        // 운영 MessageSource 는 useCodeAsDefaultMessage=false(기본) — 코드 누락 시 NoSuchMessageException 을 던진다.
        // 메시지가 없는 basename 으로 그 상황을 재현: resolve 가 throw 하면 @ExceptionHandler 가 깨져 raw 500(ApiResponse
        // 형식 아님)이 나간다(라이브 재현된 결함). 방어 수정 후엔 항상 ApiResponse 500 을 완성해야 한다.
        final ResourceBundleMessageSource strict = new ResourceBundleMessageSource();
        strict.setBasename("nonexistent-messages-for-test");
        strict.setDefaultEncoding("UTF-8");
        final MockMvc strictMvc = MockMvcBuilders.standaloneSetup(new BoomController())
                .setControllerAdvice(new UiApiExceptionHandler(strict))
                .build();

        strictMvc.perform(get("/i18n-test/boom").header("Accept-Language", "en-US"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("INTERNAL_ERROR"));
    }

    /**
     * 처리되지 않은 예외 경로를 강제하기 위한 테스트 전용 컨트롤러입니다.
     */
    @RestController
    static class BoomController {

        @GetMapping("/i18n-test/boom")
        String boom() {
            throw new RuntimeException("boom");
        }
    }
}

package com.nori.tc.ui.adapters.web.controller;

import com.nori.tc.ui.adapters.web.controller.support.UiApiExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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

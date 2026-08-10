package com.nori.tc.ui.adapters.web.controller;

import com.nori.tc.ui.adapters.web.controller.support.UiApiExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Spring MVC 표준 예외가 의미에 맞는 상태 코드로 나가는지 검증합니다.
 *
 * <p>{@code @ExceptionHandler(Exception.class)} catch-all 은 {@code ExceptionHandlerExceptionResolver}
 * 단계에서 모든 예외를 먼저 잡아, 뒤따르는 Spring 기본 상태 코드 매핑을 아예 실행시키지 않는다.
 * 그 결과 404/405/415/400 이어야 할 응답이 전부 500 + ERROR 로그로 나갔다 — 오타 URL 하나가
 * 서버 장애로 계측되고, ERROR 0 게이트를 오염시킨다(2026-08-10 로컬 실측).</p>
 *
 * <p>응답 본문은 상태 코드와 무관하게 {@code ApiResponse} 계약을 유지해야 한다.</p>
 */
class UiApiExceptionHandlerStandardMvcTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .setControllerAdvice(new UiApiExceptionHandler(UiControllerTestMessageSource.create()))
                .build();
    }

    /**
     * 운영에서 실제로 관측된 형태입니다 — 존재하지 않는 {@code /api/**} 경로는 정적 리소스 핸들러까지
     * 흘러가 {@code NoResourceFoundException} 이 되고, catch-all 이 이를 500 으로 바꿨다.
     */
    @Test
    @DisplayName("NoResourceFoundException 은 404 NOT_FOUND 로 나갑니다")
    void noResourceFoundBecomes404() throws Exception {
        mockMvc.perform(get("/probe/missing-resource").header("Accept-Language", "ko"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"))
                .andExpect(jsonPath("$.errorMsg").value("요청한 리소스를 찾을 수 없습니다."));
    }

    @Test
    @DisplayName("매핑되지 않은 경로는 404 NOT_FOUND 로 나갑니다")
    void noHandlerFoundBecomes404() throws Exception {
        mockMvc.perform(get("/probe/__no_such_path__").header("Accept-Language", "ko"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("허용되지 않은 HTTP 메서드는 405 로 나갑니다")
    void methodNotSupportedBecomes405() throws Exception {
        mockMvc.perform(delete("/probe").header("Accept-Language", "ko"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.errorMsg").value("지원하지 않는 요청입니다."));
    }

    @Test
    @DisplayName("지원하지 않는 Content-Type 은 415 로 나갑니다")
    void mediaTypeNotSupportedBecomes415() throws Exception {
        mockMvc.perform(post("/probe/json")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("not-json")
                        .header("Accept-Language", "ko"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.errorMsg").value("지원하지 않는 요청입니다."));
    }

    @Test
    @DisplayName("필수 쿼리 파라미터 누락은 400 으로 나갑니다")
    void missingRequestParameterBecomes400() throws Exception {
        mockMvc.perform(get("/probe/param").header("Accept-Language", "ko"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.errorMsg").value("요청이 올바르지 않습니다."));
    }

    /**
     * 표준 예외 처리를 붙이면서 기존 도메인 예외 매핑이 가려지지 않는지 확인합니다.
     *
     * <p>{@code ResponseEntityExceptionHandler} 상속 후에도 catch-all 은 20종 밖의 예외에만
     * 걸려야 한다 — 걸림 범위가 뒤집히면 도메인 예외가 500 으로 퇴행한다.</p>
     */
    @Test
    @DisplayName("분류되지 않은 예외는 여전히 500 INTERNAL_ERROR 입니다")
    void unclassifiedExceptionStays500() throws Exception {
        mockMvc.perform(get("/probe/boom").header("Accept-Language", "ko"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.errorCode").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.errorMsg").value("요청 처리 중 내부 오류가 발생했습니다."));
    }

    @RestController
    static class ProbeController {

        @GetMapping("/probe")
        String probe() {
            return "ok";
        }

        @PostMapping(path = "/probe/json", consumes = MediaType.APPLICATION_JSON_VALUE)
        String probeJson(@RequestBody final Map<String, Object> body) {
            return String.valueOf(body.size());
        }

        @GetMapping("/probe/param")
        String probeParam(@RequestParam("eqpId") final String eqpId) {
            return eqpId;
        }

        @GetMapping("/probe/missing-resource")
        String probeMissingResource() throws NoResourceFoundException {
            throw new NoResourceFoundException(HttpMethod.GET, "/api/models/__no_such__",
                    "No static resource api/models/__no_such__.");
        }

        @GetMapping("/probe/boom")
        String probeBoom() {
            throw new IllegalStateException("분류되지 않은 내부 오류");
        }
    }
}

package com.nori.tc.ui.adapters.web.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nori.tc.ui.adapters.web.dto.response.ApiResponse;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Spring Security 인증 실패(401) 응답 포맷을 통일하는 엔트리포인트입니다.
 *
 * <p>적용 배경:</p>
 * <ul>
 *   <li>기존 기본 EntryPoint는 {@code response.sendError(...)} 형태의 단순 텍스트를 반환합니다.</li>
 *   <li>UI Backend API는 전 구간에서 {@link ApiResponse} JSON 규격을 사용하므로,
 *       미인증 응답도 동일 포맷으로 맞춰 프런트/운영 파서의 분기 복잡도를 줄입니다.</li>
 * </ul>
 *
 * <p>최종 응답 예시:</p>
 * <pre>
 * {
 *   "success": false,
 *   "data": null,
 *   "errorCode": "UNAUTHORIZED",
 *   "errorMsg": "인증이 필요합니다."
 * }
 * </pre>
 */
@Component
public class UiAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private static final Logger log = LoggerFactory.getLogger(UiAuthenticationEntryPoint.class);

    /**
     * 401 응답 JSON 직렬화에 사용하는 Jackson ObjectMapper입니다.
     */
    private final ObjectMapper objectMapper;

    /**
     * 의존성을 초기화합니다.
     *
     * @param objectMapper 401 응답 JSON 직렬화기
     */
    public UiAuthenticationEntryPoint(final ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is null");
    }

    /**
     * 인증 실패 시 공통 JSON 포맷으로 401 응답을 기록합니다.
     *
     * @param request       현재 HTTP 요청
     * @param response      현재 HTTP 응답
     * @param authException Spring Security 인증 실패 예외
     * @throws IOException      응답 write 실패 시
     * @throws ServletException 서블릿 레벨 오류 발생 시
     */
    @Override
    public void commence(
            final HttpServletRequest request,
            final HttpServletResponse response,
            final AuthenticationException authException
    ) throws IOException, ServletException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        final String responseBody = objectMapper.writeValueAsString(
                ApiResponse.error("UNAUTHORIZED", "인증이 필요합니다.")
        );
        response.getWriter().write(responseBody);
        response.getWriter().flush();

        if (log.isDebugEnabled()) {
            log.debug("인증 실패 401 응답 반환. uri={}, method={}, reason={}",
                    request.getRequestURI(),
                    request.getMethod(),
                    authException == null ? "N/A" : authException.getMessage());
        }
    }
}

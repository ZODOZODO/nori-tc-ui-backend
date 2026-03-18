package com.nori.tc.ui.adapters.web.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nori.tc.ui.adapters.web.dto.response.ApiResponse;
import com.nori.tc.ui.core.exception.UiAuthenticationException;
import com.nori.tc.ui.core.properties.UiAuthProperties;
import com.nori.tc.ui.core.usecase.ValidateTokenUseCase;
import com.nori.tc.ui.domain.auth.UserPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 인증 쿠키 기반 인증 처리 필터입니다.
 *
 * <p>처리 흐름:</p>
 * <ol>
 *   <li>{@link UiAuthProperties#cookieName()}으로 지정된 쿠키에서 토큰 추출</li>
 *   <li>토큰 없음 → SecurityContext 미설정 후 다음 필터로 통과
 *       (인가 단계에서 익명 요청으로 처리)</li>
 *   <li>{@link ValidateTokenUseCase#execute(String)} 호출:
 *       <ul>
 *         <li>Business Redis 캐시 히트 → 캐시된 UserPrincipal 사용 (DB 미조회)</li>
 *         <li>캐시 미스 → DB 조회 (session + user + permissions) → 캐시 저장</li>
 *         <li>lastSeenAt 업데이트 (캐시 미스 시, 비동기 처리)</li>
 *       </ul>
 *   </li>
 *   <li>토큰 검증 성공 → {@code UsernamePasswordAuthenticationToken}을 SecurityContext에 등록
 *       <ul>
 *         <li>principal = {@link UserPrincipal} (userPk, userId, permissionCodes)</li>
 *         <li>credentials = 원본 쿠키 토큰 (LogoutUseCase에서 토큰 폐기 시 사용)</li>
 *       </ul>
 *   </li>
 *   <li>{@link UiAuthenticationException} → 401 Unauthorized JSON 응답 즉시 반환</li>
 * </ol>
 *
 * <p>NOTE: lastSeenAt 업데이트 실패는 인증 실패로 전파하지 않고 WARN 로그로만 관측합니다.</p>
 */
@Component
public class UiTokenAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(UiTokenAuthenticationFilter.class);

    /**
     * 분산 추적 상관관계 키(MDC)입니다.
     *
     * <p>gateway/business 공통 로그 포맷에서 사용하는 key와 동일하게 맞춥니다.</p>
     */
    private static final String TRACE_ID_MDC_KEY = "traceId";

    /**
     * 클라이언트가 전달한 요청 상관관계 헤더 키입니다.
     */
    private static final String REQUEST_ID_HEADER = "X-Request-Id";

    /**
     * DeferredResult 재디스패치 시 재사용할 인증 객체 request attribute 키입니다.
     */
    private static final String ASYNC_AUTH_ATTRIBUTE =
            UiTokenAuthenticationFilter.class.getName() + ".ASYNC_AUTH";

    /**
     * 요청 단위 traceId를 재디스패치에서도 재사용하기 위한 request attribute 키입니다.
     */
    private static final String REQUEST_TRACE_ID_ATTRIBUTE =
            UiTokenAuthenticationFilter.class.getName() + ".REQUEST_TRACE_ID";

    private final ValidateTokenUseCase validateTokenUseCase;
    private final ObjectMapper objectMapper;
    private final UiAuthProperties authProperties;

    /**
     * 필수 의존성을 초기화합니다.
     *
     * @param validateTokenUseCase 토큰 검증 유스케이스 (캐시 → DB 폴백 처리)
     * @param objectMapper         401 응답 JSON 직렬화용
     * @param authProperties       인증 쿠키 이름/정책 프로퍼티
     */
    public UiTokenAuthenticationFilter(
            final ValidateTokenUseCase validateTokenUseCase,
            final ObjectMapper objectMapper,
            final UiAuthProperties authProperties
    ) {
        this.validateTokenUseCase = Objects.requireNonNull(validateTokenUseCase, "validateTokenUseCase is null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is null");
        this.authProperties = Objects.requireNonNull(authProperties, "authProperties is null");
    }

    /**
     * DeferredResult 비동기 디스패치에서도 토큰 인증 필터를 실행합니다.
     *
     * <p>Spring MVC의 {@code DeferredResult} 완료 시, 서블릿 컨테이너는
     * 응답 전송을 위해 요청을 재디스패치(ASYNC dispatch)합니다.
     * {@link org.springframework.web.filter.OncePerRequestFilter}의 기본 동작은
     * ASYNC 디스패치에서 필터를 건너뛰므로({@code shouldNotFilterAsyncDispatch() = true}),
     * SecurityContext가 비어 있어 Spring Security가 401을 반환하게 됩니다.</p>
     *
     * <p>{@code false}를 반환하여 ASYNC 디스패치 시에도 이 필터가 실행되도록 합니다.
     * 재실행 시 요청 속성에 저장한 인증 객체를 재사용하여 SecurityContext를 복원합니다.
     * 토큰 검증은 Redis 캐시 히트로 처리되므로 성능 영향이 최소화됩니다.</p>
     *
     * @return false — ASYNC 디스패치에서도 이 필터를 실행함
     */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    /**
     * 요청당 1회 실행되는 인증 처리 메서드입니다.
     *
     * @param request     HTTP 요청
     * @param response    HTTP 응답
     * @param filterChain 다음 필터 체인
     */
    @Override
    protected void doFilterInternal(
            final HttpServletRequest request,
            final HttpServletResponse response,
            final FilterChain filterChain
    ) throws ServletException, IOException {
        // 4.5 OPS-02:
        // 요청 단위 traceId를 MDC에 주입해 Web 계층 로그를 요청 단위로 상관분석할 수 있게 만듭니다.
        // - 1순위: X-Request-Id 헤더
        // - 2순위: 동일 요청 재디스패치 시 request attribute 재사용
        // - 3순위: UUID 신규 생성
        final String previousTraceId = MDC.get(TRACE_ID_MDC_KEY);
        final String requestTraceId = resolveRequestTraceId(request);
        MDC.put(TRACE_ID_MDC_KEY, requestTraceId);

        try {
            // DeferredResult 재디스패치(ASYNC)이고 이미 인증 컨텍스트가 있으면 토큰 재검증을 생략합니다.
            // - 최초 REQUEST 디스패치에서 이미 인증을 마친 상태를 그대로 사용합니다.
            // - Redis/DB 중복 조회를 줄여 비동기 응답 경로의 불필요한 오버헤드를 제거합니다.
            if (isAsyncDispatch(request)) {
                final var currentAuthentication = SecurityContextHolder.getContext().getAuthentication();
                if (currentAuthentication != null) {
                    if (log.isTraceEnabled()) {
                        log.trace("비동기 재디스패치 인증 재검증 생략(SecurityContext 재사용). uri={}, method={}",
                                request.getRequestURI(), request.getMethod());
                    }
                    filterChain.doFilter(request, response);
                    return;
                }

                // 일부 컨테이너/설정에서는 ASYNC 디스패치 시 SecurityContext가 비어 있을 수 있으므로
                // 최초 REQUEST 디스패치에서 request attribute로 저장한 인증 객체를 복원합니다.
                final Object asyncAuthentication = request.getAttribute(ASYNC_AUTH_ATTRIBUTE);
                if (asyncAuthentication instanceof UsernamePasswordAuthenticationToken auth) {
                    SecurityContextHolder.getContext().setAuthentication(auth);
                    if (log.isTraceEnabled()) {
                        log.trace("비동기 재디스패치 인증 복원 후 재검증 생략. uri={}, method={}",
                                request.getRequestURI(), request.getMethod());
                    }
                    filterChain.doFilter(request, response);
                    return;
                }
            }

            // 1단계: 인증 쿠키에서 세션 토큰 추출
            final String token = extractTokenFromCookie(request, authProperties.cookieName());
            if (token == null) {
                // 토큰 없음 → SecurityContext 미설정 후 다음 필터로 통과
                // 공개 경로(POST /api/auth/login 등)는 이 경로로 처리됨
                if (log.isTraceEnabled()) {
                    log.trace("인증 쿠키 없음. cookieName={}, uri={}, method={}",
                            authProperties.cookieName(), request.getRequestURI(), request.getMethod());
                }
                filterChain.doFilter(request, response);
                return;
            }

            // 2단계: 토큰 검증 (Business Redis 캐시 → DB 폴백)
            final UserPrincipal principal;
            try {
                principal = validateTokenUseCase.execute(token);
            } catch (UiAuthenticationException e) {
                // 토큰 유효하지 않음 (만료/폐기/미존재/계정 비활성)
                // 즉시 401을 반환하지 않고 SecurityContext 미설정 후 다음 필터로 통과합니다.
                // - permitAll 엔드포인트(/api/auth/csrf, /api/auth/login 등)는 Spring Security가 허용합니다.
                // - 보호 엔드포인트는 인가 단계에서 AuthenticationEntryPoint를 통해 401을 반환합니다.
                // NOTE: 즉시 401을 반환하면 브라우저에 만료 쿠키가 남아 있을 때
                //       permitAll 엔드포인트 접근까지 차단되어 로그인 자체가 불가능해집니다.
                log.warn("유효하지 않은 토큰 - 익명 접근으로 처리합니다. uri={}, method={}, reason={}",
                        request.getRequestURI(), request.getMethod(), e.getMessage());
                filterChain.doFilter(request, response);
                return;
            } catch (Exception e) {
                // 예상치 못한 예외 (Redis/DB 장애 등)
                log.error("토큰 검증 중 예상치 못한 오류. uri={}, method={}",
                        request.getRequestURI(), request.getMethod(), e);
                sendUnauthorized(response, "인증 처리 중 오류가 발생했습니다.");
                return;
            }

            // 3단계: SecurityContext에 인증 정보 등록
            // credentials에 원본 토큰을 보관 → LogoutUseCase에서 토큰 폐기 시 사용
            final UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(principal, token, List.of());
            SecurityContextHolder.getContext().setAuthentication(authentication);
            request.setAttribute(ASYNC_AUTH_ATTRIBUTE, authentication);

            log.debug("인증 처리 완료. userPk={}, userId={}, uri={}, method={}",
                    principal.userPk(), principal.userId(),
                    request.getRequestURI(), request.getMethod());

            // 4단계: 다음 필터로 요청 전달
            filterChain.doFilter(request, response);
        } finally {
            restoreTraceIdMdc(previousTraceId);
        }
    }

    // -------------------------------------------------------------------------
    // 내부 유틸
    // -------------------------------------------------------------------------

    /**
     * 요청 쿠키 배열에서 지정된 이름의 인증 토큰을 추출합니다.
     *
     * @param request HTTP 요청
     * @param cookieName 인증 토큰 쿠키 이름
     * @return 토큰 문자열 (쿠키 미존재/공백 값이면 null)
     */
    private static String extractTokenFromCookie(final HttpServletRequest request, final String cookieName) {
        final Cookie[] cookies = request.getCookies();
        if (cookies == null || cookies.length == 0) {
            return null;
        }

        for (Cookie cookie : cookies) {
            if (!cookieName.equals(cookie.getName())) {
                continue;
            }
            final String token = cookie.getValue() == null ? null : cookie.getValue().trim();
            if (token == null || token.isEmpty()) {
                return null;
            }
            return token;
        }
        return null;
    }

    /**
     * 요청 단위 traceId를 계산하여 request attribute에 저장/재사용합니다.
     *
     * <p>DeferredResult 재디스패치에서도 동일 traceId를 유지해야 로그 상관관계가 유지되므로,
     * 최초 계산 값을 request attribute에 저장한 뒤 이후 디스패치에서 재사용합니다.</p>
     *
     * @param request 현재 HTTP 요청
     * @return MDC에 주입할 traceId
     */
    private static String resolveRequestTraceId(final HttpServletRequest request) {
        final Object existingAttribute = request.getAttribute(REQUEST_TRACE_ID_ATTRIBUTE);
        if (existingAttribute instanceof String traceId && !traceId.isBlank()) {
            return traceId;
        }

        final String headerTraceId = request.getHeader(REQUEST_ID_HEADER);
        final String resolvedTraceId = (headerTraceId == null || headerTraceId.isBlank())
                ? UUID.randomUUID().toString()
                : headerTraceId.trim();

        request.setAttribute(REQUEST_TRACE_ID_ATTRIBUTE, resolvedTraceId);
        return resolvedTraceId;
    }

    /**
     * 필터 종료 시 MDC traceId를 이전 상태로 복구합니다.
     *
     * <p>현재 요청에서 주입한 MDC 값이 다음 요청 로그에 누수되지 않도록
     * 반드시 finally 블록에서 호출해야 합니다.</p>
     *
     * @param previousTraceId 필터 진입 전 MDC traceId 값
     */
    private static void restoreTraceIdMdc(final String previousTraceId) {
        if (previousTraceId == null || previousTraceId.isBlank()) {
            MDC.remove(TRACE_ID_MDC_KEY);
            return;
        }
        MDC.put(TRACE_ID_MDC_KEY, previousTraceId);
    }

    /**
     * 401 Unauthorized JSON 응답을 즉시 반환합니다.
     *
     * <p>Spring Security의 인증 실패 응답과 일관된 형태({@link ApiResponse})로 반환합니다.</p>
     *
     * @param response HTTP 응답
     * @param message  사용자 친화적 오류 메시지
     */
    private void sendUnauthorized(final HttpServletResponse response, final String message) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        final String body = objectMapper.writeValueAsString(
                ApiResponse.error("UNAUTHORIZED", message)
        );
        response.getWriter().write(body);
        response.getWriter().flush();
    }
}

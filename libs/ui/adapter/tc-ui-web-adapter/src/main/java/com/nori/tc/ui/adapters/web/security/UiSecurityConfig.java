package com.nori.tc.ui.adapters.web.security;

import com.nori.tc.ui.core.properties.UiAuthProperties;
import com.nori.tc.ui.domain.auth.UserPrincipal;
import jakarta.servlet.DispatcherType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;
import java.util.Objects;

/**
 * Spring Security 필터 체인 설정 클래스입니다.
 *
 * <p>설정 개요:</p>
 * <ul>
 *   <li>CSRF: 활성화 ({@link CookieCsrfTokenRepository} 사용)</li>
 *   <li>CORS: 활성화 (allowCredentials=true + 프로퍼티 기반 Origin 화이트리스트)</li>
 *   <li>세션: STATELESS (서버 측 HttpSession 사용 안 함)</li>
 *   <li>인증 필터: {@link UiTokenAuthenticationFilter}를
 *       {@link UsernamePasswordAuthenticationFilter} 앞에 등록</li>
 *   <li>공개 경로: POST /api/auth/login, GET /api/auth/csrf, GET /api/actuator/health</li>
 *   <li>그 외 경로: 인증 필수 + DB 기반 URL 인가 ({@link UiApiPermissionCache})</li>
 * </ul>
 *
 * <p>URL 인가 로직 ({@link UiApiPermissionCache#isAuthorized}):</p>
 * <ul>
 *   <li>인증되지 않은 요청 → 401 (AuthenticationEntryPoint: HttpServletResponse.SC_UNAUTHORIZED)</li>
 *   <li>인증된 요청 + URI에 매칭 API 권한 없음 → 차단 (closed by default)</li>
 *   <li>인증된 요청 + URI에 매칭 API 권한 있음 → 사용자가 해당 permCode 보유 시 허용</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(UiAuthProperties.class)
public class UiSecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(UiSecurityConfig.class);

    /**
     * Spring Security 필터 체인을 설정합니다.
     *
     * @param http                  HttpSecurity 빌더
     * @param tokenFilter           인증 쿠키 토큰 인증 필터
     * @param permissionCache       DB 기반 API 권한 캐시
     * @param authenticationEntryPoint 인증 실패(401) 응답 포맷 통일 엔트리포인트
     * @param csrfTokenRepository   CSRF 토큰 저장소 (Cookie 기반)
     * @param corsConfigurationSource CORS 정책 소스
     * @param authProperties        인증 관련 프로퍼티 (로그 보강용)
     * @return 설정된 SecurityFilterChain
     * @throws Exception 설정 오류 시
     */
    @Bean
    public SecurityFilterChain securityFilterChain(
            final HttpSecurity http,
            final UiTokenAuthenticationFilter tokenFilter,
            final UiApiPermissionCache permissionCache,
            final UiAuthenticationEntryPoint authenticationEntryPoint,
            final CsrfTokenRepository csrfTokenRepository,
            @Qualifier("uiCorsConfigurationSource") final CorsConfigurationSource corsConfigurationSource,
            final UiAuthProperties authProperties
    ) throws Exception {
        Objects.requireNonNull(tokenFilter, "tokenFilter is null");
        Objects.requireNonNull(permissionCache, "permissionCache is null");
        Objects.requireNonNull(authenticationEntryPoint, "authenticationEntryPoint is null");
        Objects.requireNonNull(csrfTokenRepository, "csrfTokenRepository is null");
        Objects.requireNonNull(corsConfigurationSource, "corsConfigurationSource is null");
        Objects.requireNonNull(authProperties, "authProperties is null");

        http
                // CSRF 활성화: Double Submit Cookie 패턴을 적용합니다.
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokenRepository)
                        /*
                         * Spring Security 7 기본 핸들러(XOR)는 헤더 토큰을 마스킹 포맷으로 기대합니다.
                         * 본 UI는 쿠키(XSRF-TOKEN)의 원문 토큰을 그대로 헤더(X-XSRF-TOKEN)로 재전송하므로
                         * raw 토큰 비교가 가능한 RequestAttributeHandler를 명시적으로 사용합니다.
                         */
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                )

                // CORS 활성화: 쿠키 기반 인증이므로 allowCredentials=true 정책을 사용합니다.
                .cors(cors -> cors
                        .configurationSource(corsConfigurationSource)
                )

                // 세션 stateless: 서버 측 HttpSession 사용 안 함
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // URL별 인가 설정
                .authorizeHttpRequests(auth -> auth
                        /*
                         * ERROR 디스패치는 원래 상태코드(404/500 등)를 클라이언트로 전달하기 위해
                         * 인증 없이 통과시킵니다. 이를 막으면 /error가 다시 401로 치환되어
                         * 실제 실패 원인이 가려지는 문제가 발생합니다.
                         */
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers("/error").permitAll()
                        // 공개 경로: 인증 없이 허용
                        .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/auth/csrf").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/actuator/health").permitAll()
                        // CORS preflight 요청은 브라우저가 자동 호출하므로 인증 없이 허용합니다.
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        // 그 외 모든 요청: 인증 필수 + DB 기반 URL 인가
                        .anyRequest().access(buildPermissionAuthorizationManager(permissionCache))
                )

                // UiTokenAuthenticationFilter를 UsernamePasswordAuthenticationFilter 앞에 등록
                .addFilterBefore(tokenFilter, UsernamePasswordAuthenticationFilter.class)

                // 미인증(Anonymous) 요청 거부 시 401 반환
                // 기본 AuthenticationEntryPoint(Http403ForbiddenEntryPoint)는 403을 반환하므로
                // REST API 표준에 맞게 401로 명시적으로 설정합니다.
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(authenticationEntryPoint)
                );

        log.info("UiSecurityConfig 초기화 완료. CSRF=활성(cookie), CORS=활성(credentials), 세션=STATELESS, authCookie={}, csrfCookie={}, csrfHeader={}, corsAllowedOriginsCount={}",
                authProperties.cookieName(),
                authProperties.csrfCookieName(),
                authProperties.csrfHeaderName(),
                authProperties.corsAllowedOrigins().size());
        return http.build();
    }

    /**
     * CSRF 토큰 저장소를 Cookie 기반으로 구성합니다.
     *
     * <p>프런트가 CSRF 토큰을 헤더로 재전송할 수 있도록 HttpOnly=false 쿠키를 사용합니다.
     * 쿠키/헤더 이름은 설정 프로퍼티를 통해 환경별로 일관되게 관리합니다.</p>
     *
     * @param authProperties 인증/CSRF 프로퍼티
     * @return Cookie 기반 CSRF 토큰 저장소
     */
    @Bean
    public CsrfTokenRepository uiCsrfTokenRepository(final UiAuthProperties authProperties) {
        Objects.requireNonNull(authProperties, "authProperties is null");

        final CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookieName(authProperties.csrfCookieName());
        repository.setHeaderName(authProperties.csrfHeaderName());
        repository.setCookiePath(authProperties.cookiePath());
        /*
         * 기본 CookieCsrfTokenRepository는 request.isSecure()를 기준으로 Secure를 결정하고,
         * SameSite/Domain은 명시적으로 설정하지 않으면 브라우저/환경별 기본 동작에 의존하게 됩니다.
         * 인증 쿠키와 CSRF 쿠키 정책이 다르면 프런트가 토큰 쿠키를 읽지 못하는 상황이 발생할 수 있으므로
         * 인증 설정(UiAuthProperties)과 동일한 쿠키 정책을 강제합니다.
         */
        repository.setCookieCustomizer(cookieBuilder -> {
            cookieBuilder.secure(authProperties.cookieSecure());
            cookieBuilder.sameSite(authProperties.cookieSameSite());
            if (authProperties.cookieDomain() != null) {
                cookieBuilder.domain(authProperties.cookieDomain());
            }
        });

        log.info("CSRF 저장소 구성 완료. cookieName={}, headerName={}, cookiePath={}, cookieDomain={}, cookieSecure={}, cookieSameSite={}",
                authProperties.csrfCookieName(),
                authProperties.csrfHeaderName(),
                authProperties.cookiePath(),
                authProperties.cookieDomain() == null ? "(none)" : authProperties.cookieDomain(),
                authProperties.cookieSecure(),
                authProperties.cookieSameSite());
        return repository;
    }

    /**
     * CORS 정책 소스를 생성합니다.
     *
     * <p>쿠키 인증을 사용하므로 {@code allowCredentials=true}를 강제하고,
     * 허용 Origin은 프로퍼티 화이트리스트를 그대로 사용합니다.
     * Origin 목록이 비어 있으면 same-origin 요청만 허용되는 상태가 됩니다.</p>
     *
     * @param authProperties 인증 프로퍼티
     * @return 전역 URL(`/**`)에 적용할 CORS 정책 소스
     */
    @Bean
    public CorsConfigurationSource uiCorsConfigurationSource(final UiAuthProperties authProperties) {
        Objects.requireNonNull(authProperties, "authProperties is null");

        final CorsConfiguration corsConfiguration = new CorsConfiguration();
        corsConfiguration.setAllowedOrigins(authProperties.corsAllowedOrigins());
        corsConfiguration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        corsConfiguration.setAllowedHeaders(List.of("*"));
        corsConfiguration.setAllowCredentials(true);
        corsConfiguration.setMaxAge(3600L);

        final UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", corsConfiguration);

        if (authProperties.corsAllowedOrigins().isEmpty()) {
            log.debug("CORS 허용 Origin 목록이 비어 있습니다. same-origin 이외 요청은 브라우저에서 차단됩니다.");
        } else {
            log.info("CORS 정책 구성 완료. allowCredentials=true, allowedOrigins={}", authProperties.corsAllowedOrigins());
        }

        return source;
    }

    /**
     * DB 기반 URL 권한 판단 AuthorizationManager를 생성합니다.
     *
     * <p>판단 로직:</p>
     * <ol>
     *   <li>인증 미완료(Anonymous) → 거부 (403)</li>
     *   <li>principal이 {@link UserPrincipal}이 아님 → 거부 (비정상 상태)</li>
     *   <li>{@link UiApiPermissionCache#isAuthorized} 호출 → 결과에 따라 허용/거부</li>
     * </ol>
     *
     * @param permissionCache API 권한 캐시
     * @return AuthorizationManager 인스턴스
     */
    private AuthorizationManager<RequestAuthorizationContext> buildPermissionAuthorizationManager(
            final UiApiPermissionCache permissionCache
    ) {
        return (authenticationSupplier, context) -> {
            final var authentication = authenticationSupplier.get();

            // 인증 미완료 → 거부
            if (authentication == null || !authentication.isAuthenticated()) {
                log.debug("미인증 요청 거부. uri={}", context.getRequest().getRequestURI());
                return new AuthorizationDecision(false);
            }
            if (authentication instanceof AnonymousAuthenticationToken) {
                log.debug("익명 사용자 요청 거부. uri={}", context.getRequest().getRequestURI());
                return new AuthorizationDecision(false);
            }

            // principal 타입 확인
            final Object principal = authentication.getPrincipal();
            if (!(principal instanceof UserPrincipal userPrincipal)) {
                // UiTokenAuthenticationFilter를 거치지 않은 비정상 경로
                log.warn("principal 타입 불일치. principalType={}, uri={}",
                        principal == null ? "null" : principal.getClass().getName(),
                        context.getRequest().getRequestURI());
                return new AuthorizationDecision(false);
            }

            // DB 기반 URL 인가 판단
            final String httpMethod = context.getRequest().getMethod();
            final String requestUri = context.getRequest().getRequestURI();
            final boolean authorized = permissionCache.isAuthorized(userPrincipal, httpMethod, requestUri);

            return new AuthorizationDecision(authorized);
        };
    }
}

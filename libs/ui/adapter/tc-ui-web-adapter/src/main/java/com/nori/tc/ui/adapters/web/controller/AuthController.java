package com.nori.tc.ui.adapters.web.controller;

import com.nori.tc.ui.adapters.web.dto.request.LoginRequest;
import com.nori.tc.ui.adapters.web.dto.response.ApiResponse;
import com.nori.tc.ui.adapters.web.dto.response.LoginResponse;
import com.nori.tc.ui.adapters.web.dto.response.MeResponse;
import com.nori.tc.ui.core.exception.UiAuthenticationException;
import com.nori.tc.ui.core.properties.UiAuthProperties;
import com.nori.tc.ui.core.usecase.LoginUseCase;
import com.nori.tc.ui.core.usecase.LogoutUseCase;
import com.nori.tc.ui.domain.auth.AuthToken;
import com.nori.tc.ui.domain.auth.UserPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Objects;

/**
 * 인증(Authentication) REST API 컨트롤러입니다.
 *
 * <p>제공 엔드포인트:</p>
 * <ul>
 *   <li>POST /api/auth/login  — 사용자 ID/비밀번호로 로그인, 세션 토큰 쿠키 발급</li>
 *   <li>POST /api/auth/logout — 현재 세션 토큰 폐기 + 인증 쿠키 삭제</li>
 *   <li>GET  /api/auth/me     — 현재 인증된 사용자 정보 조회</li>
 *   <li>GET  /api/auth/csrf   — CSRF 토큰 쿠키 발급 트리거</li>
 * </ul>
 *
 * <p>인증 흐름:</p>
 * <p>로그인 성공 시 발급된 토큰은 {@code Set-Cookie} 응답 헤더로만 전달되며,
 * 이후 보호 API는 쿠키 기반으로 인증됩니다.
 * 로그아웃 시 SecurityContext의 credentials에서 원본 토큰을 추출해 폐기하고,
 * 삭제 쿠키를 내려 브라우저의 인증 쿠키를 즉시 만료시킵니다.</p>
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final LoginUseCase loginUseCase;
    private final LogoutUseCase logoutUseCase;
    private final UiAuthProperties authProperties;
    private final CsrfTokenRepository csrfTokenRepository;

    /**
     * 필수 의존성을 초기화합니다.
     *
     * @param loginUseCase  로그인(세션 토큰 발급) 유스케이스
     * @param logoutUseCase 로그아웃(세션 토큰 폐기) 유스케이스
     * @param authProperties 인증 쿠키 정책 프로퍼티
     * @param csrfTokenRepository CSRF 토큰 저장소 (cookie 기반)
     */
    public AuthController(
            final LoginUseCase loginUseCase,
            final LogoutUseCase logoutUseCase,
            final UiAuthProperties authProperties,
            final CsrfTokenRepository csrfTokenRepository
    ) {
        this.loginUseCase = Objects.requireNonNull(loginUseCase, "loginUseCase is null");
        this.logoutUseCase = Objects.requireNonNull(logoutUseCase, "logoutUseCase is null");
        this.authProperties = Objects.requireNonNull(authProperties, "authProperties is null");
        this.csrfTokenRepository = Objects.requireNonNull(csrfTokenRepository, "csrfTokenRepository is null");
    }

    /**
     * 사용자 ID와 비밀번호로 로그인하여 세션 토큰을 발급합니다.
     *
     * <p>UiSecurityConfig에서 이 경로는 permitAll()로 공개 접근을 허용합니다.
     * 인증 성공 시 응답 본문에는 최소한의 세션 메타데이터만 반환하고,
     * 실제 인증 토큰은 HttpOnly 쿠키로 전달합니다.</p>
     *
     * @param request 로그인 요청 DTO (userId, password 필수)
     * @return 200 OK + 로그인 메타데이터 + Set-Cookie | 401 인증 실패
     */
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(
            @Valid @RequestBody final LoginRequest request
    ) {
        log.info("로그인 요청. userId={}", request.userId());

        try {
            final AuthToken authToken = loginUseCase.execute(request.userId(), request.password());

            final LoginResponse response = new LoginResponse(
                    authToken.userPk(),
                    authToken.issuedAt(),
                    authToken.expiresAt()
            );
            final long authCookieMaxAgeSeconds = resolveAuthCookieMaxAgeSeconds(authToken);
            final ResponseCookie authCookie = buildAuthCookie(authToken.token(), authCookieMaxAgeSeconds);

            log.info("로그인 성공. userId={}, userPk={}, expiresAt={}, cookieName={}",
                    request.userId(), authToken.userPk(), authToken.expiresAt(), authProperties.cookieName());
            if (log.isDebugEnabled()) {
                log.debug("로그인 쿠키 발급 정책 적용. cookieName={}, path={}, domain={}, secure={}, sameSite={}, maxAgeSeconds={}",
                        authProperties.cookieName(),
                        authProperties.cookiePath(),
                        authProperties.cookieDomain() == null ? "(none)" : authProperties.cookieDomain(),
                        authProperties.cookieSecure(),
                        authProperties.cookieSameSite(),
                        authCookieMaxAgeSeconds);
            }

            return ResponseEntity.ok()
                    .header(HttpHeaders.SET_COOKIE, authCookie.toString())
                    .body(ApiResponse.success(response));

        } catch (UiAuthenticationException e) {
            // 사용자 미존재, 비밀번호 불일치, 계정 비활성 등 인증 실패
            log.warn("로그인 실패. userId={}, reason={}", request.userId(), e.getMessage());
            return ResponseEntity
                    .status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error("UNAUTHORIZED", e.getMessage()));
        }
    }

    /**
     * 현재 세션 토큰을 폐기하여 로그아웃 처리합니다.
     *
     * <p>UiTokenAuthenticationFilter가 SecurityContext의 credentials에 원본 토큰을
     * 저장해 두므로, 요청 쿠키를 다시 파싱할 필요 없이 직접 추출합니다.
     * DB와 Redis 양쪽에서 토큰을 즉시 무효화한 뒤, 응답에 삭제 쿠키를 내려
     * 브라우저 쿠키를 즉시 만료시킵니다.</p>
     *
     * @return 200 OK (이미 폐기된 토큰이어도 멱등성 보장)
     */
    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout() {
        final Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null) {
            log.warn("로그아웃 요청 거부 - SecurityContext 인증 정보 없음");
            return unauthorizedLogout("인증 정보가 유효하지 않습니다.");
        }

        // UiTokenAuthenticationFilter에서 credentials에 원본 토큰(String)을 보관합니다.
        // 캐스팅 예외(ClassCastException) 방지를 위해 instanceof 패턴 매칭을 사용합니다.
        if (!(authentication.getCredentials() instanceof String token) || token.isBlank()) {
            log.warn("로그아웃 요청 거부 - credentials 타입 불일치. actualType={}",
                    authentication.getCredentials() == null
                            ? "null"
                            : authentication.getCredentials().getClass().getName());
            return unauthorizedLogout("인증 정보가 유효하지 않습니다.");
        }

        if (!(authentication.getPrincipal() instanceof UserPrincipal principal)) {
            log.warn("로그아웃 요청 거부 - principal 타입 불일치. actualType={}",
                    authentication.getPrincipal() == null
                            ? "null"
                            : authentication.getPrincipal().getClass().getName());
            return unauthorizedLogout("인증 정보가 유효하지 않습니다.");
        }

        final String tokenPrefix = token.substring(0, Math.min(8, token.length()));
        log.info("로그아웃 요청. token={}..., userPk={}",
                tokenPrefix, principal.userPk());

        logoutUseCase.execute(token);

        // SecurityContext 초기화: 이후 필터 체인에서 인증 정보 노출 방지
        SecurityContextHolder.clearContext();
        final ResponseCookie deleteCookie = buildDeleteAuthCookie();

        log.info("로그아웃 완료. token={}...", tokenPrefix);
        if (log.isDebugEnabled()) {
            log.debug("로그아웃 삭제 쿠키 발급. cookieName={}, path={}, domain={}, secure={}, sameSite={}, maxAge=0",
                    authProperties.cookieName(),
                    authProperties.cookiePath(),
                    authProperties.cookieDomain() == null ? "(none)" : authProperties.cookieDomain(),
                    authProperties.cookieSecure(),
                    authProperties.cookieSameSite());
        }

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, deleteCookie.toString())
                .body(ApiResponse.success(null));
    }

    /**
     * 현재 인증된 사용자의 식별 정보와 권한 목록을 반환합니다.
     *
     * <p>SecurityContext의 principal에서 UserPrincipal을 추출합니다.
     * 클라이언트에서 메뉴 노출 여부나 버튼 활성화 판단에 활용합니다.</p>
     *
     * @return 200 OK + 현재 사용자 정보 (userPk, userId, permissionCodes)
     */
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<MeResponse>> me() {
        final Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            log.warn("현재 사용자 정보 조회 거부 - SecurityContext 인증 정보 없음");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error("UNAUTHORIZED", "인증 정보가 유효하지 않습니다."));
        }

        // 강제 캐스팅 대신 instanceof 패턴 매칭으로 타입 안정성을 보장합니다.
        if (!(authentication.getPrincipal() instanceof UserPrincipal principal)) {
            log.warn("현재 사용자 정보 조회 거부 - principal 타입 불일치. actualType={}",
                    authentication.getPrincipal() == null
                            ? "null"
                            : authentication.getPrincipal().getClass().getName());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error("UNAUTHORIZED", "인증 정보가 유효하지 않습니다."));
        }

        log.debug("현재 사용자 정보 조회. userPk={}, userId={}",
                principal.userPk(), principal.userId());

        final MeResponse response = new MeResponse(
                principal.userPk(),
                principal.userId(),
                principal.permissionCodes()
        );

        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * CSRF 토큰 발급을 트리거하는 엔드포인트입니다.
     *
     * <p>Spring Security의 CsrfFilter가 {@link CsrfToken}을 요청 컨텍스트에 넣을 때
     * CookieCsrfTokenRepository가 CSRF 쿠키를 함께 발급합니다.
     * 프런트는 초기 진입 시 이 엔드포인트를 호출해 CSRF 쿠키를 확보한 뒤,
     * 상태 변경 요청에 CSRF 헤더를 포함해야 합니다.</p>
     *
     * @param request 현재 HTTP 요청
     * @param response 현재 HTTP 응답
     * @return 200 OK (응답 본문은 단순 성공; 실제 토큰 전달은 쿠키 계약을 따름)
     */
    @GetMapping("/csrf")
    public ResponseEntity<ApiResponse<Void>> csrf(
            final HttpServletRequest request,
            final HttpServletResponse response
    ) {
        /*
         * CsrfTokenRepository는 지연(lazy) 방식으로 토큰을 생성/저장합니다.
         * loadDeferredToken(...).get()을 호출하면 "토큰 조회 + 저장(saveToken)"이 보장되며,
         * CookieCsrfTokenRepository를 사용하는 현재 구조에서는 Set-Cookie 응답 헤더가 즉시 생성됩니다.
         * 프런트는 /api/auth/csrf 호출 직후 document.cookie에서 XSRF-TOKEN을 읽기 때문에
         * 이 트리거가 누락되면 로그인 이전 단계에서 CSRF_TOKEN_MISSING으로 실패할 수 있습니다.
         */
        final CsrfToken csrfToken = csrfTokenRepository.loadDeferredToken(request, response).get();
        final String resolvedToken = csrfToken.getToken();
        if (log.isTraceEnabled()) {
            log.trace("CSRF 토큰 발급 완료. cookieName={}, headerName={}, parameterName={}, tokenLength={}",
                    authProperties.csrfCookieName(),
                    csrfToken.getHeaderName(),
                    csrfToken.getParameterName(),
                    resolvedToken.length());
        }
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    /**
     * 로그아웃 실패(401) 응답을 생성하는 헬퍼입니다.
     *
     * <p>인증 정보가 비정상인 경우에도 브라우저에 남아 있는 쿠키를 정리하기 위해
     * 삭제 쿠키를 함께 내려 클라이언트 상태를 일관되게 맞춥니다.</p>
     *
     * @param message 오류 메시지
     * @return 401 Unauthorized 응답
     */
    private ResponseEntity<ApiResponse<Void>> unauthorizedLogout(final String message) {
        final ResponseCookie deleteCookie = buildDeleteAuthCookie();
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.SET_COOKIE, deleteCookie.toString())
                .body(ApiResponse.error("UNAUTHORIZED", message));
    }

    /**
     * 로그인 성공 시 사용할 인증 쿠키를 생성합니다.
     *
     * <p>토큰은 HttpOnly 속성으로 발급하여 JavaScript 접근을 차단하고,
     * Secure/SameSite/Path/Domain 정책은 {@link UiAuthProperties}를 그대로 반영합니다.</p>
     *
     * @param token 인증 토큰 원문
     * @param maxAgeSeconds 쿠키 유효 시간(초)
     * @return Set-Cookie 헤더로 직렬화 가능한 ResponseCookie
     */
    private ResponseCookie buildAuthCookie(final String token, final long maxAgeSeconds) {
        final ResponseCookie.ResponseCookieBuilder builder = ResponseCookie
                .from(authProperties.cookieName(), token)
                .httpOnly(true)
                .secure(authProperties.cookieSecure())
                .path(authProperties.cookiePath())
                .sameSite(authProperties.cookieSameSite())
                .maxAge(maxAgeSeconds);
        if (authProperties.cookieDomain() != null) {
            builder.domain(authProperties.cookieDomain());
        }
        return builder.build();
    }

    /**
     * 로그아웃 시 브라우저의 인증 쿠키를 즉시 제거하기 위한 삭제 쿠키를 생성합니다.
     *
     * <p>삭제가 확실히 적용되도록 Path/Domain/SameSite/Secure 정책을 로그인 쿠키와 동일하게 맞추고,
     * Max-Age를 0으로 설정해 즉시 만료시킵니다.</p>
     *
     * @return 쿠키 삭제용 ResponseCookie
     */
    private ResponseCookie buildDeleteAuthCookie() {
        final ResponseCookie.ResponseCookieBuilder builder = ResponseCookie
                .from(authProperties.cookieName(), "")
                .httpOnly(true)
                .secure(authProperties.cookieSecure())
                .path(authProperties.cookiePath())
                .sameSite(authProperties.cookieSameSite())
                .maxAge(0);
        if (authProperties.cookieDomain() != null) {
            builder.domain(authProperties.cookieDomain());
        }
        return builder.build();
    }

    /**
     * 인증 토큰의 발급/만료 시각을 기준으로 인증 쿠키 max-age(초)를 계산합니다.
     *
     * <p>이 값은 브라우저 보관 수명으로 사용되며, 음수 값이 되지 않도록 최소 0으로 보정합니다.</p>
     *
     * @param authToken 로그인 유스케이스가 반환한 토큰 도메인 객체
     * @return 쿠키 max-age(초)
     */
    private static long resolveAuthCookieMaxAgeSeconds(final AuthToken authToken) {
        return Math.max(0L, Duration.between(authToken.issuedAt(), authToken.expiresAt()).getSeconds());
    }
}

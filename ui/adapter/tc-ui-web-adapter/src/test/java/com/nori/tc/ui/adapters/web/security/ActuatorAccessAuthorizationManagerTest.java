package com.nori.tc.ui.adapters.web.security;

import com.nori.tc.ui.domain.auth.UserPrincipal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * W1-3 (AC-W1-3) — Actuator 민감 endpoint(prometheus/info/metrics) 인가 규칙 검증.
 *
 * <p>{@link UiSecurityConfig} 가 prometheus/info/metrics 경로에 적용하는
 * {@link ActuatorAccessAuthorizationManager} 의 "내부망 IP 화이트리스트 AND 토큰 인증" 결정을
 * 직접 검증한다(본 모듈 기존 패턴 {@code UiApiPermissionGatingTest} 와 동일하게 AuthorizationManager
 * 결정 단위를 직접 assert). 거부 결정은 SecurityFilterChain 에서 미인증 시 401
 * ({@link UiAuthenticationEntryPoint}), 인증됐으나 IP 미허용 시 403 으로 응답한다.</p>
 *
 * <p>health/liveness/readiness probe 의 무인증 허용은 {@link UiSecurityConfig} 의
 * {@code requestMatchers("/api/actuator/health", ...).permitAll()} 규칙(본 판단기 비대상)으로
 * 유지되며, 본 판단기는 health 경로에 매핑되지 않는다.</p>
 */
class ActuatorAccessAuthorizationManagerTest {

    private static final List<String> ALLOWED_CIDRS = List.of("10.0.0.0/8", "127.0.0.1/32");
    private static final String INTERNAL_IP = "10.1.2.3";
    private static final String EXTERNAL_IP = "203.0.113.5";

    private final ActuatorAccessAuthorizationManager manager =
            new ActuatorAccessAuthorizationManager(ALLOWED_CIDRS);

    @Test
    @DisplayName("내부망 IP + 토큰 인증(UserPrincipal) → 허용(200)")
    void internalIpWithAuthenticatedUserIsGranted() {
        final AuthorizationResult decision = manager.authorize(
                authenticatedUser(),
                contextFrom(INTERNAL_IP));

        assertTrue(decision.isGranted(), "내부망 IP + 인증 완료 → prometheus 접근 허용");
    }

    @Test
    @DisplayName("내부망 IP + 미인증(anonymous) → 거부(401)")
    void internalIpWithoutAuthenticationIsDenied() {
        final AuthorizationResult decision = manager.authorize(
                anonymous(),
                contextFrom(INTERNAL_IP));

        assertFalse(decision.isGranted(), "내부망 IP 라도 토큰 미인증이면 거부(→401)");
    }

    @Test
    @DisplayName("내부망 IP + Authentication null → 거부(401)")
    void internalIpWithNullAuthenticationIsDenied() {
        final AuthorizationResult decision = manager.authorize(
                () -> null,
                contextFrom(INTERNAL_IP));

        assertFalse(decision.isGranted(), "Authentication 부재 → 거부(→401)");
    }

    @Test
    @DisplayName("외부 IP + 토큰 인증(UserPrincipal) → 거부(403)")
    void externalIpWithAuthenticatedUserIsDenied() {
        final AuthorizationResult decision = manager.authorize(
                authenticatedUser(),
                contextFrom(EXTERNAL_IP));

        assertFalse(decision.isGranted(), "인증됐어도 화이트리스트 외 IP 면 거부(→403)");
    }

    @Test
    @DisplayName("외부 IP + 미인증 → 거부")
    void externalIpWithoutAuthenticationIsDenied() {
        final AuthorizationResult decision = manager.authorize(
                anonymous(),
                contextFrom(EXTERNAL_IP));

        assertFalse(decision.isGranted(), "IP·인증 모두 불충족 → 거부");
    }

    @Test
    @DisplayName("내부망 IP + 인증됐으나 principal 이 UserPrincipal 아님 → 거부")
    void internalIpWithNonUserPrincipalIsDenied() {
        final Supplier<Authentication> nonUserPrincipal = () -> new UsernamePasswordAuthenticationToken(
                "raw-string-principal", null, AuthorityUtils.NO_AUTHORITIES);

        final AuthorizationResult decision = manager.authorize(
                nonUserPrincipal,
                contextFrom(INTERNAL_IP));

        assertFalse(decision.isGranted(), "토큰 필터를 거치지 않은 비정상 principal → 거부");
    }

    private static Supplier<Authentication> authenticatedUser() {
        final UserPrincipal principal = new UserPrincipal(1L, "admin", Set.of("DLQ_READ"));
        final Authentication authentication = new UsernamePasswordAuthenticationToken(
                principal, null, AuthorityUtils.createAuthorityList("ROLE_USER"));
        return () -> authentication;
    }

    private static Supplier<Authentication> anonymous() {
        final Authentication authentication = new AnonymousAuthenticationToken(
                "anon-key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));
        return () -> authentication;
    }

    private static RequestAuthorizationContext contextFrom(final String remoteAddr) {
        final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/actuator/prometheus");
        request.setRemoteAddr(remoteAddr);
        return new RequestAuthorizationContext(request);
    }
}

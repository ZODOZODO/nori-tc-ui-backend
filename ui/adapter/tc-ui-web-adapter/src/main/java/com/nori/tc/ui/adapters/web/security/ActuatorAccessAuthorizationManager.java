package com.nori.tc.ui.adapters.web.security;

import com.nori.tc.ui.domain.auth.UserPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.util.matcher.IpAddressMatcher;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Actuator 민감 endpoint(prometheus/info/metrics) 전용 인가 판단기입니다.
 *
 * <p>허용 조건은 두 가지를 <b>모두</b> 충족해야 합니다(AND):</p>
 * <ol>
 *   <li>요청 출발지 IP 가 내부망 허용 CIDR 목록에 포함됨</li>
 *   <li>토큰 인증이 완료되어 principal 이 {@link UserPrincipal} 임</li>
 * </ol>
 *
 * <p>둘 중 하나라도 불충족하면 거부합니다. 미인증 거부는
 * {@link UiAuthenticationEntryPoint} 를 통해 401 로, 인증됐으나 IP 미허용으로 인한 거부는
 * Spring Security 기본 동작상 403 으로 응답합니다.</p>
 *
 * <p>health/liveness/readiness probe 는 본 판단기 대상이 아니며
 * {@link UiSecurityConfig} 에서 무인증(permitAll)으로 유지됩니다(k8s probe 용).</p>
 */
final class ActuatorAccessAuthorizationManager
        implements AuthorizationManager<RequestAuthorizationContext> {

    private static final Logger log = LoggerFactory.getLogger(ActuatorAccessAuthorizationManager.class);

    private final List<IpAddressMatcher> allowedIpMatchers;

    /**
     * 허용 CIDR 목록으로 IP 매처를 구성합니다.
     *
     * @param allowedCidrs 내부망 허용 CIDR 목록(비어 있을 수 없음 — 빈 경우 모든 IP 거부)
     */
    ActuatorAccessAuthorizationManager(final List<String> allowedCidrs) {
        Objects.requireNonNull(allowedCidrs, "allowedCidrs is null");
        this.allowedIpMatchers = allowedCidrs.stream()
                .map(IpAddressMatcher::new)
                .toList();
        log.info("ActuatorAccessAuthorizationManager 초기화. allowedCidrs={}", allowedCidrs);
    }

    @Override
    public AuthorizationResult authorize(
            final Supplier<? extends Authentication> authenticationSupplier,
            final RequestAuthorizationContext context
    ) {
        final String remoteAddr = context.getRequest().getRemoteAddr();

        // 1) 내부망 IP 화이트리스트 검사
        if (!isIpAllowed(remoteAddr)) {
            log.debug("actuator 접근 거부 - 화이트리스트 외 IP. remoteAddr={}, uri={}",
                    remoteAddr, context.getRequest().getRequestURI());
            return new AuthorizationDecision(false);
        }

        // 2) 토큰 인증 검사
        final Authentication authentication = authenticationSupplier.get();
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            log.debug("actuator 접근 거부 - 미인증 요청. remoteAddr={}, uri={}",
                    remoteAddr, context.getRequest().getRequestURI());
            return new AuthorizationDecision(false);
        }
        if (!(authentication.getPrincipal() instanceof UserPrincipal)) {
            log.warn("actuator 접근 거부 - principal 타입 불일치. principalType={}, uri={}",
                    authentication.getPrincipal() == null
                            ? "null" : authentication.getPrincipal().getClass().getName(),
                    context.getRequest().getRequestURI());
            return new AuthorizationDecision(false);
        }

        return new AuthorizationDecision(true);
    }

    private boolean isIpAllowed(final String remoteAddr) {
        if (remoteAddr == null) {
            return false;
        }
        return allowedIpMatchers.stream().anyMatch(matcher -> matcher.matches(remoteAddr));
    }
}

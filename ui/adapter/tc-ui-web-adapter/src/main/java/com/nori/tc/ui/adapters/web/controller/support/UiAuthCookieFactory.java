package com.nori.tc.ui.adapters.web.controller.support;

import com.nori.tc.ui.core.properties.UiAuthProperties;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * 인증 쿠키 생성 정책을 담당하는 팩토리입니다.
 *
 * <p>로그인/로그아웃 쿠키 규칙을 컨트롤러에서 분리해 재사용 가능한 정책 단위로 유지합니다.</p>
 */
@Component
public class UiAuthCookieFactory {

    private final UiAuthProperties authProperties;

    public UiAuthCookieFactory(final UiAuthProperties authProperties) {
        this.authProperties = Objects.requireNonNull(authProperties, "authProperties is null");
    }

    public ResponseCookie buildAuthCookie(
            final String token,
            final OffsetDateTime issuedAt,
            final OffsetDateTime expiresAt
    ) {
        final long maxAgeSeconds = Math.max(0L, Duration.between(issuedAt, expiresAt).getSeconds());
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

    public ResponseCookie buildDeleteAuthCookie() {
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
}

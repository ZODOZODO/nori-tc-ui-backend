package com.nori.tc.ui.core.properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Locale;

/**
 * UI 인증 관련 설정 바인딩 클래스입니다.
 *
 * <p>바인딩 대상 (config/tc-ui-backend.properties):</p>
 * <pre>
 * tc.ui.backend.auth.session-ttl-hours=8
 * tc.ui.backend.auth.token-cache-ttl-seconds=300
 * tc.ui.backend.auth.cookie-name=TC_UI_AUTH
 * tc.ui.backend.auth.cookie-path=/
 * tc.ui.backend.auth.cookie-domain=
 * tc.ui.backend.auth.cookie-secure=true
 * tc.ui.backend.auth.cookie-same-site=None
 * tc.ui.backend.auth.csrf-cookie-name=XSRF-TOKEN
 * tc.ui.backend.auth.csrf-header-name=X-XSRF-TOKEN
 * tc.ui.backend.auth.cors-allowed-origins=https://ui.example.com,https://admin.example.com
 * </pre>
 *
 * <p>사용처:</p>
 * <ul>
 *   <li>{@code sessionTtlHours}: LoginUseCase에서 세션 만료 시각(expiresAt) 계산</li>
 *   <li>{@code tokenCacheTtlSeconds}: tc-ui-redis-adapter의 UiSessionCacheService에서
 *       Redis TTL 설정</li>
 *   <li>{@code cookieName/cookiePath/cookieDomain/cookieSecure/cookieSameSite}:
 *       Web Adapter에서 HttpOnly 인증 쿠키 발급 정책 구성</li>
 *   <li>{@code csrfCookieName/csrfHeaderName}: CSRF 토큰 저장/전달 계약 구성</li>
 *   <li>{@code corsAllowedOrigins}: 쿠키 기반 CORS 허용 Origin 화이트리스트 구성</li>
 * </ul>
 *
 * @param sessionTtlHours      세션 유효 시간 (단위: 시간, 기본값 8)
 * @param tokenCacheTtlSeconds Redis 토큰 캐시 TTL (단위: 초, 기본값 300)
 * @param cookieName           인증 쿠키 이름 (기본값 TC_UI_AUTH)
 * @param cookiePath           인증 쿠키 Path (기본값 /)
 * @param cookieDomain         인증 쿠키 Domain (옵션, 비어 있으면 미지정)
 * @param cookieSecure         인증 쿠키 Secure 속성 (기본값 true)
 * @param cookieSameSite       인증 쿠키 SameSite 속성 (Strict/Lax/None, 기본값 None)
 * @param csrfCookieName       CSRF 쿠키 이름 (기본값 XSRF-TOKEN)
 * @param csrfHeaderName       CSRF 헤더 이름 (기본값 X-XSRF-TOKEN)
 * @param corsAllowedOrigins   CORS 허용 Origin 목록 (비어 있으면 빈 목록)
 */
@ConfigurationProperties(prefix = "tc.ui.backend.auth")
public record UiAuthProperties(
        int sessionTtlHours,
        int tokenCacheTtlSeconds,
        String cookieName,
        String cookiePath,
        String cookieDomain,
        Boolean cookieSecure,
        String cookieSameSite,
        String csrfCookieName,
        String csrfHeaderName,
        List<String> corsAllowedOrigins
) {

    /**
     * 속성 바인딩/기본값 적용 로그를 위한 로거입니다.
     */
    private static final Logger log = LoggerFactory.getLogger(UiAuthProperties.class);

    /**
     * 세션 TTL 기본값(시간)입니다.
     */
    private static final int DEFAULT_SESSION_TTL_HOURS = 8;

    /**
     * 토큰 캐시 TTL 기본값(초)입니다.
     */
    private static final int DEFAULT_TOKEN_CACHE_TTL_SECONDS = 300;

    /**
     * 인증 쿠키 이름 기본값입니다.
     */
    private static final String DEFAULT_COOKIE_NAME = "TC_UI_AUTH";

    /**
     * 인증 쿠키 Path 기본값입니다.
     */
    private static final String DEFAULT_COOKIE_PATH = "/";

    /**
     * 인증 쿠키 Secure 기본값입니다.
     */
    private static final boolean DEFAULT_COOKIE_SECURE = true;

    /**
     * 인증 쿠키 SameSite 기본값입니다.
     */
    private static final String DEFAULT_COOKIE_SAME_SITE = "None";

    /**
     * CSRF 쿠키 이름 기본값입니다.
     */
    private static final String DEFAULT_CSRF_COOKIE_NAME = "XSRF-TOKEN";

    /**
     * CSRF 헤더 이름 기본값입니다.
     */
    private static final String DEFAULT_CSRF_HEADER_NAME = "X-XSRF-TOKEN";

    /**
     * 기본값을 적용하는 compact constructor입니다.
     *
     * <p>설정 파일에 값이 없을 경우 0이 바인딩되는 것을 방지하기 위해
     * 양수 기본값을 강제합니다.</p>
     */
    public UiAuthProperties {
        if (sessionTtlHours <= 0) {
            log.warn("인증 설정 보정 - session-ttl-hours 값이 0 이하입니다. 입력값={}, 적용값={}",
                    sessionTtlHours, DEFAULT_SESSION_TTL_HOURS);
            sessionTtlHours = DEFAULT_SESSION_TTL_HOURS;
        }
        if (tokenCacheTtlSeconds <= 0) {
            log.warn("인증 설정 보정 - token-cache-ttl-seconds 값이 0 이하입니다. 입력값={}, 적용값={}",
                    tokenCacheTtlSeconds, DEFAULT_TOKEN_CACHE_TTL_SECONDS);
            tokenCacheTtlSeconds = DEFAULT_TOKEN_CACHE_TTL_SECONDS;
        }

        cookieName = normalizeRequiredText(
                "tc.ui.backend.auth.cookie-name",
                cookieName,
                DEFAULT_COOKIE_NAME
        );
        cookiePath = normalizeCookiePath(cookiePath);
        cookieDomain = normalizeOptionalText(cookieDomain);

        if (cookieSecure == null) {
            log.warn("인증 설정 보정 - cookie-secure 값이 누락되어 기본값을 적용합니다. 적용값={}",
                    DEFAULT_COOKIE_SECURE);
            cookieSecure = DEFAULT_COOKIE_SECURE;
        }

        cookieSameSite = normalizeSameSite(cookieSameSite);
        csrfCookieName = normalizeRequiredText(
                "tc.ui.backend.auth.csrf-cookie-name",
                csrfCookieName,
                DEFAULT_CSRF_COOKIE_NAME
        );
        csrfHeaderName = normalizeRequiredText(
                "tc.ui.backend.auth.csrf-header-name",
                csrfHeaderName,
                DEFAULT_CSRF_HEADER_NAME
        );
        corsAllowedOrigins = normalizeCorsAllowedOrigins(corsAllowedOrigins);

        /*
         * SameSite=None 정책은 브라우저 정책상 Secure=true와 함께 사용해야 합니다.
         * 잘못된 조합은 런타임에서 쿠키가 차단될 수 있으므로 운영 로그에 명확히 남깁니다.
         */
        if ("None".equals(cookieSameSite) && !cookieSecure) {
            log.warn("인증 설정 점검 필요 - SameSite=None + Secure=false 조합입니다. "
                            + "일부 브라우저에서 쿠키 전송이 차단될 수 있습니다.");
        }

        /*
         * CORS Origin이 비어 있으면 cross-origin 쿠키 요청이 차단될 가능성이 큽니다.
         * 단, same-origin 배포에서는 정상일 수 있으므로 WARN 대신 DEBUG로 남깁니다.
         */
        if (corsAllowedOrigins.isEmpty()) {
            log.debug("인증 설정 점검 - cors-allowed-origins가 비어 있습니다. same-origin 배포가 아니라면 설정이 필요합니다.");
        }

        log.info("UiAuthProperties 검증 완료. sessionTtlHours={}, tokenCacheTtlSeconds={}, cookieName={}, "
                        + "cookiePath={}, cookieDomain={}, cookieSecure={}, cookieSameSite={}, csrfCookieName={}, "
                        + "csrfHeaderName={}, corsAllowedOriginsCount={}",
                sessionTtlHours,
                tokenCacheTtlSeconds,
                cookieName,
                cookiePath,
                cookieDomain == null ? "(none)" : cookieDomain,
                cookieSecure,
                cookieSameSite,
                csrfCookieName,
                csrfHeaderName,
                corsAllowedOrigins.size());
    }

    /**
     * 필수 문자열 속성의 기본값/trim 정규화를 수행합니다.
     *
     * <p>값이 null/blank이면 기본값을 적용하고 경고 로그를 남깁니다.</p>
     *
     * @param propertyKey 설정 키 이름(로그용)
     * @param rawValue 바인딩된 원본 값
     * @param defaultValue 기본값
     * @return 정규화된 문자열 값
     */
    private static String normalizeRequiredText(
            final String propertyKey,
            final String rawValue,
            final String defaultValue
    ) {
        if (rawValue == null || rawValue.isBlank()) {
            log.warn("인증 설정 보정 - {} 값이 비어 있어 기본값을 적용합니다. 적용값={}",
                    propertyKey, defaultValue);
            return defaultValue;
        }
        return rawValue.trim();
    }

    /**
     * 옵션 문자열 속성을 trim 후 비어 있으면 null로 정규화합니다.
     *
     * <p>cookie-domain은 미설정(null)을 정상 상태로 허용합니다.</p>
     *
     * @param rawValue 바인딩된 원본 값
     * @return 정규화된 문자열(값 있음) 또는 null(미설정)
     */
    private static String normalizeOptionalText(final String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return null;
        }
        return rawValue.trim();
    }

    /**
     * 인증 쿠키 Path를 정규화합니다.
     *
     * <p>Path가 비어 있으면 "/"를 사용하고, "/"로 시작하지 않으면 자동 보정합니다.</p>
     *
     * @param rawValue 바인딩된 원본 값
     * @return RFC 6265 관점에서 유효한 Path 문자열
     */
    private static String normalizeCookiePath(final String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            log.warn("인증 설정 보정 - cookie-path 값이 비어 있어 기본값을 적용합니다. 적용값={}",
                    DEFAULT_COOKIE_PATH);
            return DEFAULT_COOKIE_PATH;
        }

        final String trimmedPath = rawValue.trim();
        if (trimmedPath.startsWith("/")) {
            return trimmedPath;
        }

        final String adjustedPath = "/" + trimmedPath;
        log.warn("인증 설정 보정 - cookie-path 값이 '/'로 시작하지 않아 자동 보정했습니다. 입력값={}, 적용값={}",
                trimmedPath, adjustedPath);
        return adjustedPath;
    }

    /**
     * SameSite 문자열을 표준 표기(None/Lax/Strict)로 정규화합니다.
     *
     * <p>허용되지 않는 값은 기본값 None으로 보정합니다.</p>
     *
     * @param rawValue 바인딩된 원본 값
     * @return 정규화된 SameSite 값
     */
    private static String normalizeSameSite(final String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            log.warn("인증 설정 보정 - cookie-same-site 값이 비어 있어 기본값을 적용합니다. 적용값={}",
                    DEFAULT_COOKIE_SAME_SITE);
            return DEFAULT_COOKIE_SAME_SITE;
        }

        final String normalized = rawValue.trim().toLowerCase(Locale.ROOT);
        if ("strict".equals(normalized)) {
            return "Strict";
        }
        if ("lax".equals(normalized)) {
            return "Lax";
        }
        if ("none".equals(normalized)) {
            return "None";
        }

        log.warn("인증 설정 보정 - cookie-same-site 값이 허용 범위를 벗어나 기본값을 적용합니다. 입력값={}, 적용값={}",
                rawValue, DEFAULT_COOKIE_SAME_SITE);
        return DEFAULT_COOKIE_SAME_SITE;
    }

    /**
     * CORS 허용 Origin 목록을 정규화합니다.
     *
     * <p>null 엔트리/공백 엔트리를 제거하고, trim + 중복 제거를 수행합니다.</p>
     *
     * @param rawOrigins 바인딩된 원본 Origin 목록
     * @return 정규화된 Origin 목록(불변 리스트)
     */
    private static List<String> normalizeCorsAllowedOrigins(final List<String> rawOrigins) {
        if (rawOrigins == null || rawOrigins.isEmpty()) {
            return List.of();
        }

        final List<String> normalizedOrigins = rawOrigins.stream()
                .filter(origin -> origin != null && !origin.isBlank())
                .map(String::trim)
                .distinct()
                .toList();

        if (normalizedOrigins.size() != rawOrigins.size()) {
            log.debug("인증 설정 정규화 - cors-allowed-origins에서 null/공백/중복 엔트리를 제거했습니다. 입력개수={}, 적용개수={}",
                    rawOrigins.size(), normalizedOrigins.size());
        }

        return normalizedOrigins;
    }
}

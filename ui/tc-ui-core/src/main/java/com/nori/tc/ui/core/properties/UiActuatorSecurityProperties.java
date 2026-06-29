package com.nori.tc.ui.core.properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Actuator 민감 endpoint(prometheus/info/metrics) 접근 통제 설정입니다.
 *
 * <p>바인딩 대상 (config/tc-ui-backend.properties):</p>
 * <pre>
 * tc.ui.backend.actuator.allowed-cidrs=10.0.0.0/8,172.16.0.0/12,192.168.0.0/16,127.0.0.1/32,::1/128
 * </pre>
 *
 * <p>{@code allowedCidrs} 는 Actuator 민감 endpoint 호출을 허용할 출발지(내부망) CIDR 목록입니다.
 * {@code SecurityFilterChain} 의 인가 규칙은 "출발지 IP 가 이 목록에 포함됨 AND 토큰 인증 완료" 를
 * 모두 만족할 때만 허용합니다(둘 중 하나라도 불충족 시 차단). health/liveness/readiness probe 는
 * 본 통제 대상이 아니며 무인증으로 유지됩니다(k8s probe 용).</p>
 *
 * <p>값이 비어 있으면 사설망 표준 대역({@link #DEFAULT_ALLOWED_CIDRS})을 기본 적용합니다.
 * prometheus 메트릭은 OTLP push 경로로 수집되며 본 endpoint 를 scrape 하지 않으므로,
 * 본 통제는 측정 파이프라인에 영향을 주지 않습니다.</p>
 *
 * @param allowedCidrs Actuator 민감 endpoint 접근 허용 CIDR 목록(비어 있으면 사설망 기본 대역)
 */
@ConfigurationProperties(prefix = "tc.ui.backend.actuator")
public record UiActuatorSecurityProperties(
        List<String> allowedCidrs
) {

    private static final Logger log = LoggerFactory.getLogger(UiActuatorSecurityProperties.class);

    /**
     * 미설정 시 적용하는 사설망 표준 대역 + 루프백입니다.
     */
    private static final List<String> DEFAULT_ALLOWED_CIDRS = List.of(
            "10.0.0.0/8",
            "172.16.0.0/12",
            "192.168.0.0/16",
            "127.0.0.1/32",
            "::1/128"
    );

    /**
     * null/공백 엔트리 제거 + 미설정 시 사설망 기본 대역을 적용하는 compact constructor입니다.
     */
    public UiActuatorSecurityProperties {
        allowedCidrs = normalizeAllowedCidrs(allowedCidrs);
        log.info("UiActuatorSecurityProperties 검증 완료. allowedCidrsCount={}, values={}",
                allowedCidrs.size(), allowedCidrs);
    }

    private static List<String> normalizeAllowedCidrs(final List<String> rawCidrs) {
        if (rawCidrs == null || rawCidrs.isEmpty()) {
            log.info("actuator allowed-cidrs 미설정 - 사설망 기본 대역을 적용합니다. 적용값={}", DEFAULT_ALLOWED_CIDRS);
            return DEFAULT_ALLOWED_CIDRS;
        }
        final List<String> normalized = rawCidrs.stream()
                .filter(cidr -> cidr != null && !cidr.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        if (normalized.isEmpty()) {
            log.warn("actuator allowed-cidrs 가 비어 있어 사설망 기본 대역을 적용합니다. 적용값={}", DEFAULT_ALLOWED_CIDRS);
            return DEFAULT_ALLOWED_CIDRS;
        }
        return normalized;
    }
}

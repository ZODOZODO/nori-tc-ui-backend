package com.nori.tc.ui.adapters.redis.coordination;

import com.nori.tc.common.coordination.digest.CanonicalJsonCanonicalizer;
import com.nori.tc.common.coordination.digest.ManifestDigests;
import com.nori.tc.ui.core.coordination.port.UiCoordinationDigestPort;

/**
 * {@link UiCoordinationDigestPort} 의 공통 coordination 모듈 구현입니다(§8.4).
 *
 * <p>digest 규칙이 서비스마다 다르면 같은 manifest 가 서로 다른 idempotency 판정을 받으므로, 계산은
 * 반드시 공통 구현을 씁니다.</p>
 */
public final class CommonCoordinationDigestAdapter implements UiCoordinationDigestPort {

    private final CanonicalJsonCanonicalizer canonicalizer = new CanonicalJsonCanonicalizer();

    @Override
    public String canonicalize(final String json) {
        return canonicalizer.canonicalize(json);
    }

    @Override
    public String sha256Hex(final String canonicalJson) {
        return ManifestDigests.sha256Hex(canonicalJson);
    }
}

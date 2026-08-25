package com.nori.tc.ui.core.coordination.port;

/**
 * canonical JSON 정규화와 digest 계산 포트입니다(§8.4).
 *
 * <p>구현은 공통 coordination 모듈에 있고 core 는 인프라 의존을 갖지 않으므로 포트로 뒤집습니다 —
 * digest 규칙이 서비스마다 달라지면 같은 manifest 가 서로 다른 idempotency key 를 만든다.</p>
 */
public interface UiCoordinationDigestPort {

    /**
     * JSON 을 canonical 형태로 정규화합니다.
     *
     * @param json 원본 JSON
     * @return canonical JSON
     */
    String canonicalize(String json);

    /**
     * canonical JSON 의 SHA-256 lowercase hex digest 를 계산합니다.
     *
     * @param canonicalJson canonical JSON
     * @return digest
     */
    String sha256Hex(String canonicalJson);
}

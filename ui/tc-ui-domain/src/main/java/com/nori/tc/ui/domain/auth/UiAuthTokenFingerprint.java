package com.nori.tc.ui.domain.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 인증 토큰 fingerprint/마스킹 유틸리티입니다.
 *
 * <p>토큰 원문을 저장소와 로그에 그대로 남기지 않기 위한 공통 규칙을 제공합니다.</p>
 */
public final class UiAuthTokenFingerprint {

    private static final int MASK_PREFIX_LENGTH = 8;

    private UiAuthTokenFingerprint() {
    }

    /**
     * 토큰 원문을 SHA-256 16진 문자열로 변환합니다.
     *
     * @param token 토큰 원문
     * @return 64자 SHA-256 hex fingerprint
     */
    public static String sha256Hex(final String token) {
        validateToken(token);
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            final byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", e);
        }
    }

    /**
     * 로그 출력용 토큰 prefix를 반환합니다.
     *
     * @param token 토큰 원문
     * @return 앞 8자리 또는 N/A
     */
    public static String mask(final String token) {
        if (token == null || token.isBlank()) {
            return "N/A";
        }
        return token.substring(0, Math.min(MASK_PREFIX_LENGTH, token.length()));
    }

    private static void validateToken(final String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("token은 필수입니다.");
        }
    }
}

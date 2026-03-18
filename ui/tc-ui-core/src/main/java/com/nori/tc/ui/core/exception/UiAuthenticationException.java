package com.nori.tc.ui.core.exception;

/**
 * UI 인증 실패 예외입니다.
 *
 * <p>발생 상황:</p>
 * <ul>
 *   <li>사용자 ID가 존재하지 않는 경우</li>
 *   <li>비밀번호가 일치하지 않는 경우</li>
 *   <li>사용자 계정이 비활성(LOCKED/DISABLED/DELETED) 상태인 경우</li>
 *   <li>세션 토큰이 유효하지 않거나 만료된 경우</li>
 * </ul>
 *
 * <p>처리 방식:</p>
 * <p>tc-ui-web-adapter의 AuthController 또는 UiTokenAuthenticationFilter가
 * 이 예외를 캐치하여 HTTP 401 응답으로 변환합니다.</p>
 */
public class UiAuthenticationException extends RuntimeException {

    /**
     * 인증 실패 예외를 생성합니다.
     *
     * @param message 인증 실패 이유 (로그용, 사용자에게 직접 노출되지 않음)
     */
    public UiAuthenticationException(final String message) {
        super(message);
    }

    /**
     * 원인 예외를 포함한 인증 실패 예외를 생성합니다.
     *
     * @param message 인증 실패 이유
     * @param cause   원인 예외
     */
    public UiAuthenticationException(final String message, final Throwable cause) {
        super(message, cause);
    }
}

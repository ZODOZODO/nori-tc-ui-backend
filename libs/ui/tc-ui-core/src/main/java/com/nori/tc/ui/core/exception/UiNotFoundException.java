package com.nori.tc.ui.core.exception;

/**
 * UI 관리 API에서 조회/수정/삭제 대상이 존재하지 않을 때 사용하는 예외입니다.
 *
 * <p>웹 어댑터는 본 예외를 HTTP 404 NOT_FOUND로 변환합니다.</p>
 */
public class UiNotFoundException extends RuntimeException {

    /**
     * 예외 메시지로 인스턴스를 생성합니다.
     *
     * @param message 사용자에게 노출 가능한 오류 메시지
     */
    public UiNotFoundException(final String message) {
        super(message);
    }

    /**
     * 예외 메시지와 원인으로 인스턴스를 생성합니다.
     *
     * @param message 사용자에게 노출 가능한 오류 메시지
     * @param cause 원인 예외
     */
    public UiNotFoundException(final String message, final Throwable cause) {
        super(message, cause);
    }
}

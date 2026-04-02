package com.nori.tc.ui.core.exception;

/**
 * UI 요청이 현재 데이터 상태와 충돌할 때 사용하는 예외입니다.
 *
 * <p>대표 사례:</p>
 * <ul>
 *   <li>유니크 키 중복(중복 생성/중복 매핑)으로 저장이 거부된 경우</li>
 *   <li>FK 참조가 존재하여 삭제가 거부된 경우</li>
 *   <li>동일 자원에 대한 상태 충돌로 요청을 즉시 처리할 수 없는 경우</li>
 * </ul>
 *
 * <p>처리 정책:</p>
 * <p>Web Adapter(Controller)에서 본 예외를 수신하면
 * HTTP 409 Conflict로 변환하도록 설계합니다.</p>
 */
public class UiConflictException extends RuntimeException {

    private final String errorCode;

    /**
     * 메시지만 포함한 409 계열 예외를 생성합니다.
     *
     * @param message 충돌 원인 메시지
     */
    public UiConflictException(final String message) {
        this(UiErrorCode.CONFLICT, message);
    }

    /**
     * 오류 코드를 포함한 409 계열 예외를 생성합니다.
     *
     * @param errorCode 충돌 오류 코드
     * @param message 충돌 원인 메시지
     */
    public UiConflictException(
            final String errorCode,
            final String message
    ) {
        super(message);
        this.errorCode = errorCode == null || errorCode.isBlank() ? UiErrorCode.CONFLICT : errorCode;
    }

    /**
     * 원인 예외를 포함한 409 계열 예외를 생성합니다.
     *
     * @param message 충돌 원인 메시지
     * @param cause 원인 예외
     */
    public UiConflictException(final String message, final Throwable cause) {
        this(UiErrorCode.CONFLICT, message, cause);
    }

    /**
     * 오류 코드와 원인 예외를 포함한 409 계열 예외를 생성합니다.
     *
     * @param errorCode 충돌 오류 코드
     * @param message 충돌 원인 메시지
     * @param cause 원인 예외
     */
    public UiConflictException(
            final String errorCode,
            final String message,
            final Throwable cause
    ) {
        super(message, cause);
        this.errorCode = errorCode == null || errorCode.isBlank() ? UiErrorCode.CONFLICT : errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}

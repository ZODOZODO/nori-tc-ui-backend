package com.nori.tc.ui.core.coordination;

/**
 * 조정 원장에 접근할 수 없어 어떤 확정도 할 수 없는 상태입니다(§13.2 → 503).
 *
 * <p>이 예외는 <b>결과를 만들지 않는다</b>. HTTP 경로에서는 503 이 되고, Kafka reply ingress 에서는
 * ACK 를 금지하는 근거가 된다 — Redis 가 불명확한 상태에서 ACK 하면 그 reply proof 는 영구히
 * 사라진다(§13.5).</p>
 */
public class UiCoordinationUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 이 예외에 대응하는 API error code 입니다. */
    public static final String ERROR_CODE = "COORDINATION_UNAVAILABLE";

    /**
     * 사유 메시지로 예외를 만듭니다.
     *
     * @param message 사유
     */
    public UiCoordinationUnavailableException(final String message) {
        super(message);
    }

    /**
     * 사유와 원인으로 예외를 만듭니다.
     *
     * @param message 사유
     * @param cause 원인
     */
    public UiCoordinationUnavailableException(final String message, final Throwable cause) {
        super(message, cause);
    }
}

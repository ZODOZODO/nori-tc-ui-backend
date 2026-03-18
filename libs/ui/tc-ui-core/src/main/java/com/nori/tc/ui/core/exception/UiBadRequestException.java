package com.nori.tc.ui.core.exception;

/**
 * UI 요청 입력값이 정책/형식에 맞지 않을 때 사용하는 예외입니다.
 *
 * <p>대표 사례:</p>
 * <ul>
 *   <li>필수 경로 변수/쿼리 파라미터가 누락된 경우</li>
 *   <li>PK, offset, limit 등 숫자 입력이 허용 범위를 벗어난 경우</li>
 *   <li>상위 계층에서 전달한 명령 객체의 필수 필드가 비어 있는 경우</li>
 * </ul>
 *
 * <p>처리 정책:</p>
 * <p>Web Adapter(Controller)에서 본 예외를 수신하면
 * HTTP 400 Bad Request로 변환하도록 설계합니다.</p>
 */
public class UiBadRequestException extends RuntimeException {

    /**
     * 메시지만 포함한 400 계열 예외를 생성합니다.
     *
     * @param message 입력값 검증 실패 원인
     */
    public UiBadRequestException(final String message) {
        super(message);
    }

    /**
     * 원인 예외를 포함한 400 계열 예외를 생성합니다.
     *
     * @param message 입력값 검증 실패 원인
     * @param cause 원인 예외
     */
    public UiBadRequestException(final String message, final Throwable cause) {
        super(message, cause);
    }
}

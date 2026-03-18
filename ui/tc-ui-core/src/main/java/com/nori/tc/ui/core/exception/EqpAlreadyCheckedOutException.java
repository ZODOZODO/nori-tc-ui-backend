package com.nori.tc.ui.core.exception;

/**
 * 설비 파라미터가 이미 다른 사용자에 의해 체크아웃된 상태에서 체크아웃을 시도할 때 발생하는 예외입니다.
 *
 * <p>처리 정책:</p>
 * <p>Web Adapter(Controller)에서 본 예외를 수신하면 HTTP 409 Conflict로 변환합니다.</p>
 */
public class EqpAlreadyCheckedOutException extends UiConflictException {

    private static final String DEFAULT_MESSAGE = "설비 파라미터가 이미 다른 사용자에 의해 체크아웃 중입니다. 잠시 후 다시 시도해 주세요.";

    /**
     * 체크아웃 중인 사용자 ID를 포함한 409 예외를 생성합니다.
     *
     * @param eqpId 설비 비즈니스 ID
     * @param checkedOutBy 현재 체크아웃 중인 사용자 ID
     */
    public EqpAlreadyCheckedOutException(final String eqpId, final String checkedOutBy) {
        super(buildMessage(checkedOutBy));
    }

    /**
     * 메시지만 포함한 409 예외를 생성합니다.
     *
     * @param message 충돌 원인 메시지
     */
    public EqpAlreadyCheckedOutException(final String message) {
        super(message);
    }

    /**
     * 원인 예외를 포함한 409 예외를 생성합니다.
     *
     * @param message 충돌 원인 메시지
     * @param cause 원인 예외
     */
    public EqpAlreadyCheckedOutException(final String message, final Throwable cause) {
        super(message, cause);
    }

    /**
     * 사용자에게 노출할 충돌 메시지를 정규화합니다.
     *
     * <p>설비 ID는 로그에 남기고, 화면 메시지는 사용자 친화적인 문장으로 유지합니다.</p>
     *
     * @param checkedOutBy 체크아웃 사용자 ID
     * @return 정규화된 메시지
     */
    private static String buildMessage(final String checkedOutBy) {
        if (checkedOutBy == null || checkedOutBy.isBlank()) {
            return DEFAULT_MESSAGE;
        }
        return "설비 파라미터가 이미 체크아웃 중입니다. 현재 편집 사용자: " + checkedOutBy + ".";
    }
}

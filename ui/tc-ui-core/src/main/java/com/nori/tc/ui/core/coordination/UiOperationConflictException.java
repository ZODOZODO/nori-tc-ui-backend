package com.nori.tc.ui.core.coordination;

import java.util.Objects;

/**
 * 접수 단계에서 확정 가능한 충돌입니다(§13.2/§18.1 → 409).
 *
 * <p>{@code IDEMPOTENCY_CONFLICT} 는 같은 key 에 다른 digest 가 온 경우, {@code RESOURCE_BUSY} 는
 * 같은 리소스를 다른 operation 이 claim 중인 경우, {@code REVISION_CONFLICT} 는 expected base
 * revision 이 어긋난 경우다. 어느 쪽도 기존 operation/result 를 바꾸지 않는다.</p>
 */
public class UiOperationConflictException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 같은 idempotency key 에 다른 payload digest 가 온 경우입니다. */
    public static final String IDEMPOTENCY_CONFLICT = "IDEMPOTENCY_CONFLICT";

    /** 같은 리소스를 다른 operation 이 claim 중인 경우입니다. */
    public static final String RESOURCE_BUSY = "RESOURCE_BUSY";

    /** expected base revision 이 현재 head 와 다른 경우입니다. */
    public static final String REVISION_CONFLICT = "REVISION_CONFLICT";

    /** 미종결 operation 이 리소스를 차단하고 있는 경우입니다. */
    public static final String RESOURCE_BLOCKED = "RESOURCE_BLOCKED";

    private final String errorCode;
    private final String conflictingOperationId;

    /**
     * 충돌 예외를 만듭니다.
     *
     * @param errorCode 충돌 코드
     * @param message 사유
     * @param conflictingOperationId 충돌 상대 operation(없으면 null)
     */
    public UiOperationConflictException(
            final String errorCode,
            final String message,
            final String conflictingOperationId
    ) {
        super(message);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode is null");
        this.conflictingOperationId = conflictingOperationId;
    }

    /**
     * 충돌 코드를 반환합니다.
     *
     * @return 충돌 코드
     */
    public String errorCode() {
        return errorCode;
    }

    /**
     * 충돌 상대 operation 을 반환합니다.
     *
     * @return operationId(없으면 null)
     */
    public String conflictingOperationId() {
        return conflictingOperationId;
    }
}

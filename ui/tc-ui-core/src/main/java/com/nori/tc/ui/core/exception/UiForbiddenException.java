package com.nori.tc.ui.core.exception;

/**
 * 인증은 되었으나 해당 조작이 인가되지 않은 경우입니다(HTTP 403).
 *
 * <p>URL 단위 인가는 {@code UiApiPermissionCache} 가 담당합니다. 이 예외는 URL 인가를 통과한 뒤
 * 대상별로만 판정할 수 있는 조작(예: 자기 자신에게 권한을 부여하는 self-escalation)을 거부할 때 씁니다.</p>
 */
public class UiForbiddenException extends RuntimeException {

    public UiForbiddenException(final String message) {
        super(message);
    }
}

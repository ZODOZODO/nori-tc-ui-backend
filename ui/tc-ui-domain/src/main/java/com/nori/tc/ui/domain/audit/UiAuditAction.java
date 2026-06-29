package com.nori.tc.ui.domain.audit;

/**
 * 감사 로그에 기록하는 중요 작업 종류입니다.
 *
 * <p>ui-backend 가 직접 접수·처리하는 작업으로 한정합니다. 설비 제어는 gateway 경유라
 * 본 enum 의 범위가 아닙니다(account-lifecycle-policy / dev-report 의 범위 한정 참조).</p>
 */
public enum UiAuditAction {

    /** 로그인 시도(성공/실패는 {@link UiAuditOutcome} 로 구분). */
    LOGIN,

    /** 그룹에 권한 부여. */
    PERMISSION_GRANT,

    /** 그룹에서 권한 회수. */
    PERMISSION_REVOKE
}

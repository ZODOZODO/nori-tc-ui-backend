package com.nori.tc.ui.domain.auth;

/**
 * UI Backend 내부 사용자 상태 모델입니다.
 *
 * <p>인증 코어가 DB enum에 직접 의존하지 않도록 별도 의미 모델로 유지합니다.</p>
 */
public enum UiUserStatus {
    ACTIVE,
    LOCKED,
    DISABLED,
    DELETED
}

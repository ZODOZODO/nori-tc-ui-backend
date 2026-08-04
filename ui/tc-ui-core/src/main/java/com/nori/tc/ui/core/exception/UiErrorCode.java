package com.nori.tc.ui.core.exception;

/**
 * UI API 오류 코드를 정의합니다.
 */
public final class UiErrorCode {

    public static final String INVALID_REQUEST = "INVALID_REQUEST";
    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String FORBIDDEN = "FORBIDDEN";
    public static final String CONFLICT = "CONFLICT";
    public static final String DEPLOY_ACTIVE_EQP_CONFLICT = "DEPLOY_ACTIVE_EQP_CONFLICT";
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    private UiErrorCode() {
    }
}

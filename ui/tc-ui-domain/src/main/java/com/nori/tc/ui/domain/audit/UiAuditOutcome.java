package com.nori.tc.ui.domain.audit;

/**
 * 감사 대상 작업의 결과입니다.
 */
public enum UiAuditOutcome {

    /** 작업 성공. */
    SUCCESS,

    /** 작업 실패(거부/오류). */
    FAILURE
}

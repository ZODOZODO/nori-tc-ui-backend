package com.nori.tc.ui.adapters.web.dto.response;

/**
 * 설비 체크아웃 상태 응답 DTO입니다.
 *
 * @param checkedOut EDIT 버전 파라미터가 존재하면 true (= 체크아웃 중)
 * @param checkedOutBy 체크아웃한 사용자 ID (체크아웃 중이 아니면 null)
 */
public record EqpCheckoutStatusResponse(
        boolean checkedOut,
        String checkedOutBy
) {
}

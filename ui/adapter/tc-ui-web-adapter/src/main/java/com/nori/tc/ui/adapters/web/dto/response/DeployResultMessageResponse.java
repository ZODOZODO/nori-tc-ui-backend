package com.nori.tc.ui.adapters.web.dto.response;

/**
 * deploy API 결과 메시지 응답입니다.
 */
public record DeployResultMessageResponse(
        String title,
        String message,
        String severity
) {
}

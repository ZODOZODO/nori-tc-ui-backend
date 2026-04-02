package com.nori.tc.ui.adapters.web.dto.response;

/**
 * Cancel / Delete 같은 deploy 액션 응답 DTO 입니다.
 */
public record DeployActionResponse(
        long deployKey,
        String deployStatus,
        Boolean pauseRequested,
        Boolean deleted,
        String resultCode,
        DeployResultMessageResponse resultMessage
) {
}

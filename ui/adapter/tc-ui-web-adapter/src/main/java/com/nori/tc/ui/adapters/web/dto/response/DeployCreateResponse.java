package com.nori.tc.ui.adapters.web.dto.response;

/**
 * deploy 생성 응답 DTO 입니다.
 */
public record DeployCreateResponse(
        long deployKey,
        String deployStatus,
        PollingResponse polling,
        String resultCode,
        DeployResultMessageResponse resultMessage
) {

    /**
     * polling URL 묶음입니다.
     */
    public record PollingResponse(
            String summaryUrl,
            String targetsUrl
    ) {
    }
}

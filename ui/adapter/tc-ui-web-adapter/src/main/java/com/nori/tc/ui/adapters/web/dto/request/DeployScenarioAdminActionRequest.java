package com.nori.tc.ui.adapters.web.dto.request;

/**
 * scenario validate/reload 요청 본문입니다.
 *
 * @param reason 운영 사유
 */
public record DeployScenarioAdminActionRequest(
        String reason
) {
}

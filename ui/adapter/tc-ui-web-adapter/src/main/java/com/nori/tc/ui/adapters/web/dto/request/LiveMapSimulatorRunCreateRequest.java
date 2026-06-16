package com.nori.tc.ui.adapters.web.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * 모달 Run 경로 시작 요청(R-1) — 신규 orchestrator Run 경로. payload 형상은 기존 모달과 동일(testType/scenarioId/
 * eqpIds) — ui-backend 가 testType→mode 매핑 + 선택 설비 DB 설정→target 변환을 수행한다.
 */
public record LiveMapSimulatorRunCreateRequest(
        @NotBlank(message = "testType은 필수입니다.")
        String testType,
        @NotBlank(message = "scenarioId는 필수입니다.")
        String scenarioId,
        @NotEmpty(message = "eqpIds는 1개 이상이어야 합니다.")
        List<@NotBlank(message = "eqpId는 비어 있을 수 없습니다.") String> eqpIds
) {
}

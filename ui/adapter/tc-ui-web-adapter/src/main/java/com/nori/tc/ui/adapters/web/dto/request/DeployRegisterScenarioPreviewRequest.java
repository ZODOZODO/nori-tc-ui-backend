package com.nori.tc.ui.adapters.web.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.Map;

/**
 * Register deploy 시나리오 preview 요청 DTO 입니다.
 */
public record DeployRegisterScenarioPreviewRequest(

        @Valid
        @NotNull(message = "commonRequest는 필수입니다.")
        CommonRequest commonRequest,

        @Valid
        @NotEmpty(message = "targets는 1건 이상이어야 합니다.")
        List<TargetRequest> targets
) {

    /**
     * 공통 요청 블록입니다.
     */
    public record CommonRequest(

            @NotBlank(message = "commonRequest.interfaceType은 필수입니다.")
            String interfaceType,

            Map<String, Object> uiMessage
    ) {
    }

    /**
     * 개별 target 요청 블록입니다.
     */
    public record TargetRequest(

            @NotBlank(message = "targets[].eqpId는 필수입니다.")
            String eqpId,

            Map<String, Object> uiMessageOverride
    ) {
    }
}

package com.nori.tc.ui.adapters.web.dto.response;

import com.nori.tc.ui.core.deploy.DeployStatus;
import com.nori.tc.ui.domain.common.model.UiModelStatus;
import com.nori.tc.ui.domain.common.model.UiProtocolType;

import java.util.List;

/**
 * Deploy Register 대상 설비 목록 응답 DTO입니다.
 */
public record DeployRegisterTargetPageResponse(
        List<DeployRegisterTargetResponse> items,
        int offset,
        int limit,
        long count
) {

    /**
     * Deploy Register 대상 설비 단건 응답 DTO입니다.
     */
    public record DeployRegisterTargetResponse(
            Long eqpKey,
            String eqpId,
            UiProtocolType commInterface,
            boolean enabled,
            Long modelVersionKey,
            String modelName,
            String modelVersion,
            UiModelStatus modelStatus,
            Long businessJarRef,
            String businessJarFileName,
            String appliedParamVersion,
            boolean blockedByActiveDeploy,
            Long activeDeployKey,
            DeployStatus activeDeployStatus,
            String activeDeployName,
            String activeRequestedBy
    ) {
    }
}

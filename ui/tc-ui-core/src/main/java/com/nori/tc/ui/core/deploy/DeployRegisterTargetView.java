package com.nori.tc.ui.core.deploy;

import com.nori.tc.ui.domain.common.model.UiModelStatus;
import com.nori.tc.ui.domain.common.model.UiProtocolType;

/**
 * Deploy Register 화면의 대상 설비 조회 결과 단건 뷰입니다.
 */
public record DeployRegisterTargetView(
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
        String appliedParamVersion
) {
}

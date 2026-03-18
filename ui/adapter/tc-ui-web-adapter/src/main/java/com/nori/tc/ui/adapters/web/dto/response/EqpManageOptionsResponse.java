package com.nori.tc.ui.adapters.web.dto.response;

import com.nori.tc.db.domain.common.model.ModelStatus;
import com.nori.tc.db.domain.common.model.ProtocolType;

import java.util.List;

/**
 * EQP 관리 옵션 응답 DTO입니다.
 */
public record EqpManageOptionsResponse(
        List<String> socketProtocolTypes,
        List<String> gatewayJarFileNames,
        List<String> businessJarFileNames,
        List<ModelOptionResponse> developModelOptions,
        List<ModelOptionResponse> operateModelOptions
) {

    /**
     * EQP 관리 화면 모델 선택 옵션 응답 DTO입니다.
     */
    public record ModelOptionResponse(
            long modelVersionKey,
            long modelKey,
            String modelName,
            String parentModel,
            String modelVersion,
            ProtocolType commInterface,
            ModelStatus status
    ) {
    }
}

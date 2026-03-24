package com.nori.tc.ui.adapters.web.dto.response;

import com.nori.tc.ui.domain.common.model.UiModelStatus;
import com.nori.tc.ui.domain.common.model.UiProtocolType;

import java.util.List;

/**
 * EQP 관리 옵션 응답 DTO입니다.
 */
public record EqpManageOptionsResponse(
        List<String> socketProtocolTypes,
        List<JarOptionResponse> gatewayJarOptions,
        List<JarOptionResponse> businessJarOptions,
        List<ModelOptionResponse> developModelOptions,
        List<ModelOptionResponse> operateModelOptions
) {

    /**
     * EQP 관리 화면 JAR 선택 옵션 응답 DTO입니다.
     */
    public record JarOptionResponse(
            long jarRef,
            String jarFileName
    ) {
    }

    /**
     * EQP 관리 화면 모델 선택 옵션 응답 DTO입니다.
     */
    public record ModelOptionResponse(
            long modelVersionKey,
            long modelKey,
            String modelName,
            String parentModel,
            String modelVersion,
            UiProtocolType commInterface,
            UiModelStatus status
    ) {
    }
}

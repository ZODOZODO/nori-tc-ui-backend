package com.nori.tc.ui.core.eqp;

import com.nori.tc.ui.domain.common.model.UiModelStatus;
import com.nori.tc.ui.domain.common.model.UiProtocolType;

import java.util.List;

/**
 * EQP 관리 화면 드롭다운 옵션 묶음입니다.
 */
public record EqpManagementOptions(
        List<String> socketProtocolTypes,
        List<JarOption> gatewayJarOptions,
        List<JarOption> businessJarOptions,
        List<ModelOption> developModelOptions,
        List<ModelOption> operateModelOptions
) {

    /**
     * JAR 선택 옵션입니다.
     *
     * @param jarRef jar 식별 키
     * @param jarFileName jar 파일명
     */
    public record JarOption(
            long jarRef,
            String jarFileName
    ) {
    }

    /**
     * 모델 선택 옵션입니다.
     *
     * @param modelVersionKey model version key
     * @param modelKey model key
     * @param modelName model name
     * @param parentModel parent model name
     * @param modelVersion model version
     * @param commInterface 통신 인터페이스
     * @param status 모델 상태
     */
    public record ModelOption(
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

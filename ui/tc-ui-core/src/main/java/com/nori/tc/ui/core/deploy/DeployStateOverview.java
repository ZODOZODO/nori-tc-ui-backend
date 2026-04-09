package com.nori.tc.ui.core.deploy;

/**
 * Deploy View 에서 표시할 AS-IS / TO-BE 상태 요약입니다.
 */
public record DeployStateOverview(
        String interfaceType,
        String modelName,
        String modelVersion,
        String businessJarName,
        String eqpParamVersion
) {
}

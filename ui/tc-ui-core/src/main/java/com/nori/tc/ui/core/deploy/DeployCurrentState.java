package com.nori.tc.ui.core.deploy;

/**
 * planning 시점 DB 기준 current state 입니다.
 */
public record DeployCurrentState(
        long eqpKey,
        String eqpId,
        String interfaceType,
        String connectionMode,
        String eqpIp,
        int eqpPort,
        long modelVersionKey,
        String appliedParamVersion,
        Long gatewayJarRef,
        Long businessJarRef,
        String socketType,
        Integer hsmsDeviceId
) {

    public DeployCurrentState {
        if (eqpKey <= 0) {
            throw new IllegalArgumentException("eqpKey must be greater than 0");
        }
        eqpId = requireText("eqpId", eqpId);
        interfaceType = requireText("interfaceType", interfaceType);
        connectionMode = requireText("connectionMode", connectionMode);
        eqpIp = requireText("eqpIp", eqpIp);
        if (eqpPort <= 0) {
            throw new IllegalArgumentException("eqpPort must be greater than 0");
        }
        if (modelVersionKey <= 0) {
            throw new IllegalArgumentException("modelVersionKey must be greater than 0");
        }
        socketType = normalizeOptionalText(socketType);
        appliedParamVersion = normalizeOptionalText(appliedParamVersion);
    }

    private static String requireText(final String fieldName, final String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }

    private static String normalizeOptionalText(final String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}

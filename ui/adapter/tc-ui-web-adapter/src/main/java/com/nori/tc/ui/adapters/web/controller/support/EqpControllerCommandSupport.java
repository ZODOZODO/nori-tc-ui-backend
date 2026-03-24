package com.nori.tc.ui.adapters.web.controller.support;

import com.nori.tc.ui.adapters.web.dto.request.EqpCreateRequest;
import com.nori.tc.ui.adapters.web.dto.request.EqpManagementRequestSupport;
import com.nori.tc.ui.adapters.web.dto.request.EqpUpdateRequest;
import com.nori.tc.ui.core.eqp.EqpManagementCommand;
import com.nori.tc.ui.domain.auth.UserPrincipal;
import org.springframework.security.core.Authentication;

/**
 * EqpController 요청 DTO를 core 명령으로 변환하는 보조 유틸리티입니다.
 */
public final class EqpControllerCommandSupport {

    private EqpControllerCommandSupport() {
    }

    public static EqpManagementCommand.Create toCreateCommand(
            final EqpCreateRequest request,
            final Authentication authentication
    ) {
        return new EqpManagementCommand.Create(
                resolveCurrentUser(authentication),
                request.eqpId(),
                request.interfaceType(),
                request.commMode(),
                request.isDev(),
                request.routePartition(),
                request.eqpIp(),
                request.eqpPort(),
                request.modelVersionKey(),
                request.appliedParamVersion(),
                request.gatewayJarRef(),
                request.businessJarRef(),
                toLogSettings(request.logSettings()),
                toHsmsSettings(request.hsmsSettings()),
                toSocketSettings(request.socketSettings())
        );
    }

    public static EqpManagementCommand.Update toUpdateCommand(
            final EqpUpdateRequest request,
            final Authentication authentication
    ) {
        return new EqpManagementCommand.Update(
                resolveCurrentUser(authentication),
                request.commMode(),
                request.isDev(),
                request.routePartition(),
                request.eqpIp(),
                request.eqpPort(),
                request.modelVersionKey(),
                request.appliedParamVersion(),
                request.gatewayJarRef(),
                request.businessJarRef(),
                toLogSettings(request.logSettings()),
                toHsmsSettings(request.hsmsSettings()),
                toSocketSettings(request.socketSettings())
        );
    }

    public static String resolveCurrentUser(final Authentication authentication) {
        if (authentication == null) {
            return "SYSTEM";
        }
        if (authentication.getPrincipal() instanceof UserPrincipal principal
                && principal.userId() != null
                && !principal.userId().isBlank()) {
            return principal.userId();
        }
        if (authentication.getName() == null || authentication.getName().isBlank()) {
            return "SYSTEM";
        }
        return authentication.getName();
    }

    private static EqpManagementCommand.LogSettings toLogSettings(
            final EqpManagementRequestSupport.LogSettingsRequest request
    ) {
        if (request == null) {
            return null;
        }
        return new EqpManagementCommand.LogSettings(
                request.logLevel(),
                request.logRetentionDays(),
                request.logPath()
        );
    }

    private static EqpManagementCommand.HsmsSettings toHsmsSettings(
            final EqpManagementRequestSupport.HsmsSettingsRequest request
    ) {
        if (request == null) {
            return null;
        }
        return new EqpManagementCommand.HsmsSettings(
                request.deviceId(),
                request.t3Timeout(),
                request.t5Timeout(),
                request.t6Timeout(),
                request.t7Timeout(),
                request.t8Timeout(),
                request.linkTestEnabled(),
                request.linkTestInterval(),
                request.maxMsgBytes()
        );
    }

    private static EqpManagementCommand.SocketSettings toSocketSettings(
            final EqpManagementRequestSupport.SocketSettingsRequest request
    ) {
        if (request == null) {
            return null;
        }
        return new EqpManagementCommand.SocketSettings(
                request.socketProtocolType(),
                request.charset(),
                request.heartbeatEnabled(),
                request.heartbeatInterval(),
                request.readTimeout(),
                request.writeTimeout(),
                request.maxFrameSizeBytes(),
                request.keepAliveEnabled()
        );
    }
}

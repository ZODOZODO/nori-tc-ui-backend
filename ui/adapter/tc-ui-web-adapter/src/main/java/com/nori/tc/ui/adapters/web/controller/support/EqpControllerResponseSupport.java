package com.nori.tc.ui.adapters.web.controller.support;

import com.nori.tc.ui.adapters.web.dto.response.ApiResponse;
import com.nori.tc.ui.adapters.web.dto.response.EqpInfoResponse;
import com.nori.tc.ui.adapters.web.dto.response.EqpManageDetailResponse;
import com.nori.tc.ui.adapters.web.dto.response.EqpManageOptionsResponse;
import com.nori.tc.ui.core.eqp.EqpCommandResult;
import com.nori.tc.ui.core.eqp.EqpManagementOptions;
import com.nori.tc.ui.core.eqp.EqpManagementSnapshot;
import com.nori.tc.ui.core.model.PagedResponse;
import com.nori.tc.ui.domain.eqp.UiEqp;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.List;

/**
 * EqpController 응답 DTO 변환을 담당하는 보조 유틸리티입니다.
 */
public final class EqpControllerResponseSupport {

    private static final String EDIT_PARAM_VERSION = "EDIT";

    private EqpControllerResponseSupport() {
    }

    public static ResponseEntity<ApiResponse<Void>> toCommandResponse(final EqpCommandResult result) {
        if (result.success()) {
            return ResponseEntity.status(result.statusCode()).body(ApiResponse.success(null));
        }
        return ResponseEntity.status(result.statusCode())
                .body(ApiResponse.error(result.errorCode(), result.errorMessage()));
    }

    public static EqpManageDetailResponse toManageDetailResponse(final EqpManagementSnapshot snapshot) {
        final List<EqpManageDetailResponse.ParamVersionOptionResponse> paramVersions = toParamVersionOptions(snapshot);
        final AppliedParamVersionView appliedParamView = resolveAppliedParamVersionView(
                snapshot.eqp().appliedParamVersion(),
                paramVersions
        );

        return new EqpManageDetailResponse(
                snapshot.eqp().eqpId(),
                snapshot.eqp().commInterface(),
                snapshot.eqp().commMode(),
                snapshot.eqp().isDev(),
                snapshot.eqp().routePartition(),
                snapshot.eqp().eqpIp(),
                snapshot.eqp().eqpPort(),
                snapshot.eqp().enabled(),
                snapshot.runtimeState() == null
                        ? null
                        : new EqpManageDetailResponse.RuntimeStateResponse(
                        snapshot.runtimeState().controlState(),
                        snapshot.runtimeState().eqpState(),
                        snapshot.connectionState()
                ),
                snapshot.logPolicy() == null
                        ? null
                        : new EqpManageDetailResponse.LogPolicyResponse(
                        snapshot.logPolicy().logLevel(),
                        snapshot.logPolicy().logRetentionDays(),
                        snapshot.logPolicy().logPath()
                ),
                new EqpManageDetailResponse.JarBindingResponse(
                        snapshot.eqp().gatewayJarKey(),
                        snapshot.gatewayJar() == null ? null : snapshot.gatewayJar().jarFileName(),
                        snapshot.eqp().businessJarKey(),
                        snapshot.businessJar() == null ? null : snapshot.businessJar().jarFileName()
                ),
                snapshot.model() == null
                        ? null
                        : new EqpManageDetailResponse.ModelBindingResponse(
                        snapshot.model().modelVersionKey(),
                        snapshot.model().modelKey(),
                        snapshot.model().modelName(),
                        snapshot.model().parentModel(),
                        snapshot.model().modelVersion(),
                        snapshot.model().commInterface(),
                        snapshot.model().status()
                ),
                snapshot.hsms() == null
                        ? null
                        : new EqpManageDetailResponse.HsmsSettingsResponse(
                        snapshot.hsms().deviceId(),
                        snapshot.hsms().t3Timeout(),
                        snapshot.hsms().t5Timeout(),
                        snapshot.hsms().t6Timeout(),
                        snapshot.hsms().t7Timeout(),
                        snapshot.hsms().t8Timeout(),
                        snapshot.hsms().linkTestEnabled(),
                        snapshot.hsms().linkTestInterval(),
                        snapshot.hsms().maxMsgBytes()
                ),
                snapshot.socket() == null
                        ? null
                        : new EqpManageDetailResponse.SocketSettingsResponse(
                        snapshot.socket().socketProtocolType(),
                        snapshot.socket().charset(),
                        snapshot.socket().heartbeatEnabled(),
                        snapshot.socket().heartbeatInterval(),
                        snapshot.socket().readTimeout(),
                        snapshot.socket().writeTimeout(),
                        snapshot.socket().maxFrameSizeBytes(),
                        snapshot.socket().keepAliveEnabled()
                ),
                appliedParamView.version(),
                appliedParamView.description(),
                paramVersions,
                snapshot.portStatuses().stream()
                        .map(portStatus -> new EqpManageDetailResponse.PortStatusResponse(
                                portStatus.portId(),
                                portStatus.portType(),
                                portStatus.portState(),
                                portStatus.carrierId(),
                                portStatus.carrierType(),
                                portStatus.carrierState(),
                                portStatus.updatedAt()
                        ))
                        .toList()
        );
    }

    public static EqpManageOptionsResponse toManageOptionsResponse(final EqpManagementOptions options) {
        return new EqpManageOptionsResponse(
                options.socketProtocolTypes(),
                options.gatewayJarOptions().stream()
                        .map(EqpControllerResponseSupport::toJarOptionResponse)
                        .toList(),
                options.businessJarOptions().stream()
                        .map(EqpControllerResponseSupport::toJarOptionResponse)
                        .toList(),
                options.developModelOptions().stream()
                        .map(EqpControllerResponseSupport::toModelOptionResponse)
                        .toList(),
                options.operateModelOptions().stream()
                        .map(EqpControllerResponseSupport::toModelOptionResponse)
                        .toList()
        );
    }

    public static PagedResponse<EqpInfoResponse> toEqpPage(final PagedResponse<UiEqp> page) {
        return PagedResponse.of(
                page.items().stream()
                        .map(EqpControllerResponseSupport::toEqpInfoResponse)
                        .toList(),
                page.offset(),
                page.limit(),
                page.count()
        );
    }

    private static List<EqpManageDetailResponse.ParamVersionOptionResponse> toParamVersionOptions(
            final EqpManagementSnapshot snapshot
    ) {
        final LinkedHashMap<String, String> paramVersionDescriptions = new LinkedHashMap<>();

        snapshot.paramVersionMetas().forEach(paramVersionMeta -> {
            final String normalizedVersion = normalizeText(paramVersionMeta.paramVersion());
            if (normalizedVersion == null || EDIT_PARAM_VERSION.equals(normalizedVersion)) {
                return;
            }
            paramVersionDescriptions.putIfAbsent(
                    normalizedVersion,
                    normalizeText(paramVersionMeta.versionDescription())
            );
        });

        snapshot.params().forEach(param -> {
            final String normalizedVersion = normalizeText(param.paramVersion());
            if (normalizedVersion == null || EDIT_PARAM_VERSION.equals(normalizedVersion)) {
                return;
            }
            paramVersionDescriptions.putIfAbsent(normalizedVersion, normalizeText(param.description()));
        });

        return paramVersionDescriptions.entrySet().stream()
                .map(entry -> new EqpManageDetailResponse.ParamVersionOptionResponse(entry.getKey(), entry.getValue()))
                .toList();
    }

    private static AppliedParamVersionView resolveAppliedParamVersionView(
            final String storedAppliedParamVersion,
            final List<EqpManageDetailResponse.ParamVersionOptionResponse> paramVersions
    ) {
        final String appliedParamVersion = normalizeText(storedAppliedParamVersion);
        if (appliedParamVersion != null) {
            return new AppliedParamVersionView(
                    appliedParamVersion,
                    resolveAppliedParamDescription(paramVersions, appliedParamVersion)
            );
        }

        final EqpManageDetailResponse.ParamVersionOptionResponse fallback = paramVersions.stream()
                .findFirst()
                .orElse(null);
        if (fallback == null) {
            return new AppliedParamVersionView(null, null);
        }

        return new AppliedParamVersionView(
                normalizeText(fallback.paramVersion()),
                normalizeText(fallback.description())
        );
    }

    private static String resolveAppliedParamDescription(
            final List<EqpManageDetailResponse.ParamVersionOptionResponse> paramVersions,
            final String appliedParamVersion
    ) {
        return paramVersions.stream()
                .filter(option -> appliedParamVersion.equals(normalizeText(option.paramVersion())))
                .map(EqpManageDetailResponse.ParamVersionOptionResponse::description)
                .findFirst()
                .orElse(null);
    }

    private static EqpManageOptionsResponse.ModelOptionResponse toModelOptionResponse(
            final EqpManagementOptions.ModelOption option
    ) {
        return new EqpManageOptionsResponse.ModelOptionResponse(
                option.modelVersionKey(),
                option.modelKey(),
                option.modelName(),
                option.parentModel(),
                option.modelVersion(),
                option.commInterface(),
                option.status()
        );
    }

    private static EqpManageOptionsResponse.JarOptionResponse toJarOptionResponse(
            final EqpManagementOptions.JarOption option
    ) {
        return new EqpManageOptionsResponse.JarOptionResponse(
                option.jarRef(),
                option.jarFileName()
        );
    }

    public static EqpInfoResponse toEqpInfoResponse(final UiEqp eqp) {
        return new EqpInfoResponse(
                eqp.eqpKey(),
                eqp.eqpId(),
                eqp.commInterface(),
                eqp.commMode(),
                eqp.isDev(),
                eqp.routePartition(),
                eqp.eqpIp(),
                eqp.eqpPort(),
                eqp.modelVersionKey(),
                eqp.enabled(),
                eqp.createdAt(),
                eqp.updatedAt(),
                eqp.createdBy(),
                eqp.updatedBy()
        );
    }

    private static String normalizeText(final String value) {
        if (value == null) {
            return null;
        }
        final String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private record AppliedParamVersionView(
            String version,
            String description
    ) {
    }
}

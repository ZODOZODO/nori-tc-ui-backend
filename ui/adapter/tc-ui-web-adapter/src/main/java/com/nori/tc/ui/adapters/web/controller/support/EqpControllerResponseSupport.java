package com.nori.tc.ui.adapters.web.controller.support;

import com.nori.tc.ui.adapters.web.dto.response.ApiResponse;
import com.nori.tc.ui.adapters.web.dto.response.EqpInfoResponse;
import com.nori.tc.ui.adapters.web.dto.response.EqpManageDetailBatchResponse;
import com.nori.tc.ui.adapters.web.dto.response.EqpManageDetailResponse;
import com.nori.tc.ui.adapters.web.dto.response.EqpManageOptionsResponse;
import com.nori.tc.ui.core.eqp.EqpCommandResult;
import com.nori.tc.ui.core.eqp.EqpManagementOptions;
import com.nori.tc.ui.core.eqp.EqpManagementSnapshot;
import com.nori.tc.ui.core.model.PagedResponse;
import com.nori.tc.ui.domain.eqp.UiEqp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * EqpController 응답 DTO 변환을 담당하는 보조 유틸리티입니다.
 */
public final class EqpControllerResponseSupport {

    private static final Logger log = LoggerFactory.getLogger(EqpControllerResponseSupport.class);
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
                snapshot.eqp().lineKey(),
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
                snapshot.plc() == null
                        ? null
                        : new EqpManageDetailResponse.PlcSettingsResponse(
                        snapshot.plc().plcProtocol(),
                        snapshot.plc().transport(),
                        snapshot.plc().unitId(),
                        snapshot.plc().pollTimeoutMs(),
                        snapshot.plc().reconnectMaxRetries()
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

    /**
     * 요청한 eqpId 순서대로 배치 응답을 만들고, 존재하지 않는 항목은 missingEqpIds에 담아 반환합니다.
     */
    public static EqpManageDetailBatchResponse toManageDetailBatchResponse(
            final List<String> requestedEqpIds,
            final Map<String, EqpManagementSnapshot> snapshotByEqpId
    ) {
        final List<EqpManageDetailResponse> items = new ArrayList<>(requestedEqpIds.size());
        final List<String> missingEqpIds = new ArrayList<>();
        final java.util.LinkedHashSet<String> processed = new java.util.LinkedHashSet<>();
        for (final String rawEqpId : requestedEqpIds) {
            if (rawEqpId == null) {
                continue;
            }
            final String eqpId = rawEqpId.trim();
            if (eqpId.isEmpty() || !processed.add(eqpId)) {
                continue;
            }
            final EqpManagementSnapshot snapshot = snapshotByEqpId.get(eqpId);
            if (snapshot == null) {
                missingEqpIds.add(eqpId);
                continue;
            }
            // 한 EQP의 DTO 변환 실패가 batch 응답 전체를 500으로 떨어뜨리지 않도록 격리합니다.
            // 변환 실패는 missing으로 분류해 클라이언트가 부분 결과를 받게 하고, 원인은 로그로 남깁니다.
            try {
                items.add(toManageDetailResponse(snapshot));
            } catch (RuntimeException exception) {
                log.warn(
                        "EQP manage 응답 변환 실패. 해당 항목은 missing으로 처리합니다. eqpId={}",
                        eqpId,
                        exception
                );
                missingEqpIds.add(eqpId);
            }
        }
        return new EqpManageDetailBatchResponse(items, missingEqpIds);
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
        // 적용된 파라미터 버전은 deploy 가 완료된 시점에만 tc_eqp.applied_param_version 에 기록됩니다.
        // 값이 비어 있으면 '미적용' 상태이므로, 단순히 최신 체크인 버전으로 대체하지 않고 null 을 그대로 반환합니다.
        final String appliedParamVersion = normalizeText(storedAppliedParamVersion);
        if (appliedParamVersion == null) {
            return new AppliedParamVersionView(null, null);
        }

        // 해당 설비에 실제로 존재하는 버전(check-in된 버전) 목록에 없으면 미적용으로 처리합니다.
        // tc_eqp.applied_param_version과 실제 파라미터 데이터가 불일치하는 경우를 방어합니다.
        final boolean versionExistsInParams = paramVersions.stream()
                .anyMatch(option -> appliedParamVersion.equals(normalizeText(option.paramVersion())));
        if (!versionExistsInParams) {
            return new AppliedParamVersionView(null, null);
        }

        return new AppliedParamVersionView(
                appliedParamVersion,
                resolveAppliedParamDescription(paramVersions, appliedParamVersion)
        );
    }

    private static String resolveAppliedParamDescription(
            final List<EqpManageDetailResponse.ParamVersionOptionResponse> paramVersions,
            final String appliedParamVersion
    ) {
        // description은 nullable 컬럼입니다.
        // .map(::description).findFirst()로 짜면 null 매핑 결과가 Optional.of(null)에서 NPE를 유발하므로
        // findFirst()를 element 단계에서 끊고 그 다음 map으로 description을 꺼내야 안전합니다.
        return paramVersions.stream()
                .filter(option -> appliedParamVersion.equals(normalizeText(option.paramVersion())))
                .findFirst()
                .map(EqpManageDetailResponse.ParamVersionOptionResponse::description)
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
                eqp.lineKey(),
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

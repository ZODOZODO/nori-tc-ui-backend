package com.nori.tc.ui.core.service;

import com.nori.tc.ui.core.eqp.LiveMapEqpPrefetchRow;
import com.nori.tc.ui.core.eqp.LiveMapEquipmentView;
import com.nori.tc.ui.core.eqp.LiveMapPortView;
import com.nori.tc.ui.core.eqp.LiveMapPrefetchSnapshot;
import com.nori.tc.ui.core.port.db.EqpQueryPort;
import com.nori.tc.ui.core.port.db.LiveMapPrefetchPort;
import com.nori.tc.ui.domain.eqp.UiEqpParam;
import com.nori.tc.ui.domain.eqp.UiEqpPortStatus;
import com.nori.tc.ui.domain.model.UiModelParam;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * live-map 조회 전용 서비스입니다.
 *
 * <p>호출 1회 = {@link LiveMapPrefetchPort#prefetch()} 1회 — N+1 sub-store 패턴이 제거되었다.
 * 모든 매핑은 prefetch 결과를 메모리상에서 변환만 수행한다.</p>
 */
@Service
public class LiveMapQueryService {

    private static final String PROCESS_TYPE_PARAM_NAME = "PROCESS_TYPE";
    private static final String WAFER_MODE_PARAM_NAME = "WAFER_MODE";

    private final LiveMapPrefetchPort liveMapPrefetchPort;

    public LiveMapQueryService(final LiveMapPrefetchPort liveMapPrefetchPort) {
        this.liveMapPrefetchPort = Objects.requireNonNull(liveMapPrefetchPort, "liveMapPrefetchPort is null");
    }

    /**
     * live-map 설비 목록을 조회합니다.
     */
    public List<LiveMapEquipmentView> getEquipmentViews() {
        final LiveMapPrefetchSnapshot snapshot = liveMapPrefetchPort.prefetch();
        final Map<Long, List<UiModelParam>> modelParams = snapshot.modelParamsByModelVersionKey();
        return snapshot.rows().stream()
                .map(row -> toEquipmentView(row, modelParams))
                .flatMap(Optional::stream)
                .sorted(Comparator.comparing(LiveMapEquipmentView::clusterId).thenComparing(LiveMapEquipmentView::eqpId))
                .toList();
    }

    private Optional<LiveMapEquipmentView> toEquipmentView(
            final LiveMapEqpPrefetchRow row,
            final Map<Long, List<UiModelParam>> modelParams
    ) {
        if (row == null || row.eqp() == null) {
            return Optional.empty();
        }
        final String processType = resolveProcessType(row, modelParams);
        final String clusterId = normalizeClusterId(processType);
        final String clusterName = processType;
        final EqpQueryPort.EqpRuntimeStateView runtimeState = row.runtimeState() == null
                ? new EqpQueryPort.EqpRuntimeStateView(null, null, null, null)
                : row.runtimeState();
        final String status = resolveEquipmentStatus(runtimeState.controlState(), runtimeState.eqpState());
        final List<UiEqpPortStatus> portStatuses = row.portStatuses() == null ? List.of() : row.portStatuses();
        final List<LiveMapPortView> portItems = portStatuses.stream()
                .sorted(Comparator.comparing(port -> safeUpper(port.portId())))
                .map(this::toPortView)
                .toList();

        return Optional.of(new LiveMapEquipmentView(
                row.eqp().eqpKey(),
                row.eqp().eqpId(),
                row.eqp().eqpId(),
                row.lineCode(),
                row.lineName(),
                clusterId,
                clusterName,
                resolveWaferMode(row, modelParams),
                status,
                status,
                runtimeState.desiredState(),
                portItems,
                buildTooltipMeta(portStatuses),
                buildTooltipStep(runtimeState),
                status.equals("ERROR") ? "설비 상태값을 확인해 주세요." : null
        ));
    }

    private String resolveProcessType(final LiveMapEqpPrefetchRow row, final Map<Long, List<UiModelParam>> modelParams) {
        final Optional<String> eqpProcessType = resolveEqpParamValue(row, PROCESS_TYPE_PARAM_NAME)
                .map(this::normalizeProcessTypeValue)
                .filter(value -> !value.isBlank());
        if (eqpProcessType.isPresent()) {
            return eqpProcessType.get();
        }

        return resolveModelParamValue(row.eqp().modelVersionKey(), PROCESS_TYPE_PARAM_NAME, modelParams)
                .map(this::normalizeProcessTypeValue)
                .filter(value -> !value.isBlank())
                .orElse("ETC");
    }

    private String resolveWaferMode(final LiveMapEqpPrefetchRow row, final Map<Long, List<UiModelParam>> modelParams) {
        final Optional<String> eqpWaferMode = resolveEqpParamValue(row, WAFER_MODE_PARAM_NAME)
                .map(this::normalizeWaferModeValue)
                .filter(value -> !value.isBlank());
        if (eqpWaferMode.isPresent()) {
            return eqpWaferMode.get();
        }

        return resolveModelParamValue(row.eqp().modelVersionKey(), WAFER_MODE_PARAM_NAME, modelParams)
                .map(this::normalizeWaferModeValue)
                .filter(value -> !value.isBlank())
                .orElse("-");
    }

    private Optional<String> resolveEqpParamValue(final LiveMapEqpPrefetchRow row, final String paramName) {
        if (row.params() == null) {
            return Optional.empty();
        }
        // tc_eqp_param.param_value는 nullable이라 .map(::paramValue).findFirst() 순서로 짜면
        // 매핑 결과 null에서 Optional.of(null) NPE가 납니다. element 단위로 findFirst() 한 뒤 map 합니다.
        return row.params().stream()
                .filter(param -> paramName.equalsIgnoreCase(param.paramName()))
                .filter(param -> matchesAppliedVersion(row.eqp().appliedParamVersion(), param))
                .sorted(Comparator.comparing(UiEqpParam::updatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .findFirst()
                .map(UiEqpParam::paramValue);
    }

    private Optional<String> resolveModelParamValue(
            final long modelVersionKey,
            final String paramName,
            final Map<Long, List<UiModelParam>> modelParams
    ) {
        final List<UiModelParam> params = modelParams == null ? List.of() : modelParams.getOrDefault(modelVersionKey, List.of());
        // tc_model_param.param_value도 nullable이라 위와 동일한 NPE 패턴을 차단합니다.
        return params.stream()
                .filter(param -> paramName.equalsIgnoreCase(param.paramName()))
                .sorted(Comparator.comparing(UiModelParam::updatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .findFirst()
                .map(UiModelParam::paramValue);
    }

    private boolean matchesAppliedVersion(final String appliedParamVersion, final UiEqpParam param) {
        if (appliedParamVersion == null || appliedParamVersion.isBlank()) {
            return !"EDIT".equalsIgnoreCase(param.paramVersion());
        }
        return appliedParamVersion.equalsIgnoreCase(param.paramVersion());
    }

    private String normalizeProcessTypeValue(final String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return "";
        }
        final String normalized = rawValue.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "CVD", "CMP", "DIFF", "PHOTO", "METRO", "METAL", "ETCH", "IMP", "WET", "ETC" -> normalized;
            // CLN(세정) 공정은 WET 공정과 동일한 의미로 취급해 WET 클러스터로 합칩니다.
            case "CLN" -> "WET";
            default -> "ETC";
        };
    }

    private String normalizeWaferModeValue(final String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return "";
        }
        return rawValue.trim();
    }

    private String normalizeClusterId(final String processType) {
        return processType.toLowerCase(Locale.ROOT);
    }

    private String resolveEquipmentStatus(final String controlState, final String eqpState) {
        final String normalizedControlState = safeUpper(controlState);
        if ("OFFLINE".equals(normalizedControlState)
                || "DOWN".equals(normalizedControlState)
                || "DISCONNECTED".equals(normalizedControlState)) {
            return "DOWN";
        }
        if ("LOCAL".equals(normalizedControlState) || "REMOTE".equals(normalizedControlState)) {
            return switch (safeUpper(eqpState)) {
                case "RUN" -> "RUN";
                case "IDLE" -> "IDLE";
                case "DOWN", "SERVICE_UNAVAILABLE" -> "DOWN";
                case "MAINTENANCE", "PAUSE" -> "ERROR";
                default -> "ERROR";
            };
        }
        return "ERROR";
    }

    private LiveMapPortView toPortView(final UiEqpPortStatus portStatus) {
        final String status = resolvePortStatus(portStatus.portState());
        final String portId = portStatus.portId() == null || portStatus.portId().isBlank()
                ? "PORT"
                : portStatus.portId();
        return new LiveMapPortView(
                portId,
                portId,
                portStatus.carrierId() == null || portStatus.carrierId().isBlank()
                        ? null
                        : portStatus.carrierId(),
                status,
                status
        );
    }

    private String resolvePortStatus(final String portState) {
        return switch (safeUpper(portState)) {
            case "LOAD", "LOADED", "UNLOAD" -> "RUN";
            case "IDLE", "EMPTY", "READY_TO_LOAD" -> "IDLE";
            case "DOWN", "OUT_OF_SERVICE", "UNKNOWN" -> "DOWN";
            default -> "IDLE";
        };
    }

    private String buildTooltipMeta(final List<UiEqpPortStatus> portStatuses) {
        if (portStatuses == null || portStatuses.isEmpty()) {
            return "포트 정보가 없습니다.";
        }

        return portStatuses.stream()
                .sorted(Comparator.comparing(port -> safeUpper(port.portId())))
                .limit(3)
                .map(port -> {
                    final String portId = port.portId() == null || port.portId().isBlank() ? "PORT" : port.portId();
                    final String carrierId = port.carrierId() == null || port.carrierId().isBlank()
                            ? "Empty"
                            : port.carrierId();
                    return portId + " · CARID " + carrierId;
                })
                .reduce((left, right) -> left + " / " + right)
                .orElse("포트 정보가 없습니다.");
    }

    private String buildTooltipStep(final EqpQueryPort.EqpRuntimeStateView runtimeState) {
        return "controlState="
                + defaultStateText(runtimeState.controlState())
                + " / eqpState="
                + defaultStateText(runtimeState.eqpState())
                + " / connectionState="
                + defaultStateText(runtimeState.connectionState());
    }

    private String defaultStateText(final String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private String safeUpper(final String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}

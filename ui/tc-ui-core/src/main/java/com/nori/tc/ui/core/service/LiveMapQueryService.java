package com.nori.tc.ui.core.service;

import com.nori.tc.ui.core.eqp.EqpManagementSnapshot;
import com.nori.tc.ui.core.eqp.LiveMapEquipmentView;
import com.nori.tc.ui.core.eqp.LiveMapPortView;
import com.nori.tc.ui.core.model.PagedResponse;
import com.nori.tc.ui.core.model.UiPageRequest;
import com.nori.tc.ui.core.port.db.EqpManageQueryPort;
import com.nori.tc.ui.core.port.db.EqpQueryPort;
import com.nori.tc.ui.core.port.db.ModelDetailQueryPort;
import com.nori.tc.ui.domain.eqp.UiEqp;
import com.nori.tc.ui.domain.eqp.UiEqpParam;
import com.nori.tc.ui.domain.eqp.UiEqpPortStatus;
import com.nori.tc.ui.domain.model.UiModelParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * live-map 조회 전용 서비스입니다.
 */
@Service
public class LiveMapQueryService {

    private static final Logger log = LoggerFactory.getLogger(LiveMapQueryService.class);
    private static final int LIVE_MAP_LOAD_LIMIT = 1_000;
    private static final String DEFAULT_LINE_VALUE = "A_LINE";
    private static final String DEFAULT_LINE_LABEL = "A Line";
    private static final String PROCESS_TYPE_PARAM_NAME = "PROCESS_TYPE";
    private static final String WAFER_MODE_PARAM_NAME = "WAFER_MODE";

    private final EqpQueryPort eqpQueryPort;
    private final EqpManageQueryPort eqpManageQueryPort;
    private final ModelDetailQueryPort modelDetailQueryPort;

    public LiveMapQueryService(
            final EqpQueryPort eqpQueryPort,
            final EqpManageQueryPort eqpManageQueryPort,
            final ModelDetailQueryPort modelDetailQueryPort
    ) {
        this.eqpQueryPort = Objects.requireNonNull(eqpQueryPort, "eqpQueryPort is null");
        this.eqpManageQueryPort = Objects.requireNonNull(eqpManageQueryPort, "eqpManageQueryPort is null");
        this.modelDetailQueryPort = Objects.requireNonNull(modelDetailQueryPort, "modelDetailQueryPort is null");
    }

    /**
     * live-map 설비 목록을 조회합니다.
     */
    public List<LiveMapEquipmentView> getEquipmentViews() {
        final PagedResponse<UiEqp> page = eqpQueryPort.findAll(UiPageRequest.of(0, LIVE_MAP_LOAD_LIMIT));
        return page.items().stream()
                .filter(UiEqp::enabled)
                .map(this::toEquipmentView)
                .flatMap(Optional::stream)
                .sorted(Comparator.comparing(LiveMapEquipmentView::clusterId).thenComparing(LiveMapEquipmentView::eqpId))
                .toList();
    }

    private Optional<LiveMapEquipmentView> toEquipmentView(final UiEqp eqp) {
        final Optional<EqpManagementSnapshot> snapshotOptional = eqpManageQueryPort.findManageSnapshotByEqpId(eqp.eqpId());
        if (snapshotOptional.isEmpty()) {
            log.warn("live-map 조회에서 관리 스냅샷을 찾지 못했습니다. eqpId={}", eqp.eqpId());
            return Optional.empty();
        }

        final EqpManagementSnapshot snapshot = snapshotOptional.get();
        final String processType = resolveProcessType(snapshot);
        final String clusterId = normalizeClusterId(processType);
        final String clusterName = processType;
        final EqpQueryPort.EqpRuntimeStateView runtimeState = eqpQueryPort.findRuntimeStateByEqpId(eqp.eqpId())
                .orElseGet(() -> new EqpQueryPort.EqpRuntimeStateView(null, null, null));
        final String status = resolveEquipmentStatus(runtimeState.controlState(), runtimeState.eqpState());
        final List<LiveMapPortView> portItems = snapshot.portStatuses().stream()
                .sorted(Comparator.comparing(port -> safeUpper(port.portId())))
                .map(this::toPortView)
                .toList();

        return Optional.of(new LiveMapEquipmentView(
                eqp.eqpId(),
                eqp.eqpId(),
                DEFAULT_LINE_VALUE,
                DEFAULT_LINE_LABEL,
                clusterId,
                clusterName,
                resolveWaferMode(snapshot),
                status,
                status,
                portItems,
                buildTooltipMeta(snapshot.portStatuses()),
                buildTooltipStep(runtimeState),
                status.equals("ERROR") ? "설비 상태값을 확인해 주세요." : null
        ));
    }

    private String resolveProcessType(final EqpManagementSnapshot snapshot) {
        final Optional<String> eqpProcessType = resolveEqpParamValue(snapshot, PROCESS_TYPE_PARAM_NAME)
                .map(this::normalizeProcessTypeValue)
                .filter(value -> !value.isBlank());
        if (eqpProcessType.isPresent()) {
            return eqpProcessType.get();
        }

        return resolveModelParamValue(snapshot.eqp().modelVersionKey(), PROCESS_TYPE_PARAM_NAME)
                .map(this::normalizeProcessTypeValue)
                .filter(value -> !value.isBlank())
                .orElse("ETC");
    }

    private String resolveWaferMode(final EqpManagementSnapshot snapshot) {
        final Optional<String> eqpWaferMode = resolveEqpParamValue(snapshot, WAFER_MODE_PARAM_NAME)
                .map(this::normalizeWaferModeValue)
                .filter(value -> !value.isBlank());
        if (eqpWaferMode.isPresent()) {
            return eqpWaferMode.get();
        }

        return resolveModelParamValue(snapshot.eqp().modelVersionKey(), WAFER_MODE_PARAM_NAME)
                .map(this::normalizeWaferModeValue)
                .filter(value -> !value.isBlank())
                .orElse("-");
    }

    private Optional<String> resolveEqpParamValue(final EqpManagementSnapshot snapshot, final String paramName) {
        return snapshot.params().stream()
                .filter(param -> paramName.equalsIgnoreCase(param.paramName()))
                .filter(param -> matchesAppliedVersion(snapshot.eqp().appliedParamVersion(), param))
                .sorted(Comparator.comparing(UiEqpParam::updatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(UiEqpParam::paramValue)
                .findFirst();
    }

    private Optional<String> resolveModelParamValue(final Long modelVersionKey, final String paramName) {
        return modelDetailQueryPort.findParamsByModelVersionKey(modelVersionKey).stream()
                .filter(param -> paramName.equalsIgnoreCase(param.paramName()))
                .sorted(Comparator.comparing(UiModelParam::updatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(UiModelParam::paramValue)
                .findFirst();
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

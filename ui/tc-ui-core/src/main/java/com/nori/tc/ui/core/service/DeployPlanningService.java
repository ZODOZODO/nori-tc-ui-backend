package com.nori.tc.ui.core.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import com.nori.tc.ui.core.deploy.DeployCurrentState;
import com.nori.tc.ui.core.deploy.DeployDeltaKind;
import com.nori.tc.ui.core.deploy.DeployDesiredState;
import com.nori.tc.ui.core.deploy.DeployPlanCommand;
import com.nori.tc.ui.core.deploy.DeployPlanningResult;
import com.nori.tc.ui.core.deploy.DeployResolvedStepPlan;
import com.nori.tc.ui.core.deploy.DeployScenarioCatalog;
import com.nori.tc.ui.core.deploy.DeployScenarioCondition;
import com.nori.tc.ui.core.deploy.DeployScenarioDefinition;
import com.nori.tc.ui.core.deploy.DeployTargetPlanDraft;
import com.nori.tc.ui.core.exception.UiBadRequestException;
import com.nori.tc.ui.core.exception.UiConflictException;
import com.nori.tc.ui.core.exception.UiNotFoundException;
import com.nori.tc.ui.core.model.UiCommandEventType;
import com.nori.tc.ui.core.port.db.DeployPlanningStatePort;
import com.nori.tc.ui.core.properties.UiDeployProperties;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * deploy request를 target별 current state / desired state / delta / step plan으로 해석합니다.
 */
@Service
public class DeployPlanningService {

    private final UiDeployProperties deployProperties;
    private final DeployPlanningStatePort deployPlanningStatePort;
    private final DeployScenarioCatalogService deployScenarioCatalogService;
    private final ObjectMapper objectMapper;

    public DeployPlanningService(
            final UiDeployProperties deployProperties,
            final DeployPlanningStatePort deployPlanningStatePort,
            final DeployScenarioCatalogService deployScenarioCatalogService,
            final ObjectMapper objectMapper
    ) {
        this.deployProperties = Objects.requireNonNull(deployProperties, "deployProperties is null");
        this.deployPlanningStatePort = Objects.requireNonNull(deployPlanningStatePort, "deployPlanningStatePort is null");
        this.deployScenarioCatalogService = Objects.requireNonNull(deployScenarioCatalogService, "deployScenarioCatalogService is null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is null");
    }

    /**
     * deploy 요청을 planner 결과로 변환합니다.
     *
     * @param command deploy 입력
     * @return planner 결과
     */
    public DeployPlanningResult plan(final DeployPlanCommand command) {
        if (command == null) {
            throw new UiBadRequestException("deploy 요청이 비어 있습니다.");
        }
        if (command.targets().size() > deployProperties.maxTargetsPerRequest()) {
            throw new UiBadRequestException("target 수는 "
                    + deployProperties.maxTargetsPerRequest() + "개 이하여야 합니다.");
        }

        final DeployScenarioCatalog catalog = deployScenarioCatalogService.currentCatalog();
        final List<DeployTargetPlanDraft> targetPlans = new ArrayList<>();

        for (DeployPlanCommand.TargetRequest target : command.targets()) {
            targetPlans.add(planTarget(command, target, catalog));
        }

        return new DeployPlanningResult(
                command.deployName(),
                command.eventType(),
                command.executionPolicy(),
                catalog.catalogVersion(),
                List.copyOf(targetPlans)
        );
    }

    private DeployTargetPlanDraft planTarget(
            final DeployPlanCommand command,
            final DeployPlanCommand.TargetRequest target,
            final DeployScenarioCatalog catalog
    ) {
        final Optional<DeployCurrentState> currentStateOptional =
                deployPlanningStatePort.findCurrentStateByEqpId(target.eqpId());
        validateCurrentStatePrecondition(command.eventType(), target.eqpId(), currentStateOptional);

        final JsonNode mergedUiMessage = mergeUiMessage(
                command.commonRequest().uiMessage(),
                target.uiMessageOverride()
        );
        final DeployDesiredState desiredState = buildDesiredState(
                command.eventType(),
                command.commonRequest().interfaceType(),
                mergedUiMessage
        );
        final Set<DeployDeltaKind> deltaKinds = calculateDeltaKinds(
                command.eventType(),
                currentStateOptional.orElse(null),
                desiredState
        );
        final DeployScenarioDefinition scenario = selectScenario(
                catalog,
                command.eventType(),
                deltaKinds
        );
        final List<DeployResolvedStepPlan> resolvedSteps = scenario == null
                ? List.of()
                : resolveSteps(scenario, deltaKinds);
        final boolean noChange = isNoChange(deltaKinds, resolvedSteps);

        return new DeployTargetPlanDraft(
                target.eqpId(),
                command.commonRequest().interfaceType(),
                currentStateOptional.orElse(null),
                desiredState,
                deltaKinds,
                scenario == null ? null : scenario.scenario().code(),
                scenario == null ? null : scenario.version(),
                resolvedSteps,
                objectMapper.valueToTree(desiredState),
                toDeltaJson(deltaKinds),
                objectMapper.valueToTree(resolvedSteps),
                noChange
        );
    }

    private void validateCurrentStatePrecondition(
            final UiCommandEventType eventType,
            final String eqpId,
            final Optional<DeployCurrentState> currentStateOptional
    ) {
        switch (eventType) {
            case EQP_CREATE -> {
                if (currentStateOptional.isPresent()) {
                    throw new UiConflictException("이미 등록된 설비입니다. eqpId=" + eqpId);
                }
            }
            case EQP_UPDATE,
                 EQP_DELETE,
                 EQP_START,
                 EQP_END,
                 EQP_SEND_MESSAGE,
                 EQP_RECONNECT,
                 EQP_PARAMETER_UPDATE,
                 EQP_GATEWAY_JARFILE_UPDATE,
                 EQP_BUSINESS_JARFILE_UPDATE -> {
                if (currentStateOptional.isEmpty()) {
                    throw new UiNotFoundException("설비를 찾을 수 없습니다. eqpId=" + eqpId);
                }
            }
        }
    }

    private DeployDesiredState buildDesiredState(
            final UiCommandEventType eventType,
            final String interfaceType,
            final JsonNode uiMessage
    ) {
        validateUiMessage(eventType, interfaceType, uiMessage);

        return new DeployDesiredState(
                normalizedText(interfaceType),
                longValue(uiMessage, "modelInfo", "modelVersionKey"),
                longValue(uiMessage, "businessJar", "jarRef"),
                longValue(uiMessage, "gatewayJar", "jarRef"),
                textValue(uiMessage, "eqpParams", "version"),
                textValue(uiMessage, "gatewayProfile", "connectionMode"),
                textValue(uiMessage, "gatewayProfile", "eqpIp"),
                intValue(uiMessage, "gatewayProfile", "eqpPort"),
                textValue(uiMessage, "gatewayProfile", "socketType"),
                intValue(uiMessage, "gatewayProfile", "hsmsDeviceId"),
                uiMessage
        );
    }

    private void validateUiMessage(
            final UiCommandEventType eventType,
            final String interfaceType,
            final JsonNode uiMessage
    ) {
        if (uiMessage == null || !uiMessage.isObject()) {
            throw new UiBadRequestException("uiMessage는 JSON object 여야 합니다.");
        }

        switch (eventType) {
            case EQP_CREATE, EQP_UPDATE -> {
                requireObject(uiMessage, "modelInfo");
                requireLong(uiMessage, "modelInfo", "modelVersionKey");
                requireObject(uiMessage, "gatewayProfile");
                requireText(uiMessage, "gatewayProfile", "connectionMode");
                requireText(uiMessage, "gatewayProfile", "eqpIp");
                requireInt(uiMessage, "gatewayProfile", "eqpPort");
                requireObject(uiMessage, "modelParams");
                requireObject(uiMessage, "eqpParams");
            }
            case EQP_DELETE -> requireText(uiMessage, "deleteMode");
            case EQP_SEND_MESSAGE -> {
                requireText(uiMessage, "messageName");
                if ("SOCKET".equalsIgnoreCase(interfaceType)) {
                    requireText(uiMessage, "rawMessage");
                }
                if ("HSMS".equalsIgnoreCase(interfaceType)) {
                    if (!hasObject(uiMessage, "secs2") && textValue(uiMessage, "rawMessage") == null) {
                        throw new UiBadRequestException("HSMS sendMessage는 secs2 또는 rawMessage가 필요합니다.");
                    }
                }
            }
            case EQP_GATEWAY_JARFILE_UPDATE -> {
                requireObject(uiMessage, "gatewayJar");
                requireLong(uiMessage, "gatewayJar", "jarRef");
            }
            case EQP_PARAMETER_UPDATE -> requireObject(uiMessage, "eqpParams");
            case EQP_BUSINESS_JARFILE_UPDATE -> {
                requireObject(uiMessage, "modelInfo");
                requireLong(uiMessage, "modelInfo", "modelVersionKey");
                requireObject(uiMessage, "businessJar");
                requireLong(uiMessage, "businessJar", "jarRef");
            }
            case EQP_RECONNECT, EQP_START, EQP_END -> {
                // 추가 필수값 없음
            }
        }
    }

    private Set<DeployDeltaKind> calculateDeltaKinds(
            final UiCommandEventType eventType,
            final DeployCurrentState currentState,
            final DeployDesiredState desiredState
    ) {
        final LinkedHashSet<DeployDeltaKind> deltaKinds = new LinkedHashSet<>();

        switch (eventType) {
            case EQP_CREATE -> {
                addIfPresent(deltaKinds, desiredState.modelVersionKey(), DeployDeltaKind.MODEL_CHANGED);
                addIfPresent(deltaKinds, desiredState.businessJarRef(), DeployDeltaKind.BUSINESS_JAR_CHANGED);
                addIfPresent(deltaKinds, desiredState.gatewayJarRef(), DeployDeltaKind.GATEWAY_JAR_CHANGED);
                addIfPresent(deltaKinds, desiredState.eqpParamVersion(), DeployDeltaKind.EQP_PARAM_CHANGED);
                if (desiredState.eqpIp() != null || desiredState.eqpPort() != null) {
                    deltaKinds.add(DeployDeltaKind.CONNECTION_ENDPOINT_CHANGED);
                }
                if (desiredState.connectionMode() != null
                        || desiredState.socketType() != null
                        || desiredState.hsmsDeviceId() != null
                        || desiredState.interfaceType() != null) {
                    deltaKinds.add(DeployDeltaKind.GATEWAY_RUNTIME_SHAPE_CHANGED);
                }
            }
            case EQP_UPDATE -> {
                if (!Objects.equals(currentState.modelVersionKey(), safeLong(desiredState.modelVersionKey()))) {
                    deltaKinds.add(DeployDeltaKind.MODEL_CHANGED);
                }
                if (!Objects.equals(currentState.businessJarRef(), desiredState.businessJarRef())) {
                    deltaKinds.add(DeployDeltaKind.BUSINESS_JAR_CHANGED);
                }
                if (!Objects.equals(currentState.gatewayJarRef(), desiredState.gatewayJarRef())) {
                    deltaKinds.add(DeployDeltaKind.GATEWAY_JAR_CHANGED);
                }
                if (!Objects.equals(normalizedText(currentState.appliedParamVersion()), normalizedText(desiredState.eqpParamVersion()))) {
                    deltaKinds.add(DeployDeltaKind.EQP_PARAM_CHANGED);
                }
                if (!Objects.equals(normalizedText(currentState.eqpIp()), normalizedText(desiredState.eqpIp()))
                        || !Objects.equals(currentState.eqpPort(), safeInt(desiredState.eqpPort()))) {
                    deltaKinds.add(DeployDeltaKind.CONNECTION_ENDPOINT_CHANGED);
                }
                if (!Objects.equals(normalizedText(currentState.interfaceType()), normalizedText(desiredState.interfaceType()))
                        || !Objects.equals(normalizedText(currentState.connectionMode()), normalizedText(desiredState.connectionMode()))
                        || !Objects.equals(normalizedText(currentState.socketType()), normalizedText(desiredState.socketType()))
                        || !Objects.equals(currentState.hsmsDeviceId(), desiredState.hsmsDeviceId())) {
                    deltaKinds.add(DeployDeltaKind.GATEWAY_RUNTIME_SHAPE_CHANGED);
                }
            }
            case EQP_PARAMETER_UPDATE -> {
                if (!Objects.equals(normalizedText(currentState.appliedParamVersion()), normalizedText(desiredState.eqpParamVersion()))) {
                    deltaKinds.add(DeployDeltaKind.EQP_PARAM_CHANGED);
                }
            }
            case EQP_BUSINESS_JARFILE_UPDATE -> {
                if (!Objects.equals(currentState.businessJarRef(), desiredState.businessJarRef())) {
                    deltaKinds.add(DeployDeltaKind.BUSINESS_JAR_CHANGED);
                }
            }
            case EQP_GATEWAY_JARFILE_UPDATE -> {
                if (!Objects.equals(currentState.gatewayJarRef(), desiredState.gatewayJarRef())) {
                    deltaKinds.add(DeployDeltaKind.GATEWAY_JAR_CHANGED);
                }
            }
            case EQP_RECONNECT,
                 EQP_START,
                 EQP_END,
                 EQP_SEND_MESSAGE,
                 EQP_DELETE -> {
                // scenario 고정 이벤트는 별도 delta 없이 진행합니다.
            }
        }

        if (deltaKinds.isEmpty()) {
            return Set.of(DeployDeltaKind.NO_CHANGE);
        }
        return Set.copyOf(deltaKinds);
    }

    private DeployScenarioDefinition selectScenario(
            final DeployScenarioCatalog catalog,
            final UiCommandEventType eventType,
            final Set<DeployDeltaKind> deltaKinds
    ) {
        final List<DeployScenarioDefinition> candidates = catalog.sortedScenarios().stream()
                .filter(definition -> definition.appliesTo().matches(eventType, deltaKinds))
                .sorted(Comparator
                        .comparingInt((DeployScenarioDefinition definition) -> specificityScore(definition.appliesTo().condition()))
                        .reversed()
                        .thenComparing(definition -> definition.scenario().code()))
                .toList();

        if (candidates.isEmpty()) {
            if (deltaKinds.equals(Set.of(DeployDeltaKind.NO_CHANGE))) {
                return null;
            }
            throw new UiBadRequestException("eventType와 delta에 맞는 scenario를 찾을 수 없습니다. eventType="
                    + eventType + ", delta=" + deltaKinds);
        }

        return candidates.getFirst();
    }

    private int specificityScore(final DeployScenarioCondition condition) {
        return condition.deltaAll().size() * 100 + condition.deltaAny().size() * 10 + condition.deltaNone().size();
    }

    private List<DeployResolvedStepPlan> resolveSteps(
            final DeployScenarioDefinition scenario,
            final Set<DeployDeltaKind> deltaKinds
    ) {
        return scenario.steps().stream()
                .filter(step -> step.when().matches(deltaKinds))
                .sorted(Comparator.comparingInt(DeployScenarioDefinition.Step::order))
                .map(step -> new DeployResolvedStepPlan(
                        step.order(),
                        step.code(),
                        step.name(),
                        step.targetApp(),
                        step.description(),
                        step.run().action(),
                        step.run().timeoutMs(),
                        step.run().replyRequired(),
                        step.run().retry(),
                        step.rollback().enabled(),
                        step.rollback().mode(),
                        step.rollback().action()
                ))
                .toList();
    }

    private JsonNode toDeltaJson(final Set<DeployDeltaKind> deltaKinds) {
        final ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.put("noChange", deltaKinds.equals(Set.of(DeployDeltaKind.NO_CHANGE)));
        root.set("deltaKinds", objectMapper.valueToTree(deltaKinds.stream()
                .map(Enum::name)
                .sorted()
                .toList()));
        return root;
    }

    private boolean isNoChange(
            final Set<DeployDeltaKind> deltaKinds,
            final List<DeployResolvedStepPlan> resolvedSteps
    ) {
        return deltaKinds.equals(Set.of(DeployDeltaKind.NO_CHANGE)) && resolvedSteps.isEmpty();
    }

    private JsonNode mergeUiMessage(
            final JsonNode commonUiMessage,
            final JsonNode overrideUiMessage
    ) {
        final ObjectNode merged;
        if (commonUiMessage == null || commonUiMessage.isNull()) {
            merged = objectMapper.createObjectNode();
        } else if (commonUiMessage.isObject()) {
            merged = (ObjectNode) commonUiMessage.deepCopy();
        } else {
            throw new UiBadRequestException("공통 uiMessage는 JSON object 여야 합니다.");
        }
        if (overrideUiMessage == null || overrideUiMessage.isNull()) {
            return merged;
        }
        if (!overrideUiMessage.isObject()) {
            throw new UiBadRequestException("uiMessageOverride는 JSON object 여야 합니다.");
        }
        deepMerge(merged, overrideUiMessage);
        return merged;
    }

    private void deepMerge(
            final ObjectNode destination,
            final JsonNode source
    ) {
        source.properties().forEach(entry -> {
            final String fieldName = entry.getKey();
            final JsonNode sourceValue = entry.getValue();
            final JsonNode existingValue = destination.get(fieldName);
            if (existingValue != null && existingValue.isObject() && sourceValue.isObject()) {
                deepMerge((ObjectNode) existingValue, sourceValue);
                return;
            }
            destination.set(fieldName, sourceValue.deepCopy());
        });
    }

    private static void addIfPresent(
            final Set<DeployDeltaKind> deltaKinds,
            final Object value,
            final DeployDeltaKind deltaKind
    ) {
        if (value != null) {
            deltaKinds.add(deltaKind);
        }
    }

    private static void requireObject(
            final JsonNode parent,
            final String fieldName
    ) {
        if (!hasObject(parent, fieldName)) {
            throw new UiBadRequestException(fieldName + " 객체가 필요합니다.");
        }
    }

    private static boolean hasObject(
            final JsonNode parent,
            final String fieldName
    ) {
        final JsonNode node = parent.get(fieldName);
        return node != null && node.isObject();
    }

    private static void requireText(
            final JsonNode parent,
            final String fieldName
    ) {
        if (textValue(parent, fieldName) == null) {
            throw new UiBadRequestException(fieldName + " 값이 필요합니다.");
        }
    }

    private static void requireText(
            final JsonNode parent,
            final String objectField,
            final String fieldName
    ) {
        if (textValue(parent, objectField, fieldName) == null) {
            throw new UiBadRequestException(objectField + "." + fieldName + " 값이 필요합니다.");
        }
    }

    private static void requireLong(
            final JsonNode parent,
            final String objectField,
            final String fieldName
    ) {
        if (longValue(parent, objectField, fieldName) == null) {
            throw new UiBadRequestException(objectField + "." + fieldName + " 값이 필요합니다.");
        }
    }

    private static void requireInt(
            final JsonNode parent,
            final String objectField,
            final String fieldName
    ) {
        if (intValue(parent, objectField, fieldName) == null) {
            throw new UiBadRequestException(objectField + "." + fieldName + " 값이 필요합니다.");
        }
    }

    private static String textValue(
            final JsonNode parent,
            final String fieldName
    ) {
        if (parent == null || !parent.isObject()) {
            return null;
        }
        final JsonNode node = parent.get(fieldName);
        if (node == null || node.isNull()) {
            return null;
        }
        final String raw = node.asString(null);
        return normalizedText(raw);
    }

    private static String textValue(
            final JsonNode parent,
            final String objectField,
            final String fieldName
    ) {
        if (parent == null || !parent.isObject()) {
            return null;
        }
        final JsonNode objectNode = parent.get(objectField);
        if (objectNode == null || !objectNode.isObject()) {
            return null;
        }
        return textValue(objectNode, fieldName);
    }

    private static Long longValue(
            final JsonNode parent,
            final String objectField,
            final String fieldName
    ) {
        if (parent == null || !parent.isObject()) {
            return null;
        }
        final JsonNode objectNode = parent.get(objectField);
        if (objectNode == null || !objectNode.isObject()) {
            return null;
        }
        final JsonNode valueNode = objectNode.get(fieldName);
        if (valueNode == null || valueNode.isNull()) {
            return null;
        }
        if (!valueNode.canConvertToLong()) {
            throw new UiBadRequestException(objectField + "." + fieldName + " 값은 숫자여야 합니다.");
        }
        return valueNode.longValue();
    }

    private static Integer intValue(
            final JsonNode parent,
            final String objectField,
            final String fieldName
    ) {
        if (parent == null || !parent.isObject()) {
            return null;
        }
        final JsonNode objectNode = parent.get(objectField);
        if (objectNode == null || !objectNode.isObject()) {
            return null;
        }
        final JsonNode valueNode = objectNode.get(fieldName);
        if (valueNode == null || valueNode.isNull()) {
            return null;
        }
        if (!valueNode.canConvertToInt()) {
            throw new UiBadRequestException(objectField + "." + fieldName + " 값은 숫자여야 합니다.");
        }
        return valueNode.intValue();
    }

    private static String normalizedText(final String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static Long safeLong(final Long value) {
        return value;
    }

    private static Integer safeInt(final Integer value) {
        return value;
    }
}

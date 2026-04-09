package com.nori.tc.ui.core.service;

import tools.jackson.databind.JsonNode;
import com.nori.tc.ui.core.deploy.DeployResolvedStepPlan;
import com.nori.tc.ui.core.deploy.DeployResultMessage;
import com.nori.tc.ui.core.deploy.DeployScenarioAction;
import com.nori.tc.ui.core.deploy.DeployStatus;
import com.nori.tc.ui.core.deploy.DeployTargetCounts;
import com.nori.tc.ui.core.deploy.DeployTargetStatus;
import com.nori.tc.ui.core.deploy.PersistedDeployTarget;
import com.nori.tc.ui.core.eqp.EqpManagementSnapshot;
import com.nori.tc.ui.core.model.UiCommandEventType;
import com.nori.tc.ui.core.model.UiEquipmentProfileSnapshot;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Deploy command 조립/요약 지원을 분리합니다.
 */
@Service
public class DeployCommandAssembler {

    private final DeployServiceCommandSupport commandSupport;

    public DeployCommandAssembler(final DeployServiceCommandSupport commandSupport) {
        this.commandSupport = Objects.requireNonNull(commandSupport, "commandSupport is null");
    }

    public String normalizeActor(final String actor) {
        return commandSupport.normalizeActor(actor);
    }

    public DeployResultMessage completedMessage(final DeployTargetCounts counts) {
        return commandSupport.completedMessage(counts);
    }

    public boolean isTerminal(final DeployStatus deployStatus) {
        return commandSupport.isTerminal(deployStatus);
    }

    public String resolveResultCode(
            final DeployStatus deployStatus,
            final DeployTargetCounts counts
    ) {
        return commandSupport.resolveResultCode(deployStatus, counts);
    }

    public DeployResultMessage resolveSummaryMessage(
            final DeployStatus deployStatus,
            final DeployTargetCounts counts
    ) {
        return commandSupport.resolveSummaryMessage(deployStatus, counts);
    }

    public DeployTargetStatus parseTargetStatus(final String rawStatus) {
        return commandSupport.parseTargetStatus(rawStatus);
    }

    public String normalizeOptionalText(final String value) {
        return commandSupport.normalizeOptionalText(value);
    }

    public ThreadFactory namedThreadFactory(
            final String prefix,
            final AtomicInteger targetExecutorThreadSequence
    ) {
        return commandSupport.namedThreadFactory(prefix, targetExecutorThreadSequence);
    }

    public void refreshDeployStatus(
            final long deployKey,
            final String lastErrorCode,
            final String lastErrorMessage
    ) {
        commandSupport.refreshDeployStatus(deployKey, lastErrorCode, lastErrorMessage);
    }

    public Optional<JsonNode> captureTargetPreState(final PersistedDeployTarget target) {
        return commandSupport.captureTargetPreState(target);
    }

    public void saveCompensationSnapshot(
            final PersistedDeployTarget target,
            final DeployResolvedStepPlan step,
            final JsonNode targetPreState
    ) {
        commandSupport.saveCompensationSnapshot(target, step, targetPreState);
    }

    public String buildTraceId(
            final long deployKey,
            final long deployTargetKey,
            final String stepCode,
            final int attemptNo
    ) {
        return commandSupport.buildTraceId(deployKey, deployTargetKey, stepCode, attemptNo);
    }

    public Set<String> expectedSources(final DeployResolvedStepPlan step) {
        return commandSupport.expectedSources(step);
    }

    public Set<String> expectedSources(final com.nori.tc.ui.core.deploy.DeployTargetApp targetApp) {
        return commandSupport.expectedSources(targetApp);
    }

    public void publishStepCommand(
            final long deployKey,
            final UiCommandEventType deployEventType,
            final PersistedDeployTarget target,
            final DeployResolvedStepPlan step,
            final String traceId,
            final String actor
    ) {
        commandSupport.publishStepCommand(deployKey, deployEventType, target, step, traceId, actor);
    }

    public void publishCommand(
            final long deployKey,
            final com.nori.tc.ui.core.deploy.DeployTargetApp targetApp,
            final UiCommandEventType eventType,
            final PersistedDeployTarget target,
            final String interfaceType,
            final JsonNode uiMessage,
            final UiEquipmentProfileSnapshot equipmentProfile,
            final String traceId,
            final String actor
    ) {
        commandSupport.publishCommand(deployKey, targetApp, eventType, target, interfaceType, uiMessage, equipmentProfile, traceId, actor);
    }

    public void publishDirectCommand(
            final com.nori.tc.ui.core.deploy.DeployTargetApp targetApp,
            final UiCommandEventType eventType,
            final String eqpId,
            final String interfaceType,
            final JsonNode uiMessage,
            final UiEquipmentProfileSnapshot equipmentProfile,
            final String traceId
    ) {
        commandSupport.publishDirectCommand(targetApp, eventType, eqpId, interfaceType, uiMessage, equipmentProfile, traceId);
    }

    public UiCommandEventType resolveMessageEventType(
            final UiCommandEventType deployEventType,
            final DeployScenarioAction action
    ) {
        return commandSupport.resolveMessageEventType(deployEventType, action);
    }

    public JsonNode buildDirectStepUiMessage(
            final String executionId,
            final UiCommandEventType eventType,
            final String eqpId,
            final DeployResolvedStepPlan step,
            final String traceId,
            final String actor,
            final JsonNode mergedUiMessage
    ) {
        return commandSupport.buildDirectStepUiMessage(executionId, eventType, eqpId, step, traceId, actor, mergedUiMessage);
    }

    public UiEquipmentProfileSnapshot buildDirectEquipmentProfile(
            final UiCommandEventType messageEventType,
            final String eqpId,
            final String interfaceType,
            final Long eqpKey,
            final JsonNode stepUiMessage
    ) {
        return commandSupport.buildDirectEquipmentProfile(messageEventType, eqpId, interfaceType, eqpKey, stepUiMessage);
    }

    public UiEquipmentProfileSnapshot buildDirectEquipmentProfile(
            final UiCommandEventType messageEventType,
            final EqpManagementSnapshot executionSnapshot,
            final JsonNode stepUiMessage
    ) {
        return commandSupport.buildDirectEquipmentProfile(messageEventType, executionSnapshot, stepUiMessage);
    }

    public UiCommandEventType parseCompensationEventType(final String eventTypeText) {
        return commandSupport.parseCompensationEventType(eventTypeText);
    }

    public UiEquipmentProfileSnapshot parseEquipmentProfile(final JsonNode equipmentProfileNode) {
        return commandSupport.parseEquipmentProfile(equipmentProfileNode);
    }
}

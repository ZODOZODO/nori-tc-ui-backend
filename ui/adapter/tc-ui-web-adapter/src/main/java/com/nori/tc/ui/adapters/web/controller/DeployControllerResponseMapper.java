package com.nori.tc.ui.adapters.web.controller;

import com.nori.tc.ui.adapters.web.dto.response.DeployActionResponse;
import com.nori.tc.ui.adapters.web.dto.response.DeployCreateResponse;
import com.nori.tc.ui.adapters.web.dto.response.DeployHistoryPageResponse;
import com.nori.tc.ui.adapters.web.dto.response.DeployRegisterScenarioPreviewResponse;
import com.nori.tc.ui.adapters.web.dto.response.DeployRegisterTargetPageResponse;
import com.nori.tc.ui.adapters.web.dto.response.DeployResultMessageResponse;
import com.nori.tc.ui.adapters.web.dto.response.DeployScenarioCatalogResponse;
import com.nori.tc.ui.adapters.web.dto.response.DeploySummaryResponse;
import com.nori.tc.ui.adapters.web.dto.response.DeployTargetPageResponse;
import com.nori.tc.ui.core.deploy.DeployActionResult;
import com.nori.tc.ui.core.deploy.DeployCreateResult;
import com.nori.tc.ui.core.deploy.DeployDeleteResult;
import com.nori.tc.ui.core.deploy.DeployHistoryView;
import com.nori.tc.ui.core.deploy.DeployRegisterScenarioPreviewResult;
import com.nori.tc.ui.core.deploy.DeployRegisterTargetView;
import com.nori.tc.ui.core.deploy.DeployResultMessage;
import com.nori.tc.ui.core.deploy.DeployScenarioDefinition;
import com.nori.tc.ui.core.deploy.DeployStateOverview;
import com.nori.tc.ui.core.deploy.DeploySummaryView;
import com.nori.tc.ui.core.deploy.DeployTargetCounts;
import com.nori.tc.ui.core.deploy.DeployTargetPage;
import com.nori.tc.ui.core.deploy.DeployTargetSnapshot;
import com.nori.tc.ui.core.model.PagedResponse;

import java.time.OffsetDateTime;
import java.util.List;

final class DeployControllerResponseMapper {

    DeployCreateResponse toCreateResponse(final DeployCreateResult result) {
        final long deployKey = result.deployKey();
        return new DeployCreateResponse(
                deployKey,
                result.deployStatus().name(),
                new DeployCreateResponse.PollingResponse(
                        "/api/deploys/" + deployKey,
                        "/api/deploys/" + deployKey + "/targets"
                ),
                result.resultCode(),
                toResultMessage(result.resultMessage())
        );
    }

    DeployActionResponse toActionResponse(final DeployActionResult result) {
        return new DeployActionResponse(
                result.deployKey(),
                result.deployStatus().name(),
                result.pauseRequested(),
                null,
                result.resultCode(),
                toResultMessage(result.resultMessage())
        );
    }

    DeployActionResponse toDeleteActionResponse(final DeployDeleteResult result) {
        return new DeployActionResponse(
                result.deployKey(),
                null,
                null,
                result.deleted(),
                result.resultCode(),
                toResultMessage(result.resultMessage())
        );
    }

    DeployRegisterScenarioPreviewResponse toRegisterScenarioPreviewResponse(
            final DeployRegisterScenarioPreviewResult result
    ) {
        return new DeployRegisterScenarioPreviewResponse(
                result.removedEqpIds(),
                result.remainingEqpIds(),
                result.unionChangeKinds().stream().map(Enum::name).sorted().toList(),
                result.recommendedScenarioCode(),
                result.recommendedReason(),
                result.scenarios().stream()
                        .map(candidate -> new DeployRegisterScenarioPreviewResponse.ScenarioCandidate(
                                candidate.code(),
                                candidate.name(),
                                candidate.summary(),
                                candidate.version(),
                                candidate.stepCount(),
                                candidate.coversChangeKinds().stream().map(Enum::name).sorted().toList(),
                                candidate.recommended(),
                                candidate.selectable(),
                                candidate.reason()
                        ))
                        .toList()
        );
    }

    DeploySummaryResponse toSummaryResponse(final DeploySummaryView summary) {
        return new DeploySummaryResponse(
                summary.deployKey(),
                summary.deployName(),
                summary.eventType().name(),
                summary.resolvedScenarioCode(),
                toStateOverviewResponse(summary.asIsState()),
                summary.deployStatus().name(),
                summary.pauseRequested(),
                summary.executionPolicy().name(),
                toIso(summary.createdAt()),
                toIso(summary.startedAt()),
                toIso(summary.finishedAt()),
                toTargetSummaryResponse(summary.targetSummary()),
                summary.terminal(),
                summary.popupRequired(),
                summary.resultCode(),
                toResultMessage(summary.resultMessage())
        );
    }

    DeployTargetPageResponse toTargetPageResponse(final DeployTargetPage page) {
        final List<DeployTargetPageResponse.DeployTargetResponse> targets = page.targets().stream()
                .map(this::toTargetResponse)
                .toList();

        return new DeployTargetPageResponse(
                page.deployKey(),
                page.page(),
                page.size(),
                page.totalElements(),
                page.totalPages(),
                targets
        );
    }

    DeployRegisterTargetPageResponse toRegisterTargetPageResponse(
            final PagedResponse<DeployRegisterTargetView> page
    ) {
        return new DeployRegisterTargetPageResponse(
                page.items().stream()
                        .map(this::toRegisterTargetResponse)
                        .toList(),
                page.offset(),
                page.limit(),
                page.count()
        );
    }

    DeployHistoryPageResponse toHistoryPageResponse(final PagedResponse<DeployHistoryView> page) {
        return new DeployHistoryPageResponse(
                page.items().stream()
                        .map(this::toHistoryResponse)
                        .toList(),
                page.offset(),
                page.limit(),
                page.count()
        );
    }

    DeployScenarioCatalogResponse.ScenarioSummary toScenarioSummary(
            final DeployScenarioDefinition definition
    ) {
        return new DeployScenarioCatalogResponse.ScenarioSummary(
                definition.scenario().code(),
                definition.scenario().name(),
                definition.scenario().summary(),
                definition.version(),
                definition.steps().size()
        );
    }

    private DeploySummaryResponse.StateOverviewResponse toStateOverviewResponse(final DeployStateOverview overview) {
        if (overview == null) {
            return null;
        }
        return new DeploySummaryResponse.StateOverviewResponse(
                overview.interfaceType(),
                overview.modelName(),
                overview.modelVersion(),
                overview.businessJarName(),
                overview.eqpParamVersion()
        );
    }

    private DeploySummaryResponse.TargetSummaryResponse toTargetSummaryResponse(final DeployTargetCounts counts) {
        return new DeploySummaryResponse.TargetSummaryResponse(
                counts.total(),
                counts.pending(),
                counts.noChange(),
                counts.running(),
                counts.completed(),
                counts.failed(),
                counts.rollingBack(),
                counts.rolledBack(),
                counts.compensationFailed(),
                counts.canceled()
        );
    }

    private DeployTargetPageResponse.DeployTargetResponse toTargetResponse(final DeployTargetSnapshot target) {
        return new DeployTargetPageResponse.DeployTargetResponse(
                target.deployTargetKey(),
                target.eqpId(),
                target.targetStatus().name(),
                target.currentStepOrder(),
                target.currentStepCode(),
                target.currentStepStatus() == null ? null : target.currentStepStatus().name(),
                target.rollbackRequired(),
                target.lastErrorCode(),
                target.lastErrorMessage(),
                toIso(target.updatedAt())
        );
    }

    private DeployRegisterTargetPageResponse.DeployRegisterTargetResponse toRegisterTargetResponse(
            final DeployRegisterTargetView item
    ) {
        return new DeployRegisterTargetPageResponse.DeployRegisterTargetResponse(
                item.eqpKey(),
                item.eqpId(),
                item.commInterface(),
                item.enabled(),
                item.modelVersionKey(),
                item.modelName(),
                item.modelVersion(),
                item.modelStatus(),
                item.businessJarRef(),
                item.businessJarFileName(),
                item.appliedParamVersion(),
                item.blockedByActiveDeploy(),
                item.activeDeployKey(),
                item.activeDeployStatus(),
                item.activeDeployName(),
                item.activeRequestedBy()
        );
    }

    private DeployHistoryPageResponse.DeployHistoryResponse toHistoryResponse(final DeployHistoryView item) {
        return new DeployHistoryPageResponse.DeployHistoryResponse(
                item.deployKey(),
                item.deployName(),
                item.eventType().name(),
                item.deployStatus().name(),
                item.pauseRequested(),
                item.executionPolicy().name(),
                item.requestedBy(),
                toIso(item.createdAt()),
                toIso(item.startedAt()),
                toIso(item.finishedAt()),
                item.terminal(),
                item.interfaceType(),
                item.selectedModelName(),
                item.selectedModelVersion(),
                item.selectedBusinessJarName(),
                toTargetSummaryResponse(item.targetSummary())
        );
    }

    private DeployResultMessageResponse toResultMessage(final DeployResultMessage resultMessage) {
        return new DeployResultMessageResponse(
                resultMessage.title(),
                resultMessage.message(),
                resultMessage.severity().name()
        );
    }

    private static String toIso(final OffsetDateTime value) {
        return value == null ? null : value.toString();
    }
}

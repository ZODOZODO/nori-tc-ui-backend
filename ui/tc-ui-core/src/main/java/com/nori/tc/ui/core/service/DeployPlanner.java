package com.nori.tc.ui.core.service;

import com.nori.tc.ui.core.deploy.DeployPlanCommand;
import com.nori.tc.ui.core.deploy.DeployPlanningResult;
import com.nori.tc.ui.core.deploy.DeployRegisterScenarioPreviewCommand;
import com.nori.tc.ui.core.deploy.DeployRegisterScenarioPreviewResult;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * Deploy planning 진입점을 분리합니다.
 */
@Service
public class DeployPlanner {

    private final DeployPlanningService deployPlanningService;

    public DeployPlanner(final DeployPlanningService deployPlanningService) {
        this.deployPlanningService = Objects.requireNonNull(deployPlanningService, "deployPlanningService is null");
    }

    public DeployPlanningResult plan(final DeployPlanCommand command) {
        return deployPlanningService.plan(command);
    }

    public DeployRegisterScenarioPreviewResult previewRegisterScenarioSelection(
            final DeployRegisterScenarioPreviewCommand command
    ) {
        return deployPlanningService.previewRegisterScenarioSelection(command);
    }
}

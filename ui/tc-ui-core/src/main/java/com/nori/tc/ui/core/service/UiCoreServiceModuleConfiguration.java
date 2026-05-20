package com.nori.tc.ui.core.service;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * UI core service bean graph를 같은 패키지에서 명시적으로 고정합니다.
 */
@Configuration(proxyBeanMethods = false)
@Import({
        DeployCommandAssembler.class,
        DeployEqpFinalizationService.class,
        DeployExecutor.class,
        DeployPlanner.class,
        DeployPlanningService.class,
        DeployRollbackCoordinator.class,
        DeployScenarioCatalogService.class,
        DeployService.class,
        DeployServiceCommandSupport.class,
        DeployTraceRecorder.class,
        DeployTraceRegistry.class,
        EqpDirectScenarioLocalMutationCoordinator.class,
        EqpDirectScenarioPlanner.class,
        EqpDirectScenarioRollbackService.class,
        EqpDirectScenarioRunner.class,
        EqpDirectScenarioService.class,
        EqpManagementCommandValidator.class,
        EqpManagementService.class,
        EqpPersistenceService.class,
        EqpRepairTaskService.class,
        CommLogQueryService.class,
        LiveMapQueryService.class,
        ModelFlowQueryService.class,
        UiCoreFallbackStorageConfiguration.class,
        UiCommandIngressService.class
})
public class UiCoreServiceModuleConfiguration {
}

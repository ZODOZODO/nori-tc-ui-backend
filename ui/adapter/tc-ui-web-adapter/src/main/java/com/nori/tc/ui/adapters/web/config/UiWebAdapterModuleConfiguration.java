package com.nori.tc.ui.adapters.web.config;

import com.nori.tc.ui.adapters.web.application.admin.GroupAdminAssembler;
import com.nori.tc.ui.adapters.web.application.admin.GroupAdminFacade;
import com.nori.tc.ui.adapters.web.application.admin.PermissionAdminAssembler;
import com.nori.tc.ui.adapters.web.application.admin.PermissionAdminFacade;
import com.nori.tc.ui.adapters.web.application.admin.UserAdminAssembler;
import com.nori.tc.ui.adapters.web.application.admin.UserAdminFacade;
import com.nori.tc.ui.adapters.web.controller.AsyncResultController;
import com.nori.tc.ui.adapters.web.controller.AuthController;
import com.nori.tc.ui.adapters.web.controller.CommLogController;
import com.nori.tc.ui.adapters.web.controller.DeployController;
import com.nori.tc.ui.adapters.web.controller.DeployScenarioAdminController;
import com.nori.tc.ui.adapters.web.controller.DlqController;
import com.nori.tc.ui.adapters.web.controller.EqpController;
import com.nori.tc.ui.adapters.web.controller.EqpParamController;
import com.nori.tc.ui.adapters.web.controller.GatewayShardLeaseController;
import com.nori.tc.ui.adapters.web.controller.GroupController;
import com.nori.tc.ui.adapters.web.controller.LineController;
import com.nori.tc.ui.adapters.web.controller.LiveMapController;
import com.nori.tc.ui.adapters.web.controller.LiveMapLayoutController;
import com.nori.tc.ui.adapters.web.controller.ModelBranchController;
import com.nori.tc.ui.adapters.web.controller.ModelDetailController;
import com.nori.tc.ui.adapters.web.controller.ModelFlowController;
import com.nori.tc.ui.adapters.web.controller.ModelMutationController;
import com.nori.tc.ui.adapters.web.controller.ModelQueryController;
import com.nori.tc.ui.adapters.web.controller.PermissionController;
import com.nori.tc.ui.adapters.web.controller.UserController;
import com.nori.tc.ui.adapters.web.controller.support.UiApiExceptionHandler;
import com.nori.tc.ui.adapters.web.controller.support.UiAuthCookieFactory;
import com.nori.tc.ui.adapters.web.security.BcryptPasswordVerifier;
import com.nori.tc.ui.adapters.web.security.UiApiPermissionCache;
import com.nori.tc.ui.adapters.web.security.UiAuthenticationEntryPoint;
import com.nori.tc.ui.adapters.web.security.UiSecurityConfig;
import com.nori.tc.ui.adapters.web.security.UiTokenAuthenticationFilter;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * UI Web adapter bean graph를 명시적으로 고정합니다.
 */
@Configuration(proxyBeanMethods = false)
@Import({
        UiSimulatorConfiguration.class,
        UiSecurityPolicyConfiguration.class,
        GroupAdminAssembler.class,
        GroupAdminFacade.class,
        PermissionAdminAssembler.class,
        PermissionAdminFacade.class,
        UserAdminAssembler.class,
        UserAdminFacade.class,
        AsyncResultController.class,
        AuthController.class,
        CommLogController.class,
        DeployController.class,
        DeployScenarioAdminController.class,
        DlqController.class,
        EqpController.class,
        EqpParamController.class,
        GatewayShardLeaseController.class,
        GroupController.class,
        LineController.class,
        LiveMapController.class,
        LiveMapLayoutController.class,
        ModelBranchController.class,
        ModelDetailController.class,
        ModelFlowController.class,
        ModelMutationController.class,
        ModelQueryController.class,
        PermissionController.class,
        UserController.class,
        UiApiExceptionHandler.class,
        UiAuthCookieFactory.class,
        BcryptPasswordVerifier.class,
        UiApiPermissionCache.class,
        UiAuthenticationEntryPoint.class,
        UiSecurityConfig.class,
        UiTokenAuthenticationFilter.class
})
public class UiWebAdapterModuleConfiguration {
}

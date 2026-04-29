package com.nori.tc.ui.adapter.db;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * UI DB adapter bean graph를 명시적으로 고정합니다.
 *
 * <p>package-private 지원 컴포넌트도 함께 조립해야 하므로 같은 패키지에 둡니다.</p>
 */
@Configuration(proxyBeanMethods = false)
@Import({
        DemoJobJdbcCleanupPort.class,
        DeployJdbcStorePort.class,
        DeployPlanningStateJdbcPort.class,
        DeployScenarioCatalogMetaJdbcPort.class,
        EqpManagementDbSupport.class,
        JpaDeployRegisterQueryPort.class,
        JpaApiPermissionPort.class,
        JpaEqpCrudPort.class,
        JpaEqpManageQueryPort.class,
        JpaEqpOptionsQueryPort.class,
        JpaEqpParamCommandPort.class,
        JpaEqpQueryPort.class,
        JpaGroupCrudPort.class,
        JpaGroupPermissionMappingPort.class,
        JpaModelCrudPort.class,
        JpaModelDetailCommandPort.class,
        JpaModelDetailQueryPort.class,
        JpaModelManagementPort.class,
        JpaPermissionCrudPort.class,
        JpaPermissionPort.class,
        JpaSessionPort.class,
        JpaUserCrudPort.class,
        JpaUserGroupMappingPort.class,
        JpaUserPort.class,
        UiEqpRoutePartitionDbAdapter.class
})
public class UiDbAdapterModuleConfiguration {
}

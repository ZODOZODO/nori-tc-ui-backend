package com.nori.tc.ui.starter;

import com.nori.tc.common.coordination.script.CoordinationScriptExecutor;
import com.nori.tc.common.coordination.store.CatalogHeadRedisStore;
import com.nori.tc.common.coordination.store.CoordinationOutcome;
import com.nori.tc.common.coordination.store.ClusterEpochRedisStore;
import com.nori.tc.common.coordination.store.FencedLeaseRedisStore;
import com.nori.tc.common.coordination.store.MembershipRedisStore;
import com.nori.tc.common.coordination.store.OperationLedgerRedisStore;
import com.nori.tc.common.coordination.store.ResourceBarrierRedisStore;
import com.nori.tc.common.coordination.types.CatalogHead;
import com.nori.tc.common.coordination.types.ClusterEpoch;
import com.nori.tc.common.shard.SharedShardGroupProperties;
import com.nori.tc.ui.core.port.redis.GatewayShardLeasePort;
import com.nori.tc.ui.core.port.redis.RuntimeInstanceQueryPort;
import com.nori.tc.ui.adapters.redis.coordination.UiCoordinationContext;
import com.nori.tc.ui.core.coordination.DeployOperationCoordinator;
import com.nori.tc.ui.core.coordination.UiCoordinationRuntime;
import com.nori.tc.ui.core.coordination.UiCoordinationSession;
import com.nori.tc.ui.core.coordination.UiCoordinationStatus;
import com.nori.tc.ui.core.coordination.UiEqpMutationCoordinator;
import com.nori.tc.ui.core.coordination.UiOperationExecutor;
import com.nori.tc.ui.core.coordination.UiOperationQueryService;
import com.nori.tc.ui.core.coordination.UiOperationReconciler;
import com.nori.tc.ui.core.coordination.UiPermissionCatalogGuard;
import com.nori.tc.ui.core.coordination.UiReplyProofIngestService;
import com.nori.tc.ui.core.coordination.UiScenarioCatalogGuard;
import com.nori.tc.ui.core.coordination.port.UiFinalizedRevisionProvider;
import com.nori.tc.ui.core.coordination.port.UiOperationCommandPublisher;
import com.nori.tc.ui.core.port.db.BusinessJarDigestPort;
import com.nori.tc.ui.core.port.db.EqpManageQueryPort;
import com.nori.tc.ui.core.port.db.EqpQueryPort;
import com.nori.tc.ui.core.port.messaging.UiGatewayEqpRoutePartitionLookupPort;
import com.nori.tc.ui.core.port.messaging.UiBusinessEventPublishPort;
import com.nori.tc.ui.core.port.messaging.UiGatewayEventPublishPort;
import com.nori.tc.ui.core.port.redis.AsyncResultStorePort;
import com.nori.tc.ui.core.port.db.DeployStorePort;
import com.nori.tc.ui.core.port.redis.DeployExecutionCoordinationPort;
import com.nori.tc.ui.core.service.DeployCommandAssembler;
import com.nori.tc.ui.core.service.EqpDirectScenarioLocalMutationCoordinator;
import com.nori.tc.ui.core.service.EqpManagementCommandValidator;
import com.nori.tc.ui.core.service.EqpPersistenceService;
import com.nori.tc.ui.core.service.UiDeployOperationDbFinalizer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 조정 빈 그래프가 실제로 조립되는지 검증합니다(§13).
 *
 * <p>단위 테스트는 협력자를 손으로 조립하므로 빈 정의가 빠져도 통과하고, 실패는 앱 부팅에서만
 * 드러납니다 — P2 에서 같은 간극이 {@code NoUniqueBeanDefinitionException} 으로 실제 발생했습니다.
 * 여기서 그 간극을 막습니다.</p>
 */
class UiCoordinationConfigurationContextTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AdapterStubs.class, UiCoordinationConfiguration.class);

    @Test
    @DisplayName("조정 빈 그래프가 단일 인스턴스로 조립된다")
    void coordinationGraphAssembles() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(UiOperationExecutor.class);
            assertThat(context).hasSingleBean(UiOperationReconciler.class);
            assertThat(context).hasSingleBean(UiReplyProofIngestService.class);
            assertThat(context).hasSingleBean(UiOperationQueryService.class);
            assertThat(context).hasSingleBean(UiEqpMutationCoordinator.class);
            assertThat(context).hasSingleBean(UiScenarioCatalogGuard.class);
            assertThat(context).hasSingleBean(UiPermissionCatalogGuard.class);
            assertThat(context).hasSingleBean(UiOperationCommandPublisher.class);
            assertThat(context).hasSingleBean(UiCoordinationRuntime.class);
            assertThat(context).hasSingleBean(UiCoordinationStatus.class);
            assertThat(context).hasSingleBean(DeployExecutionCoordinationPort.class);
            assertThat(context).hasSingleBean(DeployOperationCoordinator.class);
            /*
             * shard lease 조회는 조정 구현만 등록되어야 한다 — 레거시 raw String 키 구현이 함께 뜨면
             * 후보 2 개로 기동이 깨지고, 조정 구현이 빠지면 전 group 이 미점유로 오표시된다.
             */
            assertThat(context).hasSingleBean(GatewayShardLeasePort.class);
            assertThat(context).hasSingleBean(RuntimeInstanceQueryPort.class);
        });
    }

    @Test
    @DisplayName("enabled=false 면 조정 빈을 하나도 만들지 않는다")
    void disabledPropertyRemovesTheWholeGraph() {
        runner.withPropertyValues("tc.ui.backend.coordination.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(UiOperationExecutor.class);
                    assertThat(context).doesNotHaveBean(UiCoordinationRuntime.class);
                });
    }

    @Test
    @DisplayName("readiness 표면은 조정 런타임과 같은 인스턴스다")
    void coordinationStatusIsTheRuntime() {
        runner.run(context -> assertThat(context.getBean(UiCoordinationStatus.class))
                .isSameAs(context.getBean(UiCoordinationRuntime.class)));
    }

    /**
     * 다른 ModuleConfiguration 이 제공하는 협력자만 대신 채웁니다.
     *
     * <p>{@link UiCoordinationContext} 는 실 Redis 와 발급된 cluster epoch 를 요구하므로 대역을 씁니다 —
     * 여기서 보려는 것은 원장 동작이 아니라 <b>빈 조립</b>입니다.</p>
     */
    @Configuration(proxyBeanMethods = false)
    static class AdapterStubs {

        /**
         * session 을 고정해 환경 의존을 없앱니다.
         *
         * <p>운영 빈은 {@code POD_UID} / {@code KUBERNETES_SERVICE_HOST} 환경 변수를 읽고, Kubernetes
         * 런타임에서 {@code POD_UID} 가 없으면 프로퍼티와 무관하게 기동을 실패시킵니다(§9.1 / Plan B25).
         * CI 러너 자체가 Kubernetes Pod 이므로 그 가드가 여기서 걸립니다 — 가드는 의도된 동작이고,
         * 이 테스트가 보려는 것은 <b>빈 조립</b>이므로 session 만 고정합니다. 가드 자체는
         * {@code UiCoordinationSessionAndPropertiesTest} 가 검증합니다.</p>
         */
        @Bean
        UiCoordinationSession uiCoordinationSession() {
            return new UiCoordinationSession("tc-ui-backend", "test-uid", "test-boot", "test-pod");
        }

        /**
         * finalized revision provider 는 db-adapter 모듈이 제공합니다
         * ({@code UiFinalizedRevisionDbAdapter} — {@code tc_eqp.last_finalized_revision}).
         *
         * <p>이 테스트는 starter 의 조정 빈 그래프만 띄우므로 db-adapter 빈이 없습니다. 여기서 대역을
         * 넣지 않으면 조립 실패가 "provider 배선 누락" 이 아니라 "모듈 경계" 때문에 나므로 신호가
         * 흐려집니다. 실제 배선은 {@code UiDbAdapterModuleConfiguration} 의 {@code @Import} 가 담당하고
         * 동작은 {@code UiFinalizedRevisionDbAdapterTest} 가 고정합니다.</p>
         */
        @Bean
        UiFinalizedRevisionProvider uiFinalizedRevisionProvider() {
            return UiFinalizedRevisionProvider.absent();
        }

        /**
         * route_partition 조회는 실 앱에서 db-adapter({@code UiEqpRoutePartitionDbAdapter})가 제공합니다.
         *
         * <p>조정 그래프는 이 포트를 Gateway 참여자 판정에 씁니다(§11.3) — 모듈 경계 때문에 여기서는
         * 대역을 넣고, 판정 동작은 {@code UiGatewayRouteOwnerResolverTest} 가 고정합니다.</p>
         */
        @Bean
        UiGatewayEqpRoutePartitionLookupPort uiGatewayEqpRoutePartitionLookupPort() {
            return UiGatewayEqpRoutePartitionLookupPort.noop();
        }

        @Bean
        UiCoordinationContext uiCoordinationContext() {
            /*
             * SmartLifecycle 이 refresh 에서 실제로 start() 를 부르므로, 기동 경로가 통과할 만큼만
             * 원장 응답을 채운다 — 여기서 막히면 조립 실패인지 원장 응답 부족인지 구분되지 않는다.
             */
            final CoordinationOutcome ok = new CoordinationOutcome("OK", List.of());

            final MembershipRedisStore membershipStore = Mockito.mock(MembershipRedisStore.class);
            Mockito.when(membershipStore.register(
                    Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(),
                    Mockito.anyLong(), Mockito.anyLong())).thenReturn(ok);
            Mockito.when(membershipStore.renewHeartbeat(
                    Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyLong(),
                    Mockito.anyLong(), Mockito.anyLong())).thenReturn(ok);

            final CatalogHeadRedisStore catalogHeadStore = Mockito.mock(CatalogHeadRedisStore.class);
            Mockito.when(catalogHeadStore.readHead(Mockito.anyString()))
                    .thenAnswer(invocation -> new CatalogHead(invocation.getArgument(0), 0L, null));
            Mockito.when(catalogHeadStore.commitHead(
                    Mockito.anyString(), Mockito.anyLong(), Mockito.anyString(),
                    Mockito.any(), Mockito.anyLong())).thenReturn(ok);

            final ClusterEpochRedisStore clusterEpochStore = Mockito.mock(ClusterEpochRedisStore.class);
            Mockito.when(clusterEpochStore.current()).thenReturn(Optional.of(new ClusterEpoch("epoch-1")));

            final OperationLedgerRedisStore ledgerStore = Mockito.mock(OperationLedgerRedisStore.class);
            Mockito.when(ledgerStore.pendingOperationIds(Mockito.anyLong(), Mockito.anyLong()))
                    .thenReturn(List.of());

            final UiCoordinationContext context = Mockito.mock(UiCoordinationContext.class);
            Mockito.when(context.clusterEpoch()).thenReturn("epoch-1");
            Mockito.when(context.executor()).thenReturn(Mockito.mock(CoordinationScriptExecutor.class));
            Mockito.when(context.clusterEpochStore()).thenReturn(clusterEpochStore);
            Mockito.when(context.operationLedgerStore()).thenReturn(ledgerStore);
            Mockito.when(context.resourceBarrierStore())
                    .thenReturn(Mockito.mock(ResourceBarrierRedisStore.class));
            Mockito.when(context.membershipStore()).thenReturn(membershipStore);
            Mockito.when(context.leaseStore()).thenReturn(Mockito.mock(FencedLeaseRedisStore.class));
            Mockito.when(context.catalogHeadStore()).thenReturn(catalogHeadStore);
            return context;
        }

        @Bean("businessStringRedisTemplate")
        StringRedisTemplate businessStringRedisTemplate() {
            return Mockito.mock(StringRedisTemplate.class);
        }

        /**
         * 공유 shard-group 매핑은 실 앱에서 {@code tc-common-shard} 의 auto-configuration 이 제공합니다.
         *
         * <p>이 테스트는 starter 의 조정 빈 그래프만 띄우므로 그 auto-configuration 이 없습니다 —
         * 매핑이 빈 상태(단일 인스턴스 호환 모드)로 두면 조립 검증에는 충분합니다.</p>
         */
        @Bean
        SharedShardGroupProperties sharedShardGroupProperties() {
            return new SharedShardGroupProperties();
        }

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        EqpQueryPort eqpQueryPort() {
            return Mockito.mock(EqpQueryPort.class);
        }

        @Bean
        EqpManageQueryPort eqpManageQueryPort() {
            return Mockito.mock(EqpManageQueryPort.class);
        }

        @Bean
        BusinessJarDigestPort businessJarDigestPort() {
            return Mockito.mock(BusinessJarDigestPort.class);
        }

        /**
         * deploy 확정기 대역입니다.
         *
         * <p>운영에서는 {@code UiCoreServiceModuleConfiguration} 의 {@code @Import} 가 등록합니다 —
         * 이 runner 는 조정 configuration 만 올리므로 여기서 대신 넣습니다.</p>
         */
        @Bean
        UiDeployOperationDbFinalizer uiDeployOperationDbFinalizer() {
            return Mockito.mock(UiDeployOperationDbFinalizer.class);
        }

        @Bean
        EqpPersistenceService eqpPersistenceService() {
            return Mockito.mock(EqpPersistenceService.class);
        }

        /**
         * Gateway 명령 payload 조립에 쓰는 core service 협력자 대역입니다.
         *
         * <p>운영에서는 {@code UiCoreServiceModuleConfiguration} 의 {@code @Import} 가 등록합니다 —
         * 동작은 {@code UiCoordinatedGatewayProfileFactoryTest} 가 고정합니다.</p>
         */
        @Bean
        EqpDirectScenarioLocalMutationCoordinator eqpDirectScenarioLocalMutationCoordinator() {
            return Mockito.mock(EqpDirectScenarioLocalMutationCoordinator.class);
        }

        @Bean
        DeployCommandAssembler deployCommandAssembler() {
            return Mockito.mock(DeployCommandAssembler.class);
        }

        @Bean
        DeployStorePort deployStorePort() {
            return Mockito.mock(DeployStorePort.class);
        }

        /**
         * 접수 전 입력 검증기 대역입니다(§13.2).
         *
         * <p>운영에서는 {@code UiCoreServiceModuleConfiguration} 의 {@code @Import} 가 등록합니다 —
         * 검증 동작은 {@code CoordinatedUiEqpMutationCoordinatorTest} 가 고정합니다.</p>
         */
        @Bean
        EqpManagementCommandValidator eqpManagementCommandValidator() {
            return Mockito.mock(EqpManagementCommandValidator.class);
        }

        @Bean
        AsyncResultStorePort asyncResultStorePort() {
            return AsyncResultStorePort.noop();
        }

        @Bean
        UiGatewayEventPublishPort uiGatewayEventPublishPort() {
            return UiGatewayEventPublishPort.noop();
        }

        @Bean
        UiBusinessEventPublishPort uiBusinessEventPublishPort() {
            return Mockito.mock(UiBusinessEventPublishPort.class);
        }
    }
}

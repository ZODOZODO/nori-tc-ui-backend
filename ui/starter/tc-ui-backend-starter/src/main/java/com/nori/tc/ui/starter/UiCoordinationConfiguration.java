package com.nori.tc.ui.starter;

import com.nori.tc.common.coordination.store.CatalogHeadRedisStore;
import com.nori.tc.ui.adapters.redis.coordination.CommonCoordinationDigestAdapter;
import com.nori.tc.ui.adapters.redis.coordination.DeployExecutionRedisAdapter;
import com.nori.tc.ui.adapters.redis.coordination.RedisUiCatalogHeadAdapter;
import com.nori.tc.ui.adapters.redis.coordination.RedisUiCoordinationProbeAdapter;
import com.nori.tc.ui.adapters.redis.coordination.RedisUiExecutorLeaseAdapter;
import com.nori.tc.ui.adapters.redis.coordination.RedisUiMembershipAdapter;
import com.nori.tc.ui.adapters.redis.coordination.RedisUiOperationLedgerAdapter;
import com.nori.tc.ui.adapters.redis.coordination.RedisUiResourceHeadAdapter;
import com.nori.tc.ui.adapters.redis.coordination.UiCoordinationContext;
import com.nori.tc.ui.adapters.kafka.publish.UiOperationCommandKafkaPublisher;
import com.nori.tc.ui.core.coordination.CoordinatedUiEqpMutationCoordinator;
import com.nori.tc.ui.core.coordination.CoordinatedUiPermissionCatalogGuard;
import com.nori.tc.ui.core.coordination.CoordinatedUiScenarioCatalogGuard;
import com.nori.tc.ui.core.coordination.UiCatalogHeadSynchronizer;
import com.nori.tc.ui.core.coordination.UiCoordinationMetrics;
import com.nori.tc.ui.core.coordination.UiCoordinationProperties;
import com.nori.tc.ui.core.coordination.UiCoordinationRuntime;
import com.nori.tc.ui.core.coordination.UiCoordinationSession;
import com.nori.tc.ui.core.coordination.UiCoordinationStatus;
import com.nori.tc.ui.core.coordination.UiEqpDescriptorFactory;
import com.nori.tc.ui.core.coordination.UiEqpMutationCoordinator;
import com.nori.tc.ui.core.coordination.UiEqpOperationDbFinalizer;
import com.nori.tc.ui.core.coordination.UiOperationAcceptanceService;
import com.nori.tc.ui.core.coordination.UiOperationExecutor;
import com.nori.tc.ui.core.coordination.UiOperationManifestFactory;
import com.nori.tc.ui.core.coordination.UiOperationManifestReader;
import com.nori.tc.ui.core.coordination.UiOperationQueryService;
import com.nori.tc.ui.core.coordination.UiOperationReconciler;
import com.nori.tc.ui.core.coordination.UiPermissionCatalogGuard;
import com.nori.tc.ui.core.coordination.UiReplyProofIngestService;
import com.nori.tc.ui.core.coordination.UiResourceKeyPlanner;
import com.nori.tc.ui.core.coordination.UiRuntimeDescriptorCodec;
import com.nori.tc.ui.core.coordination.UiRuntimeDescriptorReader;
import com.nori.tc.ui.core.coordination.UiScenarioCatalogGuard;
import com.nori.tc.ui.core.coordination.port.UiCatalogHeadPort;
import com.nori.tc.ui.core.coordination.port.UiCoordinationDigestPort;
import com.nori.tc.ui.core.coordination.port.UiCoordinationMembershipPort;
import com.nori.tc.ui.core.coordination.port.UiCoordinationProbePort;
import com.nori.tc.ui.core.coordination.port.UiExecutorLeasePort;
import com.nori.tc.ui.core.coordination.port.UiFinalizedRevisionProvider;
import com.nori.tc.ui.core.coordination.port.UiOperationCommandPublisher;
import com.nori.tc.ui.core.coordination.port.UiOperationDbFinalizationPort;
import com.nori.tc.ui.core.coordination.port.UiOperationLedgerPort;
import com.nori.tc.ui.core.coordination.port.UiResourceHeadPort;
import com.nori.tc.ui.core.port.db.EqpManageQueryPort;
import com.nori.tc.ui.core.port.db.EqpQueryPort;
import com.nori.tc.ui.core.port.messaging.UiBusinessEventPublishPort;
import com.nori.tc.ui.core.port.messaging.UiGatewayEventPublishPort;
import com.nori.tc.ui.core.port.redis.AsyncResultStorePort;
import com.nori.tc.ui.core.port.redis.DeployExecutionCoordinationPort;
import com.nori.tc.ui.core.service.EqpPersistenceService;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * 클러스터 조정 원장 경로의 빈 그래프를 조립합니다(§13).
 *
 * <p>조정 on/off 는 런타임 플래그 분기가 아니라 <b>빈 선택</b>으로 갈립니다 — 같은 인터페이스의 두
 * 구현을 두어 실행 경로에 조건문이 남지 않게 합니다. 이 configuration 이 없으면 core 의
 * fallback 구성이 즉시 적용 전략을 등록합니다.</p>
 *
 * <p>{@code matchIfMissing = true} 와 {@link UiCoordinationProperties#isEnabled()} 기본값(true)의
 * 방향을 맞춥니다 — {@code @ConditionalOnProperty} 는 Environment 를 읽으므로 필드 기본값만 바꾸면
 * 빈 선택이 바뀌지 않습니다(Plan §10.3 L2).</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(UiCoordinationProperties.class)
@ConditionalOnProperty(prefix = "tc.ui.backend.coordination", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class UiCoordinationConfiguration {

    private static final Logger log = LoggerFactory.getLogger(UiCoordinationConfiguration.class);

    /** {@code POD_UID} 를 주입하는 Downward API 환경 변수 이름입니다. */
    private static final String POD_UID_ENV = "POD_UID";

    /** Kubernetes 런타임 판별용 환경 변수입니다(§9.1 / Plan §8 B25). */
    private static final String KUBERNETES_SERVICE_HOST_ENV = "KUBERNETES_SERVICE_HOST";

    /**
     * 이 Pod 의 session 을 고정합니다.
     *
     * @param properties 조정 설정
     * @return session
     */
    @Bean
    @ConditionalOnMissingBean(UiCoordinationSession.class)
    public UiCoordinationSession uiCoordinationSession(final UiCoordinationProperties properties) {
        return UiCoordinationSession.create(
                properties.getServiceName(),
                System.getenv(POD_UID_ENV),
                System.getenv("HOSTNAME"),
                properties.isAllowLocalSessionIdentity(),
                System.getenv(KUBERNETES_SERVICE_HOST_ENV) != null);
    }

    /**
     * coordination store 묶음을 만듭니다 — cluster epoch 미발급이면 여기서 기동이 실패합니다(§23.9).
     *
     * @param connectionFactory 공용 Redis 연결 팩토리
     * @param properties 조정 설정
     * @return coordination context
     */
    @Bean
    @ConditionalOnMissingBean(UiCoordinationContext.class)
    public UiCoordinationContext uiCoordinationContext(
            @Qualifier("uiBackendLettuceConnectionFactory") final LettuceConnectionFactory connectionFactory,
            final UiCoordinationProperties properties
    ) {
        /*
         * Plan §8 B26 — 선언값이 아니라 실제 client 의 command timeout 으로 §9.3 관계식을 다시 강제한다.
         * 실측할 수 없으면 그것도 기동 실패다(fail-closed).
         */
        final long actualTimeoutMs = connectionFactory.getTimeout();
        properties.assertLeaseSafetyWithActualRedisTimeout(actualTimeoutMs);
        log.info("UI_COORDINATION_REDIS_TIMEOUT_MEASURED. actualMs={}", actualTimeoutMs);
        return new UiCoordinationContext(connectionFactory);
    }

    @Bean
    public UiOperationLedgerPort uiOperationLedgerPort(final UiCoordinationContext context) {
        return new RedisUiOperationLedgerAdapter(context.operationLedgerStore());
    }

    @Bean
    public UiResourceHeadPort uiResourceHeadPort(final UiCoordinationContext context) {
        return new RedisUiResourceHeadAdapter(context.resourceBarrierStore());
    }

    @Bean
    public UiCoordinationMembershipPort uiCoordinationMembershipPort(final UiCoordinationContext context) {
        return new RedisUiMembershipAdapter(context.membershipStore());
    }

    @Bean
    public UiExecutorLeasePort uiExecutorLeasePort(final UiCoordinationContext context) {
        return new RedisUiExecutorLeaseAdapter(context.leaseStore());
    }

    @Bean
    public UiCatalogHeadPort uiCatalogHeadPort(final UiCoordinationContext context) {
        return new RedisUiCatalogHeadAdapter(context.catalogHeadStore());
    }

    @Bean
    public UiCoordinationProbePort uiCoordinationProbePort(final UiCoordinationContext context) {
        final CatalogHeadRedisStore catalogHeadStore = context.catalogHeadStore();
        return new RedisUiCoordinationProbeAdapter(
                catalogHeadStore, context.clusterEpochStore(), context.clusterEpoch());
    }

    @Bean
    public UiCoordinationDigestPort uiCoordinationDigestPort() {
        return new CommonCoordinationDigestAdapter();
    }

    /**
     * deploy/direct target lease 를 fence epoch 기반 구현으로 교체합니다(§13.4).
     *
     * @param context coordination context
     * @param stringRedisTemplate claim key 전용 String template
     * @return lease/claim 어댑터
     */
    @Bean
    public DeployExecutionCoordinationPort deployExecutionCoordinationPort(
            final UiCoordinationContext context,
            @Qualifier("businessStringRedisTemplate") final StringRedisTemplate stringRedisTemplate
    ) {
        return new DeployExecutionRedisAdapter(context.leaseStore(), stringRedisTemplate);
    }

    @Bean
    public UiCoordinationMetrics uiCoordinationMetrics(final MeterRegistry meterRegistry) {
        return new UiCoordinationMetrics(meterRegistry);
    }

    @Bean
    public UiRuntimeDescriptorCodec uiRuntimeDescriptorCodec(final ObjectMapper objectMapper) {
        return new UiRuntimeDescriptorCodec(objectMapper);
    }

    @Bean
    public UiRuntimeDescriptorReader uiRuntimeDescriptorReader(final ObjectMapper objectMapper) {
        return new UiRuntimeDescriptorReader(objectMapper);
    }

    @Bean
    public UiOperationManifestFactory uiOperationManifestFactory(
            final ObjectMapper objectMapper,
            final UiRuntimeDescriptorCodec descriptorCodec,
            final UiCoordinationDigestPort digestPort
    ) {
        return new UiOperationManifestFactory(objectMapper, descriptorCodec, digestPort);
    }

    @Bean
    public UiOperationManifestReader uiOperationManifestReader(final ObjectMapper objectMapper) {
        return new UiOperationManifestReader(objectMapper);
    }

    @Bean
    public UiResourceKeyPlanner uiResourceKeyPlanner(final EqpQueryPort eqpQueryPort) {
        return new UiResourceKeyPlanner(eqpQueryPort);
    }

    @Bean
    public UiEqpDescriptorFactory uiEqpDescriptorFactory() {
        return new UiEqpDescriptorFactory();
    }

    @Bean
    public UiOperationAcceptanceService uiOperationAcceptanceService(
            final UiOperationLedgerPort ledgerPort,
            final UiResourceHeadPort resourceHeadPort,
            final UiOperationManifestFactory manifestFactory
    ) {
        return new UiOperationAcceptanceService(ledgerPort, resourceHeadPort, manifestFactory);
    }

    @Bean
    public UiOperationCommandPublisher uiOperationCommandPublisher(
            final UiGatewayEventPublishPort gatewayPublishPort,
            final UiBusinessEventPublishPort businessPublishPort
    ) {
        return new UiOperationCommandKafkaPublisher(gatewayPublishPort, businessPublishPort);
    }

    /**
     * 마지막 finalized revision 기록/조회 구현입니다.
     *
     * <p>현재는 {@link UiFinalizedRevisionProvider#absent()} 입니다 — 값을 담을 컬럼이 Flyway V7(P5)
     * 선행이기 때문입니다. 그동안 원장 후퇴 검증(§11.11/§12.3)은 동작하지 않으며, executor 가 기록
     * 실패를 metric 과 경고로 드러냅니다.</p>
     *
     * @return finalized revision provider
     */
    @Bean
    public UiFinalizedRevisionProvider uiFinalizedRevisionProvider() {
        log.warn("UI_LEDGER_REGRESSION_CHECK_INERT. 마지막 finalized revision 컬럼(Flyway V7)이 아직 없어 "
                + "원장 후퇴 검증이 동작하지 않습니다.");
        return UiFinalizedRevisionProvider.absent();
    }

    @Bean
    public UiOperationDbFinalizationPort uiOperationDbFinalizationPort(
            final EqpPersistenceService persistenceService,
            final ObjectMapper objectMapper,
            final UiRuntimeDescriptorReader descriptorReader
    ) {
        return new UiEqpOperationDbFinalizer(persistenceService, objectMapper, descriptorReader);
    }

    @Bean
    public UiOperationExecutor uiOperationExecutor(
            final UiCoordinationSession session,
            final UiCoordinationProperties properties,
            final UiOperationLedgerPort ledgerPort,
            final UiResourceHeadPort resourceHeadPort,
            final UiExecutorLeasePort leasePort,
            final UiCoordinationMembershipPort membershipPort,
            final UiOperationCommandPublisher commandPublisher,
            final UiOperationDbFinalizationPort dbFinalizationPort,
            final UiFinalizedRevisionProvider finalizedRevisionProvider,
            final UiOperationManifestReader manifestReader,
            final UiCoordinationMetrics metrics
    ) {
        return new UiOperationExecutor(session, properties, ledgerPort, resourceHeadPort, leasePort,
                membershipPort, commandPublisher, dbFinalizationPort, finalizedRevisionProvider,
                manifestReader, metrics);
    }

    @Bean
    public UiOperationReconciler uiOperationReconciler(
            final UiOperationLedgerPort ledgerPort,
            final UiOperationExecutor executor,
            final UiCoordinationProperties properties,
            final UiCoordinationMetrics metrics
    ) {
        return new UiOperationReconciler(ledgerPort, executor, properties, metrics);
    }

    @Bean
    public UiReplyProofIngestService uiReplyProofIngestService(
            final UiOperationLedgerPort ledgerPort,
            final UiOperationExecutor executor,
            final UiCoordinationMetrics metrics
    ) {
        return new UiReplyProofIngestService(ledgerPort, executor, metrics);
    }

    @Bean
    public UiOperationQueryService uiOperationQueryService(
            final UiOperationLedgerPort ledgerPort,
            final AsyncResultStorePort asyncResultStorePort,
            final UiCoordinationProperties properties
    ) {
        return new UiOperationQueryService(ledgerPort, asyncResultStorePort, properties);
    }

    @Bean
    public UiEqpMutationCoordinator uiEqpMutationCoordinator(
            final EqpManageQueryPort eqpManageQueryPort,
            final UiResourceKeyPlanner resourceKeyPlanner,
            final UiEqpDescriptorFactory descriptorFactory,
            final UiResourceHeadPort resourceHeadPort,
            final UiOperationAcceptanceService acceptanceService,
            final UiOperationReconciler reconciler,
            final ObjectMapper objectMapper
    ) {
        return new CoordinatedUiEqpMutationCoordinator(eqpManageQueryPort, resourceKeyPlanner, descriptorFactory,
                resourceHeadPort, acceptanceService, reconciler, objectMapper);
    }

    @Bean
    public UiScenarioCatalogGuard uiScenarioCatalogGuard(final UiCatalogHeadPort catalogHeadPort) {
        return new CoordinatedUiScenarioCatalogGuard(
                new UiCatalogHeadSynchronizer(catalogHeadPort, UiCatalogHeadPort.CATALOG_TYPE_SCENARIO));
    }

    @Bean
    public UiPermissionCatalogGuard uiPermissionCatalogGuard(
            final UiCatalogHeadPort catalogHeadPort,
            final UiCoordinationDigestPort digestPort
    ) {
        return new CoordinatedUiPermissionCatalogGuard(
                new UiCatalogHeadSynchronizer(catalogHeadPort, UiCatalogHeadPort.CATALOG_TYPE_PERMISSION),
                digestPort);
    }

    /**
     * 조정 런타임이자 readiness 표면입니다(§13.11).
     *
     * <p>{@link UiCoordinationRuntime} 이 {@link UiCoordinationStatus} 를 <b>직접 구현</b>하므로 상태
     * 표면을 별도 빈으로 한 번 더 노출하지 않습니다 — 노출하면 같은 인스턴스가 두 개의 빈 정의로
     * 보여 {@code UiCoordinationStatus} 를 주입받는 빈이 {@code NoUniqueBeanDefinitionException} 으로
     * 깨집니다. 빈 생성 순서에 따라 앱 기동에서만 드러나는 종류입니다.</p>
     */
    @Bean
    public UiCoordinationRuntime uiCoordinationRuntime(
            final UiCoordinationSession session,
            final UiCoordinationProperties properties,
            final UiCoordinationMembershipPort membershipPort,
            final UiCoordinationProbePort probePort,
            final UiOperationReconciler reconciler,
            final UiScenarioCatalogGuard scenarioCatalogGuard,
            final UiPermissionCatalogGuard permissionCatalogGuard,
            final UiCoordinationMetrics metrics
    ) {
        return new UiCoordinationRuntime(session, properties, membershipPort, probePort, reconciler,
                scenarioCatalogGuard, permissionCatalogGuard, metrics);
    }
}

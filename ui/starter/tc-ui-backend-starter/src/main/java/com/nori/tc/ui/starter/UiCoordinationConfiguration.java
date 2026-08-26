package com.nori.tc.ui.starter;

import com.nori.tc.common.coordination.store.CatalogHeadRedisStore;
import com.nori.tc.common.shard.SharedShardGroupProperties;
import com.nori.tc.ui.adapters.redis.config.UiGatewayLeaseProperties;
import com.nori.tc.ui.adapters.redis.config.UiRuntimeInstanceProperties;
import com.nori.tc.ui.adapters.redis.lease.GatewayShardLeaseRedisAdapter;
import com.nori.tc.ui.adapters.redis.coordination.CommonCoordinationDigestAdapter;
import com.nori.tc.ui.adapters.redis.coordination.CoordinatedGatewayShardLeaseAdapter;
import com.nori.tc.ui.adapters.redis.coordination.DeployExecutionRedisAdapter;
import com.nori.tc.ui.adapters.redis.coordination.RuntimeInstanceRedisAdapter;
import com.nori.tc.ui.adapters.redis.coordination.RedisUiCatalogHeadAdapter;
import com.nori.tc.ui.adapters.redis.coordination.RedisUiCoordinationProbeAdapter;
import com.nori.tc.ui.adapters.redis.coordination.RedisUiExecutorLeaseAdapter;
import com.nori.tc.ui.adapters.redis.coordination.RedisUiMembershipAdapter;
import com.nori.tc.ui.adapters.redis.coordination.RedisUiOperationLedgerAdapter;
import com.nori.tc.ui.adapters.redis.coordination.RedisUiResourceHeadAdapter;
import com.nori.tc.ui.adapters.redis.coordination.UiCoordinationContext;
import com.nori.tc.ui.adapters.kafka.publish.UiOperationCommandKafkaPublisher;
import com.nori.tc.ui.core.coordination.CoordinatedUiEqpMutationCoordinator;
import com.nori.tc.ui.core.coordination.DeployOperationCoordinator;
import com.nori.tc.ui.core.coordination.CoordinatedUiPermissionCatalogGuard;
import com.nori.tc.ui.core.coordination.CoordinatedUiScenarioCatalogGuard;
import com.nori.tc.ui.core.coordination.UiCatalogHeadSynchronizer;
import com.nori.tc.ui.core.coordination.UiCompensationIssuer;
import com.nori.tc.ui.core.coordination.UiCoordinationMetrics;
import com.nori.tc.ui.core.coordination.UiCoordinationProperties;
import com.nori.tc.ui.core.coordination.UiCoordinationRuntime;
import com.nori.tc.ui.core.coordination.UiCoordinationSession;
import com.nori.tc.ui.core.coordination.UiCoordinationStatus;
import com.nori.tc.ui.core.coordination.UiDeployDescriptorFactory;
import com.nori.tc.ui.core.coordination.UiEqpDescriptorFactory;
import com.nori.tc.ui.core.coordination.UiEqpMutationCoordinator;
import com.nori.tc.ui.core.coordination.UiEqpOperationDbFinalizer;
import com.nori.tc.ui.core.coordination.UiOperationDbFinalizationRouter;
import com.nori.tc.ui.core.coordination.UiOperationAcceptanceService;
import com.nori.tc.ui.core.coordination.UiOperationExecutor;
import com.nori.tc.ui.core.coordination.UiOperationManifestFactory;
import com.nori.tc.ui.core.coordination.UiOperationManifestReader;
import com.nori.tc.ui.core.coordination.UiOperationAdminQueryService;
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
import com.nori.tc.ui.core.coordination.port.UiOperationApplyDriver;
import com.nori.tc.ui.core.coordination.port.UiOperationCommandPublisher;
import com.nori.tc.ui.core.coordination.port.UiOperationDbFinalizationPort;
import com.nori.tc.ui.core.coordination.port.UiOperationLedgerPort;
import com.nori.tc.ui.core.coordination.port.UiResourceHeadPort;
import com.nori.tc.ui.core.port.db.BusinessJarDigestPort;
import com.nori.tc.ui.core.port.db.EqpManageQueryPort;
import com.nori.tc.ui.core.port.db.EqpQueryPort;
import com.nori.tc.ui.core.port.messaging.UiBusinessEventPublishPort;
import com.nori.tc.ui.core.port.messaging.UiGatewayEventPublishPort;
import com.nori.tc.ui.core.port.redis.AsyncResultStorePort;
import com.nori.tc.ui.core.port.redis.DeployExecutionCoordinationPort;
import com.nori.tc.ui.core.port.redis.GatewayShardLeasePort;
import com.nori.tc.ui.core.port.redis.RuntimeInstanceQueryPort;
import com.nori.tc.ui.core.service.EqpPersistenceService;
import com.nori.tc.ui.core.service.UiDeployOperationDbFinalizer;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
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
@EnableConfigurationProperties({UiCoordinationProperties.class, UiRuntimeInstanceProperties.class,
        UiGatewayLeaseProperties.class})
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

    /**
     * Gateway shard lease 조회를 등록합니다(§12.2).
     *
     * <p>lease 기록 스키마는 <b>Gateway 쪽 버전</b>이 정하고 이 프로퍼티와 무관합니다 — 조정을 쓰는
     * Gateway 는 fence epoch hash 에, 그 이전 Gateway 는 raw String 키에 씁니다. 그래서 어느 한쪽만
     * 읽지 않고 두 스키마를 모두 보는 어댑터를 등록해 배포 순서와 화면 정확성을 분리합니다.</p>
     *
     * @param context coordination context
     * @param shardGroupProperties Gateway 와 공유하는 shard-group 정의
     * @param stringRedisTemplate 레거시 raw String lease 를 읽는 template
     * @param leaseProperties 레거시 lease 키 prefix 설정
     * @return shard lease 조회 포트
     */
    @Bean
    public GatewayShardLeasePort coordinatedGatewayShardLeasePort(
            final UiCoordinationContext context,
            final SharedShardGroupProperties shardGroupProperties,
            @Qualifier("businessStringRedisTemplate") final StringRedisTemplate stringRedisTemplate,
            final UiGatewayLeaseProperties leaseProperties
    ) {
        return new CoordinatedGatewayShardLeaseAdapter(
                context.leaseStore(),
                shardGroupProperties,
                new GatewayShardLeaseRedisAdapter(stringRedisTemplate, shardGroupProperties, leaseProperties));
    }

    /**
     * Gateway 런타임 인스턴스 조망 조회를 등록합니다(§9.1).
     *
     * <p>lease 는 "점유된 슬롯"만 답하므로 슬롯을 못 잡은 대기(spare) Pod 이 빠집니다. membership 은
     * lease 획득 여부와 무관하게 등록되므로 Pod 축은 여기서 옵니다.</p>
     *
     * <p>조회 key namespace 인 serviceName 은 operation 발행 경로와 같은 설정
     * ({@link UiCoordinationProperties#getGatewayServiceName()} /
     * {@link UiCoordinationProperties#getBusinessServiceName()})에서 받습니다 — 두 곳에 두면 한쪽만
     * 바뀔 때 조회가 조용히 0 건이 됩니다.</p>
     *
     * @param context coordination context
     * @param coordinationProperties 조정 설정(serviceName 출처)
     * @param properties 조회 상한 설정
     * @return 런타임 인스턴스 조회 포트
     */
    @Bean
    public RuntimeInstanceQueryPort runtimeInstanceQueryPort(
            final UiCoordinationContext context,
            final UiCoordinationProperties coordinationProperties,
            final UiRuntimeInstanceProperties properties
    ) {
        return new RuntimeInstanceRedisAdapter(
                context.membershipStore(),
                coordinationProperties.getGatewayServiceName(),
                coordinationProperties.getBusinessServiceName(),
                properties);
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
     * DB 확정을 명령 종류에 따라 분배합니다(§13.7).
     *
     * <p>{@code @Primary} 입니다 — deploy 확정기도 같은 포트 타입의 빈이라 executor 주입 지점에서
     * 후보가 둘이 됩니다. 실행 경로가 반드시 거쳐야 하는 것은 라우터 쪽입니다.</p>
     *
     * @param persistenceService 설비 DB 영속화 서비스
     * @param objectMapper JSON 파서
     * @param descriptorReader descriptor 해석기
     * @param deployFinalizer deploy target 확정기
     * @return 분배 라우터
     */
    @Bean
    @Primary
    public UiOperationDbFinalizationPort uiOperationDbFinalizationPort(
            final EqpPersistenceService persistenceService,
            final ObjectMapper objectMapper,
            final UiRuntimeDescriptorReader descriptorReader,
            final UiDeployOperationDbFinalizer deployFinalizer
    ) {
        return new UiOperationDbFinalizationRouter(
                new UiEqpOperationDbFinalizer(persistenceService, objectMapper, descriptorReader),
                deployFinalizer);
    }

    /**
     * 커밋 후 실패를 되돌리는 보상 operation 발급기입니다(§13.8).
     *
     * @param ledgerPort operation 원장 포트
     * @param resourceHeadPort 리소스 head 포트
     * @param manifestFactory manifest 팩토리
     * @param manifestReader manifest 해석기
     * @return 보상 발급기
     */
    @Bean
    public UiCompensationIssuer uiCompensationIssuer(
            final UiOperationLedgerPort ledgerPort,
            final UiResourceHeadPort resourceHeadPort,
            final UiOperationManifestFactory manifestFactory,
            final UiOperationManifestReader manifestReader
    ) {
        return new UiCompensationIssuer(ledgerPort, resourceHeadPort, manifestFactory, manifestReader);
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
            final UiCompensationIssuer compensationIssuer,
            final ObjectProvider<UiOperationApplyDriver> applyDriverProvider,
            final UiCoordinationMetrics metrics
    ) {
        return new UiOperationExecutor(session, properties, ledgerPort, resourceHeadPort, leasePort,
                membershipPort, commandPublisher, dbFinalizationPort, finalizedRevisionProvider,
                manifestReader, compensationIssuer,
                applyDriverProvider.getIfAvailable(UiOperationApplyDriver::none), metrics);
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

    /**
     * §20.3 운영자 관리 조회 서비스입니다.
     *
     * <p>조정 원장이 켜진 구성에만 존재합니다 — 소비하는 컨트롤러는 이 빈을 {@code ObjectProvider} 로
     * 받아 비활성 구성에서도 기동합니다.</p>
     *
     * @param ledgerPort operation 원장 포트
     * @param resourceHeadPort 리소스 head 포트
     * @return 관리 조회 서비스
     */
    @Bean
    public UiOperationAdminQueryService uiOperationAdminQueryService(
            final UiOperationLedgerPort ledgerPort,
            final UiResourceHeadPort resourceHeadPort
    ) {
        return new UiOperationAdminQueryService(ledgerPort, resourceHeadPort);
    }

    @Bean
    public UiEqpMutationCoordinator uiEqpMutationCoordinator(
            final EqpManageQueryPort eqpManageQueryPort,
            final BusinessJarDigestPort businessJarDigestPort,
            final UiResourceKeyPlanner resourceKeyPlanner,
            final UiEqpDescriptorFactory descriptorFactory,
            final UiResourceHeadPort resourceHeadPort,
            final UiOperationAcceptanceService acceptanceService,
            final UiOperationReconciler reconciler,
            final ObjectMapper objectMapper
    ) {
        return new CoordinatedUiEqpMutationCoordinator(eqpManageQueryPort, businessJarDigestPort, resourceKeyPlanner,
                descriptorFactory, resourceHeadPort, acceptanceService, reconciler, objectMapper);
    }

    @Bean
    public UiDeployDescriptorFactory uiDeployDescriptorFactory() {
        return new UiDeployDescriptorFactory();
    }

    /**
     * deploy target 을 operation 으로 접수하는 coordinator 입니다(§13.6).
     *
     * <p>조정 원장이 켜진 구성에만 존재합니다 — {@code DeployService} 가 이 빈을
     * {@code ObjectProvider} 로 받아, 없으면 기존 target 실행 경로를 그대로 탑니다.</p>
     *
     * @param resourceKeyPlanner 영향 집합 산출기
     * @param descriptorFactory deploy descriptor 팩토리
     * @param resourceHeadPort 리소스 head 포트
     * @param acceptanceService 공통 접수 서비스
     * @param reconciler operation reconciler
     * @param executor operation 상태 기계
     * @param manifestFactory operationId 도출기
     * @param objectMapper 확정 명령 직렬화기
     * @return deploy operation coordinator
     */
    @Bean
    public DeployOperationCoordinator deployOperationCoordinator(
            final UiResourceKeyPlanner resourceKeyPlanner,
            final UiDeployDescriptorFactory descriptorFactory,
            final UiResourceHeadPort resourceHeadPort,
            final UiOperationAcceptanceService acceptanceService,
            final UiOperationReconciler reconciler,
            final UiOperationExecutor executor,
            final UiOperationManifestFactory manifestFactory,
            final ObjectMapper objectMapper
    ) {
        return new DeployOperationCoordinator(resourceKeyPlanner, descriptorFactory, resourceHeadPort,
                acceptanceService, reconciler, executor, manifestFactory, objectMapper);
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

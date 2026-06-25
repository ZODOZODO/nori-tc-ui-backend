package com.nori.tc.ui.starter;

import com.nori.tc.common.runtime.support.KafkaAdminConnectivityHealthIndicator;
import com.nori.tc.ui.adapter.db.config.UiDbAdapterAutoConfiguration;
import com.nori.tc.ui.adapters.kafka.config.UiKafkaAdapterAutoConfiguration;
import com.nori.tc.ui.adapters.kafka.config.UiKafkaTopicProperties;
import com.nori.tc.ui.adapters.redis.config.UiRedisAdapterAutoConfiguration;
import com.nori.tc.ui.adapters.redis.config.UiRedisProperties;
import com.nori.tc.ui.adapters.web.config.UiWebAdapterAutoConfiguration;
import com.nori.tc.ui.adapters.web.simulator.LiveMapSimulatorRunFacade;
import com.nori.tc.ui.core.auth.UiAuthAsyncExecutor;
import com.nori.tc.ui.core.properties.UiDeployProperties;
import com.nori.tc.ui.core.service.DeployService;
import com.nori.tc.ui.core.service.DeployTraceRegistry;
import com.nori.tc.ui.core.service.EqpManagementService;
import com.nori.tc.ui.core.service.EqpRepairTaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.env.Environment;
import org.springframework.core.task.TaskDecorator;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Map;
import java.util.concurrent.Executor;

/**
 * tc-ui-backend-starter 자동 구성 진입점입니다.
 *
 * <p>이 클래스는 UI Backend 모듈 전반을 Spring 컨텍스트에 조립합니다.
 * core/adapter 모두 명시적 {@link Import} 그래프로만 등록합니다.</p>
 *
 * <p>조립 대상 어댑터:</p>
 * <ul>
 *   <li>{@link UiWebAdapterAutoConfiguration}  — Spring Security + REST Controller</li>
 *   <li>{@link UiKafkaAdapterAutoConfiguration} — Kafka Publisher/Subscriber + 토픽 설정</li>
 *   <li>{@link UiRedisAdapterAutoConfiguration} — UI Backend 단일 Redis 연결</li>
 *   <li>{@link UiDbAdapterAutoConfiguration} — DB Port 구현 어댑터</li>
 * </ul>
 *
 * <p>Spring Boot AutoConfiguration 등록 파일:</p>
 * <pre>
 * resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
 * → com.nori.tc.ui.starter.TcUiBackendAutoConfiguration
 * </pre>
 */
@AutoConfiguration
@EnableScheduling
@EnableConfigurationProperties(UiDeployProperties.class)
@Import({
        UiCoreModuleConfiguration.class,
        UiWebAdapterAutoConfiguration.class,   // Web: Controller + Security
        UiKafkaAdapterAutoConfiguration.class, // Kafka: Publisher/Subscriber + Listener config
        UiRedisAdapterAutoConfiguration.class, // Redis: shared connection + logical templates + Pub/Sub
        UiDbAdapterAutoConfiguration.class     // DB: JPA Store Port adapter
})
public class TcUiBackendAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(TcUiBackendAutoConfiguration.class);
    private static final int EQP_MANAGEMENT_EXECUTOR_CORE_POOL_SIZE = 4;
    private static final int EQP_MANAGEMENT_EXECUTOR_MAX_POOL_SIZE = 4;
    private static final int EQP_MANAGEMENT_EXECUTOR_QUEUE_CAPACITY = 128;
    private static final int EQP_MANAGEMENT_EXECUTOR_AWAIT_TERMINATION_SECONDS = 10;
    private static final String EQP_MANAGEMENT_EXECUTOR_THREAD_PREFIX = "ui-eqp-management-";
    private static final int AUTH_EXECUTOR_CORE_POOL_SIZE = 2;
    private static final int AUTH_EXECUTOR_MAX_POOL_SIZE = 2;
    private static final int AUTH_EXECUTOR_QUEUE_CAPACITY = 256;
    private static final int AUTH_EXECUTOR_AWAIT_TERMINATION_SECONDS = 10;
    private static final String AUTH_EXECUTOR_THREAD_PREFIX = "ui-auth-";
    // 데모-run 연결-후-MES 배리어 백그라운드 실행 전용. 작업 1건이 연결확인 동안 스레드를 길게 점유(최대
    // connectionConfirmTimeoutMs)하므로 코어는 작게, 동시 다수 run 대비 max/queue 로 bound 한다.
    private static final int DEMO_RUN_ACTIVATION_EXECUTOR_CORE_POOL_SIZE = 2;
    private static final int DEMO_RUN_ACTIVATION_EXECUTOR_MAX_POOL_SIZE = 8;
    private static final int DEMO_RUN_ACTIVATION_EXECUTOR_QUEUE_CAPACITY = 64;
    private static final int DEMO_RUN_ACTIVATION_EXECUTOR_AWAIT_TERMINATION_SECONDS = 30;
    private static final String DEMO_RUN_ACTIVATION_EXECUTOR_THREAD_PREFIX = "ui-demo-run-activation-";

    /**
     * AutoConfiguration 클래스 인스턴스화 시점에 기동 시작 로그를 출력합니다.
     *
     * <p>어떤 Configuration들이 import되는지 로그로 명시하여
     * 기동 실패 시 어느 단계에서 문제가 발생했는지 빠르게 파악할 수 있게 합니다.</p>
     */
    public TcUiBackendAutoConfiguration() {
        log.info("UI_BOOT_STARTING. imports=[UiWebAdapterAutoConfiguration, UiKafkaAdapterAutoConfiguration, "
                + "UiRedisAdapterAutoConfiguration, UiDbAdapterAutoConfiguration]");
    }

    /**
     * EQP 관리용 비동기 executor를 등록합니다.
     *
     * <p>기존 구현은 {@code CompletableFuture.supplyAsync(...)} 기본 executor를 사용해
     * {@code ForkJoinPool.commonPool}에서 작업이 실행되었습니다.
     * fat jar(java -jar) 환경에서는 common pool worker의 context classloader가
     * nested jar를 올바르게 보지 못해 Kafka producer 초기화 중
     * 클래스 로딩 오류가 발생할 수 있으므로, Spring이 관리하는 전용 executor로 고정합니다.</p>
     *
     * <p>또한 TaskDecorator로 MDC와 context classloader를 함께 전파해
     * 비동기 worker에서도 traceId 로그와 Kafka 동적 클래스 로딩이
     * 요청 스레드와 동일한 컨텍스트를 사용하도록 보장합니다.</p>
     *
     * @return EQP 관리 비동기 처리 전용 executor
     */
    @Bean(name = EqpManagementService.EQP_MANAGEMENT_EXECUTOR_BEAN_NAME)
    public Executor uiEqpManagementExecutor() {
        final ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(EQP_MANAGEMENT_EXECUTOR_CORE_POOL_SIZE);
        executor.setMaxPoolSize(EQP_MANAGEMENT_EXECUTOR_MAX_POOL_SIZE);
        executor.setQueueCapacity(EQP_MANAGEMENT_EXECUTOR_QUEUE_CAPACITY);
        executor.setThreadNamePrefix(EQP_MANAGEMENT_EXECUTOR_THREAD_PREFIX);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(EQP_MANAGEMENT_EXECUTOR_AWAIT_TERMINATION_SECONDS);
        executor.setTaskDecorator(copyMdcAndContextClassLoaderTaskDecorator());
        executor.initialize();

        log.info("EQP 관리 비동기 executor 초기화 완료. beanName={}, corePoolSize={}, maxPoolSize={}, queueCapacity={}",
                EqpManagementService.EQP_MANAGEMENT_EXECUTOR_BEAN_NAME,
                EQP_MANAGEMENT_EXECUTOR_CORE_POOL_SIZE,
                EQP_MANAGEMENT_EXECUTOR_MAX_POOL_SIZE,
                EQP_MANAGEMENT_EXECUTOR_QUEUE_CAPACITY);

        return executor;
    }

    /**
     * 인증 세션 후처리(lastSeenAt 등) 전용 ThreadPoolTaskExecutor를 등록합니다.
     *
     * <p>인증 경로에서 {@code ForkJoinPool.commonPool}을 사용하지 않도록 전용 executor를 분리합니다.</p>
     *
     * @return 인증 비동기 처리 전용 ThreadPoolTaskExecutor
     */
    @Bean(name = "uiAuthTaskExecutor")
    public ThreadPoolTaskExecutor uiAuthTaskExecutor() {
        final ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(AUTH_EXECUTOR_CORE_POOL_SIZE);
        executor.setMaxPoolSize(AUTH_EXECUTOR_MAX_POOL_SIZE);
        executor.setQueueCapacity(AUTH_EXECUTOR_QUEUE_CAPACITY);
        executor.setThreadNamePrefix(AUTH_EXECUTOR_THREAD_PREFIX);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(AUTH_EXECUTOR_AWAIT_TERMINATION_SECONDS);
        executor.setTaskDecorator(copyMdcAndContextClassLoaderTaskDecorator());
        executor.initialize();

        log.info("인증 비동기 executor 초기화 완료. corePoolSize={}, maxPoolSize={}, queueCapacity={}",
                AUTH_EXECUTOR_CORE_POOL_SIZE,
                AUTH_EXECUTOR_MAX_POOL_SIZE,
                AUTH_EXECUTOR_QUEUE_CAPACITY);
        return executor;
    }

    /**
     * 데모-run 연결-후-MES 배리어 백그라운드 실행 전용 executor 를 등록합니다.
     *
     * <p>{@code LiveMapSimulatorRunFacade.startRun} 은 orchestrator createRun(저렴)까지만 동기로 응답하고, 설비
     * 연결확인 + activateUpstream(MES) 을 이 executor 로 백그라운드 수행한다(대량 설비 시 요청 동기 처리가 외부 HTTP
     * 프록시 타임아웃을 넘겨 504 나던 문제 해소). 배경 작업이 {@code startAsync} 로 EQP_START Kafka 를 발행하므로
     * common pool 대신 context classloader 를 전파하는 전용 executor 로 고정한다(fat jar nested-jar 로딩 안전).</p>
     *
     * @return 데모-run 활성화 비동기 처리 전용 executor
     */
    @Bean(name = LiveMapSimulatorRunFacade.ACTIVATION_EXECUTOR_BEAN_NAME)
    public Executor demoRunActivationExecutor() {
        final ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(DEMO_RUN_ACTIVATION_EXECUTOR_CORE_POOL_SIZE);
        executor.setMaxPoolSize(DEMO_RUN_ACTIVATION_EXECUTOR_MAX_POOL_SIZE);
        executor.setQueueCapacity(DEMO_RUN_ACTIVATION_EXECUTOR_QUEUE_CAPACITY);
        executor.setThreadNamePrefix(DEMO_RUN_ACTIVATION_EXECUTOR_THREAD_PREFIX);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(DEMO_RUN_ACTIVATION_EXECUTOR_AWAIT_TERMINATION_SECONDS);
        executor.setTaskDecorator(copyMdcAndContextClassLoaderTaskDecorator());
        executor.initialize();

        log.info("데모-run 활성화 비동기 executor 초기화 완료. corePoolSize={}, maxPoolSize={}, queueCapacity={}",
                DEMO_RUN_ACTIVATION_EXECUTOR_CORE_POOL_SIZE,
                DEMO_RUN_ACTIVATION_EXECUTOR_MAX_POOL_SIZE,
                DEMO_RUN_ACTIVATION_EXECUTOR_QUEUE_CAPACITY);
        return executor;
    }

    /**
     * 코어 계층이 사용할 인증 비동기 executor 추상화를 노출합니다.
     *
     * @param taskExecutor Spring이 관리하는 인증 executor
     * @return 코어가 사용하는 executor 추상화
     */
    @Bean
    public UiAuthAsyncExecutor uiAuthAsyncExecutor(
            @Qualifier("uiAuthTaskExecutor") final ThreadPoolTaskExecutor taskExecutor
    ) {
        return taskExecutor::execute;
    }

    /**
     * 애플리케이션 기동 완료 후 UI Backend 구성 요약을 로그로 출력합니다.
     *
     * <p>운영자가 기동 직후 로그 한 줄로 주요 구성(토픽, Redis 연결) 이상 유무를
     * 빠르게 확인할 수 있도록 compact summary 형식으로 출력합니다.</p>
     *
     * @param kafkaTopicProps Kafka 토픽 이름 바인딩 프로퍼티
     * @param redisProps      UI Backend 단일 Redis 연결 설정 프로퍼티
     * @return ApplicationReadyEvent 기반 기동 완료 리스너
     */
    @Bean
    public ApplicationListener<ApplicationReadyEvent> uiBackendStartupReadyObservationListener(
            final UiKafkaTopicProperties kafkaTopicProps,
            final UiRedisProperties redisProps
    ) {
        return event -> {
            // 각 구성 요소별 준비 완료 로그 — 기동 로그 검색 시 component 키워드로 필터링 가능
            log.info("UI_BOOT_COMPONENT_READY. component=WebSecurity");
            log.info("UI_BOOT_COMPONENT_READY. component=KafkaTopics");
            log.info("UI_BOOT_COMPONENT_READY. component=Redis");
            log.info("UI_BOOT_COMPONENT_READY. component=AuthExecutor");

            final String appName = event.getApplicationContext()
                    .getEnvironment()
                    .getProperty("spring.application.name", "tc-ui-backend-app");

            // 수신 토픽: Gateway/Business 처리 결과 응답 채널
            final String consumeTopics = sanitizeTopic(kafkaTopicProps.getCommandsTopic());

            // 발행 토픽: Gateway와 Business Core에 각각 이벤트 전달
            final String produceTopics = String.join(",",
                    sanitizeTopic(kafkaTopicProps.getGatewayEventsTopic()),
                    sanitizeTopic(kafkaTopicProps.getBusinessEventsTopic()));

            // Redis 연결 정보: 물리 Redis는 1개이고 gateway/business는 key prefix 기준의 논리 역할입니다.
            final String redis = buildRedisAddress(redisProps.getConnection());

            // 기동 완료 요약 — 구성 이상 시 즉시 식별 가능한 단일 INFO 라인
            log.info("UI_BOOT_READY. app={}, consume=[{}], produce=[{}], redis={}",
                    appName, consumeTopics, produceTopics, redis);
        };
    }

    /**
     * readiness group에서 kafka contributor 이름을 고정해 startup 검증과 운영 surface를 일치시킵니다.
     *
     * <p>Kafka bootstrap 서버 설정이 아직 없으면 contributor 자체는 유지하고 DOWN 상태로 노출합니다.</p>
     *
     * @param environment Spring 환경 설정
     * @return readiness용 kafka health contributor
     */
    @Bean(name = "kafkaHealthIndicator")
    public HealthIndicator kafkaHealthIndicator(final Environment environment) {
        final String bootstrapServers = environment.getProperty("spring.kafka.bootstrap-servers");
        if (bootstrapServers == null) {
            return () -> Health.down()
                    .withDetail("reason", "spring.kafka.bootstrap-servers missing")
                    .build();
        }
        return new KafkaAdminConnectivityHealthIndicator(bootstrapServers);
    }

    /**
     * readiness group 설정 이름과 runtime contributor bean 이름을 일치시킵니다.
     *
     * @param deployService deploy 실행 상태 조회 서비스
     * @param deployTraceRegistry deploy trace 저장소
     * @param repairTaskService repair backlog 조회 서비스
     * @param gatewayRedisFactory gateway redis factory
     * @param businessRedisFactory business redis factory
     * @return UI backend runtime readiness contributor
     */
    @Bean(name = "uiBackendRuntimeHealthIndicator")
    public HealthIndicator uiBackendRuntimeHealthIndicator(
            final DeployService deployService,
            final DeployTraceRegistry deployTraceRegistry,
            final EqpRepairTaskService repairTaskService,
            @Qualifier("gatewayLettuceConnectionFactory") final LettuceConnectionFactory gatewayRedisFactory,
            @Qualifier("businessLettuceConnectionFactory") final LettuceConnectionFactory businessRedisFactory
    ) {
        return new UiBackendRuntimeHealthIndicator(
                deployService,
                deployTraceRegistry,
                repairTaskService,
                gatewayRedisFactory,
                businessRedisFactory
        );
    }

    /**
     * 토픽 이름을 startup 요약 출력용 문자열로 정규화합니다.
     *
     * <p>null 또는 공백인 경우 {@code N/A}로 표기하여
     * 설정 누락을 기동 로그에서 즉시 식별할 수 있게 합니다.</p>
     *
     * @param value 정규화 대상 토픽 이름
     * @return 정규화된 토픽 이름 (null/공백이면 "N/A")
     */
    private static String sanitizeTopic(final String value) {
        if (value == null) {
            return "N/A";
        }
        final String trimmed = value.trim();
        return trimmed.isEmpty() ? "N/A" : trimmed;
    }

    /**
     * Redis 노드 설정을 {@code host:port} 형식의 주소 문자열로 변환합니다.
     *
     * <p>startup 요약 로그에 Redis 연결 대상을 명시하여
     * 잘못된 Redis 연결 구성을 기동 직후 바로 확인할 수 있게 합니다.</p>
     *
     * @param node Redis 노드 연결 설정 (host, port)
     * @return {@code host:port} 형식 문자열 (node가 null이면 "N/A")
     */
    private static String buildRedisAddress(final UiRedisProperties.RedisNodeProperties node) {
        if (node == null) {
            return "N/A";
        }
        final String host = (node.getHost() == null || node.getHost().isBlank()) ? "N/A" : node.getHost();
        return host + ":" + node.getPort();
    }

    /**
     * 비동기 worker에 MDC와 context classloader를 전파하는 decorator를 생성합니다.
     *
     * @return MDC/classloader 전파 task decorator
     */
    private static TaskDecorator copyMdcAndContextClassLoaderTaskDecorator() {
        return runnable -> {
            final Map<String, String> capturedMdcContext = MDC.getCopyOfContextMap();
            final ClassLoader capturedContextClassLoader = Thread.currentThread().getContextClassLoader();

            return () -> {
                final Thread currentThread = Thread.currentThread();
                final Map<String, String> previousMdcContext = MDC.getCopyOfContextMap();
                final ClassLoader previousContextClassLoader = currentThread.getContextClassLoader();

                try {
                    if (capturedMdcContext == null || capturedMdcContext.isEmpty()) {
                        MDC.clear();
                    } else {
                        MDC.setContextMap(capturedMdcContext);
                    }
                    currentThread.setContextClassLoader(capturedContextClassLoader);
                    runnable.run();
                } finally {
                    if (previousMdcContext == null || previousMdcContext.isEmpty()) {
                        MDC.clear();
                    } else {
                        MDC.setContextMap(previousMdcContext);
                    }
                    currentThread.setContextClassLoader(previousContextClassLoader);
                }
            };
        };
    }
}

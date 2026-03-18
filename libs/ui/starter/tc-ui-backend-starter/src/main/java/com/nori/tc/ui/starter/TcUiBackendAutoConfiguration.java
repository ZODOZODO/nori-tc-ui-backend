package com.nori.tc.ui.starter;

import com.nori.tc.ui.adapter.db.config.UiDbAdapterAutoConfiguration;
import com.nori.tc.ui.adapters.kafka.config.UiKafkaAdapterAutoConfiguration;
import com.nori.tc.ui.adapters.kafka.config.UiKafkaTopicProperties;
import com.nori.tc.ui.adapters.redis.config.UiRedisAdapterAutoConfiguration;
import com.nori.tc.ui.adapters.redis.config.UiRedisProperties;
import com.nori.tc.ui.adapters.web.config.UiWebAdapterAutoConfiguration;
import com.nori.tc.ui.core.service.EqpManagementService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Map;
import java.util.concurrent.Executor;

/**
 * tc-ui-backend-starter 자동 구성 진입점입니다.
 *
 * <p>이 클래스는 UI Backend 모듈 전반을 Spring 컨텍스트에 조립합니다.
 * {@link ComponentScan}으로 {@code com.nori.tc.ui.core}만 스캔하고,
 * {@link Import}로 어댑터별 AutoConfiguration을 명시 등록합니다.</p>
 *
 * <p>조립 대상 어댑터:</p>
 * <ul>
 *   <li>{@link UiWebAdapterAutoConfiguration}  — Spring Security + REST Controller</li>
 *   <li>{@link UiKafkaAdapterAutoConfiguration} — Kafka Publisher/Subscriber + 토픽 설정</li>
 *   <li>{@link UiRedisAdapterAutoConfiguration} — Gateway/Business Redis 이중 연결</li>
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
@ComponentScan(basePackages = "com.nori.tc.ui.core")
@Import({
        UiWebAdapterAutoConfiguration.class,   // Web: Controller + Security
        UiKafkaAdapterAutoConfiguration.class, // Kafka: Publisher/Subscriber + Listener config
        UiRedisAdapterAutoConfiguration.class, // Redis: gateway/business template + Pub/Sub
        UiDbAdapterAutoConfiguration.class     // DB: JPA Store Port adapter
})
public class TcUiBackendAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(TcUiBackendAutoConfiguration.class);
    private static final int EQP_MANAGEMENT_EXECUTOR_CORE_POOL_SIZE = 4;
    private static final int EQP_MANAGEMENT_EXECUTOR_MAX_POOL_SIZE = 4;
    private static final int EQP_MANAGEMENT_EXECUTOR_QUEUE_CAPACITY = 128;
    private static final int EQP_MANAGEMENT_EXECUTOR_AWAIT_TERMINATION_SECONDS = 10;
    private static final String EQP_MANAGEMENT_EXECUTOR_THREAD_PREFIX = "ui-eqp-management-";

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
     * UI Backend 전용 Jackson 2.x ObjectMapper 빈을 등록합니다.
     *
     * <p>Spring Boot 4.x는 Jackson 3.x({@code tools.jackson.databind.ObjectMapper})를 기본으로
     * 사용하므로 {@code com.fasterxml.jackson.databind.ObjectMapper} 빈이 자동으로 등록되지 않습니다.
     * {@link com.nori.tc.ui.adapters.web.security.UiTokenAuthenticationFilter}와
     * {@link com.nori.tc.ui.adapters.kafka.subscribe.UiCommandKafkaSubscriber}가
     * Jackson 2.x {@code ObjectMapper}를 생성자 주입으로 요구하므로 명시적으로 빈을 제공합니다.</p>
     *
     * <p>{@code @ConditionalOnMissingBean}으로 동일 타입의 빈이 이미 존재하는 경우(예: 다른 스타터가
     * 등록한 경우) 중복 생성을 방지합니다. Business Core Starter의 {@code businessUiObjectMapper}와
     * 동일한 패턴을 따릅니다.</p>
     *
     * @return 기본 설정의 Jackson 2.x ObjectMapper
     */
    @Bean
    @ConditionalOnMissingBean(ObjectMapper.class)
    public ObjectMapper uiObjectMapper() {
        return new ObjectMapper();
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
     * 애플리케이션 기동 완료 후 UI Backend 구성 요약을 로그로 출력합니다.
     *
     * <p>운영자가 기동 직후 로그 한 줄로 주요 구성(토픽, Redis 연결) 이상 유무를
     * 빠르게 확인할 수 있도록 compact summary 형식으로 출력합니다.</p>
     *
     * @param kafkaTopicProps Kafka 토픽 이름 바인딩 프로퍼티
     * @param redisProps      Gateway / Business Redis 연결 설정 프로퍼티
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
            log.info("UI_BOOT_COMPONENT_READY. component=DualRedis");

            final String appName = event.getApplicationContext()
                    .getEnvironment()
                    .getProperty("spring.application.name", "tc-ui-backend-app");

            // 수신 토픽: Gateway/Business 처리 결과 응답 채널
            final String consumeTopics = sanitizeTopic(kafkaTopicProps.getCommandsTopic());

            // 발행 토픽: Gateway와 Business Core에 각각 이벤트 전달
            final String produceTopics = String.join(",",
                    sanitizeTopic(kafkaTopicProps.getGatewayEventsTopic()),
                    sanitizeTopic(kafkaTopicProps.getBusinessEventsTopic()));

            // Redis 연결 정보: Gateway(DLQ/Quarantine 조회) + Business(DLQ + 토큰 캐시 + 비동기 결과)
            final String gatewayRedis = buildRedisAddress(redisProps.getGateway());
            final String businessRedis = buildRedisAddress(redisProps.getBusiness());

            // 기동 완료 요약 — 구성 이상 시 즉시 식별 가능한 단일 INFO 라인
            log.info("UI_BOOT_READY. app={}, consume=[{}], produce=[{}], redis=[gateway:{}, business:{}]",
                    appName, consumeTopics, produceTopics, gatewayRedis, businessRedis);
        };
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

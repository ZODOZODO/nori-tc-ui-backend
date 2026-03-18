package com.nori.tc.ui.adapters.redis.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.nori.tc.ui.adapters.redis.registry.DualResponsePubSubListener;
import com.nori.tc.ui.adapters.redis.registry.DualResponseRedisAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * UI Backend Redis 이중 연결 + 직렬화 + Pub/Sub 설정 클래스입니다.
 *
 * <p>핵심 목표:</p>
 * <ul>
 *   <li>Gateway Redis(6379), Business Redis(6380)를 각각 독립 연결로 관리합니다.</li>
 *   <li>기존 JDK 직렬화를 제거하고 JSON 직렬화로 통일합니다.</li>
 *   <li>DualResponse 완료 알림 채널을 Business Redis Pub/Sub로 수신합니다.</li>
 * </ul>
 *
 * <p>직렬화 전략:</p>
 * <ul>
 *   <li>Key/HashKey: {@link StringRedisSerializer}</li>
 *   <li>Value/HashValue: {@link RedisSerializer#json()}</li>
 * </ul>
 */
@Configuration
@EnableConfigurationProperties(UiRedisProperties.class)
public class UiRedisConfiguration {

    private static final Logger log = LoggerFactory.getLogger(UiRedisConfiguration.class);

    /**
     * Redis JSON 직렬화 전용 ObjectMapper 빈입니다.
     *
     * <p>목적:</p>
     * <ul>
     *   <li>Redis 저장 전용 직렬화 정책을 애플리케이션 기본 ObjectMapper와 분리합니다.</li>
     *   <li>자바 시간 타입 등 추가 모듈이 클래스패스에 있을 경우 자동 등록합니다.</li>
     * </ul>
     *
     * <p>보안 주의:</p>
     * <p>위험한 기본 타이핑(activateDefaultTyping + LaissezFaireSubTypeValidator) 조합은 사용하지 않습니다.</p>
     *
     * @return Redis 직렬화용 ObjectMapper
     */
    @Bean(name = "uiRedisObjectMapper")
    public ObjectMapper uiRedisObjectMapper() {
        final ObjectMapper mapper = new ObjectMapper();
        mapper.findAndRegisterModules();
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }

    /**
     * Redis Value/HashValue 직렬화기 빈입니다.
     *
     * @param uiRedisObjectMapper Redis 전용 ObjectMapper
     * @return JSON 기반 Redis 직렬화기
     */
    @Bean(name = "uiRedisValueSerializer")
    public RedisSerializer<Object> uiRedisValueSerializer(
            @Qualifier("uiRedisObjectMapper") final ObjectMapper uiRedisObjectMapper
    ) {
        /*
         * 참고:
         * - Spring Data Redis 4.x에서 Jackson2/GenericJackson2 계열은 제거 예정으로 표시됩니다.
         * - 따라서 경고 최소화를 위해 RedisSerializer.json() 팩토리를 사용합니다.
         * - uiRedisObjectMapper 빈은 Redis Map -> DTO 변환 등 다른 컴포넌트에서 계속 사용하므로
         *   시그니처 호환성을 위해 파라미터를 유지합니다.
         */
        return RedisSerializer.json();
    }

    // -------------------------------------------------------------------------
    // Gateway Redis (6379)
    // -------------------------------------------------------------------------

    /**
     * Gateway Redis 연결 팩토리입니다.
     *
     * @param props Redis 연결 프로퍼티
     * @return gateway 연결 팩토리
     */
    @Bean(name = "gatewayLettuceConnectionFactory", destroyMethod = "destroy")
    public LettuceConnectionFactory gatewayLettuceConnectionFactory(final UiRedisProperties props) {
        final LettuceConnectionFactory factory = buildConnectionFactory(props.getGateway());
        log.debug("Gateway Redis 연결 팩토리 생성 완료. host={}, port={}",
                props.getGateway().getHost(), props.getGateway().getPort());
        return factory;
    }

    /**
     * Gateway RedisTemplate 빈입니다.
     *
     * @param factory gateway 연결 팩토리
     * @param valueSerializer JSON 직렬화기
     * @return gateway RedisTemplate
     */
    @Bean(name = "gatewayRedisTemplate")
    public RedisTemplate<String, Object> gatewayRedisTemplate(
            @Qualifier("gatewayLettuceConnectionFactory") final LettuceConnectionFactory factory,
            @Qualifier("uiRedisValueSerializer") final RedisSerializer<Object> valueSerializer
    ) {
        final RedisTemplate<String, Object> template = buildTemplate(factory, valueSerializer);
        log.info("gatewayRedisTemplate 초기화 완료. serializer=RedisSerializer.json()");
        return template;
    }

    // -------------------------------------------------------------------------
    // Business Redis (6380)
    // -------------------------------------------------------------------------

    /**
     * Business Redis 연결 팩토리입니다.
     *
     * @param props Redis 연결 프로퍼티
     * @return business 연결 팩토리
     */
    @Bean(name = "businessLettuceConnectionFactory", destroyMethod = "destroy")
    public LettuceConnectionFactory businessLettuceConnectionFactory(final UiRedisProperties props) {
        final LettuceConnectionFactory factory = buildConnectionFactory(props.getBusiness());
        log.debug("Business Redis 연결 팩토리 생성 완료. host={}, port={}",
                props.getBusiness().getHost(), props.getBusiness().getPort());
        return factory;
    }

    /**
     * Business RedisTemplate 빈입니다.
     *
     * <p>토큰 캐시, 비동기 결과 저장, DualResponse 상태 저장에 사용됩니다.</p>
     *
     * @param factory business 연결 팩토리
     * @param valueSerializer JSON 직렬화기
     * @return business RedisTemplate
     */
    @Bean(name = "businessRedisTemplate")
    public RedisTemplate<String, Object> businessRedisTemplate(
            @Qualifier("businessLettuceConnectionFactory") final LettuceConnectionFactory factory,
            @Qualifier("uiRedisValueSerializer") final RedisSerializer<Object> valueSerializer
    ) {
        final RedisTemplate<String, Object> template = buildTemplate(factory, valueSerializer);
        log.info("businessRedisTemplate 초기화 완료. serializer=RedisSerializer.json()");
        return template;
    }

    // -------------------------------------------------------------------------
    // DualResponse Redis Pub/Sub
    // -------------------------------------------------------------------------

    /**
     * DualResponse 완료 알림 채널 구독 컨테이너입니다.
     *
     * <p>Business Redis 연결을 사용하여 완료 채널을 구독합니다.
     * 메시지 수신 시 {@link DualResponsePubSubListener}가 traceId 기준으로
     * 로컬 CompletableFuture 완료를 트리거합니다.</p>
     *
     * @param businessFactory business Redis 연결 팩토리
     * @param listener 완료 메시지 리스너
     * @return RedisMessageListenerContainer
     */
    @Bean(name = "dualResponseRedisMessageListenerContainer")
    public RedisMessageListenerContainer dualResponseRedisMessageListenerContainer(
            @Qualifier("businessLettuceConnectionFactory") final LettuceConnectionFactory businessFactory,
            final DualResponsePubSubListener listener
    ) {
        final RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(businessFactory);
        container.addMessageListener(listener, new ChannelTopic(DualResponseRedisAdapter.DUAL_COMPLETE_CHANNEL));
        log.info("DualResponse Pub/Sub 리스너 등록 완료. channel={}", DualResponseRedisAdapter.DUAL_COMPLETE_CHANNEL);
        return container;
    }

    // -------------------------------------------------------------------------
    // 공통 빌더
    // -------------------------------------------------------------------------

    /**
     * 노드 설정을 기반으로 LettuceConnectionFactory를 생성합니다.
     *
     * @param node Redis 노드 설정
     * @return 초기화된 연결 팩토리
     */
    private LettuceConnectionFactory buildConnectionFactory(final UiRedisProperties.RedisNodeProperties node) {
        final RedisStandaloneConfiguration config = new RedisStandaloneConfiguration();
        config.setHostName(node.getHost());
        config.setPort(node.getPort());

        if (node.getPassword() != null && !node.getPassword().isBlank()) {
            config.setPassword(RedisPassword.of(node.getPassword()));
        }

        final LettuceConnectionFactory factory = new LettuceConnectionFactory(config);
        factory.afterPropertiesSet();
        return factory;
    }

    /**
     * 공통 직렬화 정책이 적용된 RedisTemplate을 생성합니다.
     *
     * @param factory Redis 연결 팩토리
     * @param valueSerializer Value/HashValue 직렬화기
     * @return 초기화된 RedisTemplate
     */
    private RedisTemplate<String, Object> buildTemplate(
            final LettuceConnectionFactory factory,
            final RedisSerializer<Object> valueSerializer
    ) {
        final RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);

        final StringRedisSerializer stringSerializer = new StringRedisSerializer();
        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);
        template.setValueSerializer(valueSerializer);
        template.setHashValueSerializer(valueSerializer);
        template.afterPropertiesSet();
        return template;
    }
}

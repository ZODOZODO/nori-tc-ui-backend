package com.nori.tc.ui.adapters.redis.config;

import com.nori.tc.ui.adapters.redis.registry.DeployTracePubSubListener;
import com.nori.tc.ui.adapters.redis.registry.DeployTraceRedisAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * UI Backend Redis 단일 연결 + 직렬화 + Pub/Sub 설정 클래스입니다.
 *
 * <p>핵심 목표:</p>
 * <ul>
 *   <li>물리 Redis 연결은 1개만 생성하고, gateway/business는 논리 역할 이름으로만 유지합니다.</li>
 *   <li>기존 JDK 직렬화를 제거하고 JSON 직렬화로 통일합니다.</li>
 *   <li>Deploy trace 완료 알림 채널을 Business Redis Pub/Sub로 수신합니다.</li>
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
        return JsonMapper.builder()
                .findAndAddModules()
                .build();
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
    // Shared Redis connection
    // -------------------------------------------------------------------------

    /**
     * UI Backend 공용 Redis 연결 팩토리입니다.
     *
     * <p>기존 호출부 호환을 위해 gateway/business bean name은 alias로 유지합니다.</p>
     *
     * @param props Redis 연결 프로퍼티
     * @return UI Backend 공용 Redis 연결 팩토리
     */
    @Bean(
            name = {
                    "uiBackendLettuceConnectionFactory",
                    "gatewayLettuceConnectionFactory",
                    "businessLettuceConnectionFactory"
            },
            destroyMethod = "destroy"
    )
    public LettuceConnectionFactory uiBackendLettuceConnectionFactory(final UiRedisProperties props) {
        final UiRedisProperties.RedisNodeProperties connection = props.getConnection();
        final LettuceConnectionFactory factory = buildConnectionFactory(connection, props);
        log.debug("UI Backend Redis 연결 팩토리 생성 완료. host={}, port={}",
                connection.getHost(), connection.getPort());
        return factory;
    }

    /*
     * gatewayRedisTemplate(typed) 빈은 2026-08-21 에 제거했다.
     *
     * 유일한 소비자였던 GatewayDlqRedisService 가 typeless dlqRedisTemplate 으로 옮겨가 주입처가
     * 0 이 됐고, gateway 가 Redis 에 쓰는 값은 전부 typeless 라 이 이름으로 새로 주입하면 같은 결함이
     * 재발한다(@class 없는 값에서 SerializationException). 즉 남겨두면 트랩이다.
     * gateway 논리 연결 자체는 gatewayLettuceConnectionFactory alias 로 그대로 유지된다.
     */

    /**
     * Business RedisTemplate 빈입니다.
     *
     * <p>토큰 캐시와 비동기 결과 저장에 사용됩니다.</p>
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

    /**
     * DeployTrace 전용 Business StringRedisTemplate 빈입니다.
     *
     * <p>DeployTrace는 명시적 JSON 문자열 계약으로 저장하므로,
     * 공용 Object serializer를 거치지 않도록 별도 템플릿을 분리합니다.</p>
     *
     * @param factory business 연결 팩토리
     * @return business StringRedisTemplate
     */
    @Bean(name = "businessStringRedisTemplate")
    public StringRedisTemplate businessStringRedisTemplate(
            @Qualifier("businessLettuceConnectionFactory") final LettuceConnectionFactory factory
    ) {
        final StringRedisTemplate template = new StringRedisTemplate();
        template.setConnectionFactory(factory);
        template.afterPropertiesSet();
        log.info("businessStringRedisTemplate 초기화 완료. serializer=StringRedisSerializer");
        return template;
    }

    /**
     * 통신 로그 조회 전용 RedisTemplate 빈입니다.
     *
     * <p>적재 측(Gateway/Business)의 {@code tcRedisTemplate}이
     * {@link GenericJacksonJsonRedisSerializer}(ObjectMapper-인자 생성자, typeless mode)로 저장하므로,
     * 조회 측에서도 동일한 직렬화 정책을 사용해야 호환됩니다.</p>
     *
     * <p>UI Backend의 다른 RedisTemplate은 {@link RedisSerializer#json()}(typed, {@code @class} 기반)을 사용하지만,
     * 통신 로그는 적재/조회가 서로 다른 애플리케이션이므로 직렬화 mode가 일치해야 합니다.</p>
     *
     * <p>역직렬화 결과는 {@link java.util.LinkedHashMap}이므로, 호출부에서
     * {@link ObjectMapper#convertValue(Object, Class)}로 도메인 객체로 변환합니다.</p>
     *
     * @param factory UI Backend 공용 Redis 연결 팩토리
     * @param uiRedisObjectMapper Redis 직렬화용 ObjectMapper
     * @return 통신 로그 조회 전용 RedisTemplate
     */
    @Bean(name = "commLogRedisTemplate")
    public RedisTemplate<String, Object> commLogRedisTemplate(
            @Qualifier("uiBackendLettuceConnectionFactory") final LettuceConnectionFactory factory,
            @Qualifier("uiRedisObjectMapper") final ObjectMapper uiRedisObjectMapper
    ) {
        final RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);

        final StringRedisSerializer stringSerializer = new StringRedisSerializer();
        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);

        // 적재 측(Gateway/Business)의 tcRedisTemplate과 동일한 typeless GenericJacksonJsonRedisSerializer 사용
        final GenericJacksonJsonRedisSerializer commLogSerializer =
                new GenericJacksonJsonRedisSerializer(uiRedisObjectMapper);
        template.setValueSerializer(commLogSerializer);
        template.setHashValueSerializer(commLogSerializer);
        template.afterPropertiesSet();

        log.info("commLogRedisTemplate 초기화 완료. serializer=GenericJacksonJsonRedisSerializer(typeless)");
        return template;
    }

    /**
     * DLQ / Quarantine 조회 전용 RedisTemplate 빈입니다.
     *
     * <p>적재 측(Gateway {@code RedisDlqPublisher} / {@code RedisQuarantineStore},
     * Business {@code RedisBusinessDlqPublisher})은 common {@code TcRedisCrudRepository} 를 통해
     * <b>typeless</b> JSON({@code @class} 힌트 없음)으로 쓴다. 따라서 조회 측도 같은 mode 여야 한다 —
     * {@code commLogRedisTemplate} 과 동일한 이유·동일한 구성이다.</p>
     *
     * <p><b>이 빈이 신설된 이유(2026-08-21)</b>: 두 DLQ 서비스는 typed
     * {@code gatewayRedisTemplate} / {@code businessRedisTemplate}({@link RedisSerializer#json()})을
     * 주입받고 있었다. typed 직렬화기는 {@code @class} 가 없는 값을 만나면 {@code Map} 을 돌려주지 않고
     * {@code opsForValue().get()} 안에서 {@code SerializationException("missing type id property '@class'")}
     * 을 던진다. 그래서 서비스의 {@code Map} fallback 분기는 <b>도달 불가</b>였고, 운영에서
     * {@code GET /api/dlq/business} 가 항목이 있어도 조용히 빈 목록을 돌려줬다(WARN 만 남았다).
     * 같은 mode 를 쓰는 {@code CommLogRedisQueryAdapter} 는 정상 동작해 왔다.</p>
     *
     * <p>Gateway / Business 논리 연결은 {@code uiBackendLettuceConnectionFactory} 하나의 alias 이므로
     * (같은 host/port/db) 이 템플릿 하나가 두 접두사를 모두 읽는다.</p>
     *
     * @param factory UI Backend 공용 Redis 연결 팩토리
     * @param uiRedisObjectMapper Redis 직렬화용 ObjectMapper
     * @return DLQ / Quarantine 조회 전용 RedisTemplate
     */
    @Bean(name = "dlqRedisTemplate")
    public RedisTemplate<String, Object> dlqRedisTemplate(
            @Qualifier("uiBackendLettuceConnectionFactory") final LettuceConnectionFactory factory,
            @Qualifier("uiRedisObjectMapper") final ObjectMapper uiRedisObjectMapper
    ) {
        final RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);

        final StringRedisSerializer stringSerializer = new StringRedisSerializer();
        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);

        final GenericJacksonJsonRedisSerializer dlqSerializer =
                new GenericJacksonJsonRedisSerializer(uiRedisObjectMapper);
        template.setValueSerializer(dlqSerializer);
        template.setHashValueSerializer(dlqSerializer);
        template.afterPropertiesSet();

        log.info("dlqRedisTemplate 초기화 완료. serializer=GenericJacksonJsonRedisSerializer(typeless)");
        return template;
    }

    /**
     * Deploy trace 완료 알림 채널 구독 컨테이너입니다.
     *
     * @param businessFactory business Redis 연결 팩토리
     * @param listener 완료 메시지 리스너
     * @return RedisMessageListenerContainer
     */
    @Bean(name = "deployTraceRedisMessageListenerContainer")
    public RedisMessageListenerContainer deployTraceRedisMessageListenerContainer(
            @Qualifier("businessLettuceConnectionFactory") final LettuceConnectionFactory businessFactory,
            final DeployTracePubSubListener listener
    ) {
        final RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(businessFactory);
        container.addMessageListener(listener, new ChannelTopic(DeployTraceRedisAdapter.DEPLOY_TRACE_COMPLETE_CHANNEL));
        log.info("Deploy trace Pub/Sub 리스너 등록 완료. channel={}", DeployTraceRedisAdapter.DEPLOY_TRACE_COMPLETE_CHANNEL);
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
    private LettuceConnectionFactory buildConnectionFactory(
            final UiRedisProperties.RedisNodeProperties node,
            final UiRedisProperties props
    ) {
        final RedisStandaloneConfiguration config = new RedisStandaloneConfiguration();
        config.setHostName(node.getHost());
        config.setPort(node.getPort());
        config.setDatabase(node.getDatabase());

        if (node.getUsername() != null && !node.getUsername().isBlank()) {
            config.setUsername(node.getUsername());
        }
        if (node.getPassword() != null && !node.getPassword().isBlank()) {
            config.setPassword(RedisPassword.of(node.getPassword()));
        }

        /*
         * command/connect timeout 을 명시한다(SPEC §19.1). 이 클래스가 팩토리를 직접 만들기 때문에
         * Boot 의 Redis auto-configuration 이 적용되지 않고, 지정하지 않으면 Lettuce 기본값 60 초가
         * 쓰인다. 그 값으로는 §9.3 안전 관계식이 성립할 수 없어 조정 원장이 켜진 구성에서
         * UiCoordinationConfiguration 의 실측 검증(Plan §8 B26)이 기동을 실패시킨다.
         */
        final LettuceClientConfiguration clientConfiguration = LettuceClientConfiguration.builder()
                .commandTimeout(props.getTimeout())
                .clientOptions(ClientOptions.builder()
                        .socketOptions(SocketOptions.builder()
                                .connectTimeout(props.getConnectTimeout())
                                .build())
                        .build())
                .build();

        final LettuceConnectionFactory factory = new LettuceConnectionFactory(config, clientConfiguration);
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

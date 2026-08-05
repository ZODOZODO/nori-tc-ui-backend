package com.nori.tc.ui.adapters.kafka.config;

import jakarta.annotation.PostConstruct;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.ProducerFactory;

import java.util.Map;
import java.util.Objects;

/**
 * "발행 대기 ≥ producer delivery timeout" 불변식을 기동 시점에 강제합니다.
 *
 * <p>왜 필요한가 — 발행 경로는 {@code kafkaTemplate.send(...).get(publishTimeoutSeconds, SECONDS)} 로
 * 브로커 ack 를 동기 확인한다. 그런데 {@code Future.get} 의 timeout 은 <b>이미 전송 중인 send 를 취소하지
 * 않는다.</b> 대기가 producer 의 {@code delivery.timeout.ms} 보다 짧으면 대기가 끝난 뒤에도 producer 는
 * 계속 재시도하다가 성공할 수 있고, 그 사이 호출자는 발행 실패로 보고 재시도해 같은 UI 이벤트를 한 번 더
 * 발행한다. producer 멱등성({@code enable.idempotence=true})은 <b>애플리케이션이 만든 별개의 send 두
 * 건</b>을 하나로 합쳐주지 않는다.</p>
 *
 * <p>두 값은 서로 다른 파일(앱 런타임 설정 / Kafka producer 설정)에 있어 한쪽만 바뀌는 드리프트가 쉽게
 * 생기고 그 결과는 런타임 중복 발행으로만 드러난다. 그래서 기동에서 막는다.</p>
 *
 * <p>{@code delivery.timeout.ms} 가 비어 있으면 통과시키지 않고 기동을 실패시킨다. Kafka 기본값
 * (120000ms)이 조용히 적용되면 같은 드리프트가 그대로 되살아나기 때문이다.</p>
 *
 * <p><b>검증 출처</b>는 {@link ProducerFactory#getConfigurationProperties()} 의 merged config 다 —
 * common {@code KafkaProducerConfigValidator} 와 같은 출처이며 property 이름이 아니라 실제 적용값을 본다.
 * 형제인 business 검증기는 {@code Environment} 를 읽는데, 그것은 tc-business-starter 가 Kafka SDK
 * 비종속 모듈이라 {@code ProducerFactory} 를 들일 수 없었기 때문이다. 이 모듈은 spring-kafka 를 이미
 * 의존하므로 그 제약이 없다.</p>
 */
public final class UiPublishTimeoutInvariantValidator {

    static final String PUBLISH_TIMEOUT_SECONDS_PROPERTY = "tc.ui.backend.kafka.publish-timeout-seconds";

    private static final Logger log = LoggerFactory.getLogger(UiPublishTimeoutInvariantValidator.class);
    private static final long MILLIS_PER_SECOND = 1_000L;

    private final UiKafkaPublishProperties publishProperties;
    private final ProducerFactory<?, ?> producerFactory;

    public UiPublishTimeoutInvariantValidator(
            final UiKafkaPublishProperties publishProperties,
            final ProducerFactory<?, ?> producerFactory
    ) {
        this.publishProperties = Objects.requireNonNull(publishProperties, "publishProperties is null");
        this.producerFactory = Objects.requireNonNull(producerFactory, "producerFactory is null");
    }

    /**
     * 기동 시점에 두 값의 관계를 검증하고, 위반이면 예외로 기동을 중단시킵니다.
     */
    @PostConstruct
    public void validate() {
        final long publishTimeoutSeconds = publishProperties.getPublishTimeoutSeconds();
        final Map<String, Object> config = producerFactory.getConfigurationProperties();
        final Object rawDeliveryTimeoutMs = config.get(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG);

        if (rawDeliveryTimeoutMs == null || String.valueOf(rawDeliveryTimeoutMs).isBlank()) {
            throw new IllegalStateException(
                    ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG + " 가 설정되지 않았습니다. "
                            + "Kafka 기본값(120000ms)이 암묵 적용되면 발행 대기와의 불일치가 다시 생깁니다. "
                            + "설정 파일에 명시하십시오. "
                            + PUBLISH_TIMEOUT_SECONDS_PROPERTY + "=" + publishTimeoutSeconds + "s");
        }

        final long deliveryTimeoutMs = parseDeliveryTimeoutMs(rawDeliveryTimeoutMs);
        final long publishTimeoutMs = Math.multiplyExact(publishTimeoutSeconds, MILLIS_PER_SECOND);

        if (publishTimeoutMs < deliveryTimeoutMs) {
            throw new IllegalStateException(
                    "발행 대기가 producer delivery timeout 보다 짧습니다. 대기가 먼저 끝나도 send 는 계속 진행되므로 "
                            + "성공/실패가 불확정이고, 재시도가 같은 UI 이벤트를 중복 발행합니다. "
                            + PUBLISH_TIMEOUT_SECONDS_PROPERTY + "=" + publishTimeoutSeconds + "s("
                            + publishTimeoutMs + "ms), "
                            + ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG + "=" + deliveryTimeoutMs + "ms");
        }

        log.info("발행 대기 불변식 검증 통과. {}={}s, {}={}ms",
                PUBLISH_TIMEOUT_SECONDS_PROPERTY, publishTimeoutSeconds,
                ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, deliveryTimeoutMs);
    }

    private long parseDeliveryTimeoutMs(final Object rawDeliveryTimeoutMs) {
        try {
            return Long.parseLong(String.valueOf(rawDeliveryTimeoutMs).trim());
        } catch (final NumberFormatException ex) {
            throw new IllegalStateException(
                    ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG + " 를 정수로 읽을 수 없습니다. value="
                            + rawDeliveryTimeoutMs, ex);
        }
    }
}

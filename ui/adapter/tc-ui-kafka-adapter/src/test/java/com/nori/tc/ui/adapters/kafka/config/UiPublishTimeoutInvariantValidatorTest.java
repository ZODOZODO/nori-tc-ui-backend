package com.nori.tc.ui.adapters.kafka.config;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.ProducerFactory;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 순서 331 검증 — 발행 대기와 producer {@code delivery.timeout.ms} 의 관계를 기동 시점에 강제하는지 확인합니다.
 *
 * <p>형제 business 검증기 테스트와 같은 케이스 집합이되, 검증 출처가 {@link ProducerFactory} merged config
 * 라는 차이만 반영한다.</p>
 */
class UiPublishTimeoutInvariantValidatorTest {

    /**
     * 순서 331 의 재현 조건 — exec 가 명시한 대기 3s vs delivery 120000ms.
     * 이 조합이면 대기가 끝난 뒤에도 producer 가 계속 재시도해 성공할 수 있고, 그 사이 재시도가 중복 발행한다.
     */
    @Test
    void 대기가_delivery_보다_짧으면_기동을_실패시킨다() {
        final IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> validator(3L, "120000").validate());

        assertTrue(ex.getMessage().contains("tc.ui.backend.kafka.publish-timeout-seconds=3s"),
                "실패 메시지에 대기 값이 없다. message=" + ex.getMessage());
        assertTrue(ex.getMessage().contains("delivery.timeout.ms=120000ms"),
                "실패 메시지에 delivery 값이 없다. message=" + ex.getMessage());
    }

    /**
     * 코드 기본값이 수렴값(30s)이라 exec 설정이 30000 이면 그대로 통과해야 한다 —
     * 기본값이 3L 로 되돌아가면 이 테스트가 깨진다.
     */
    @Test
    void 코드_기본_대기가_delivery_수렴값_30000_을_만족한다() {
        final long codeDefault = new UiKafkaPublishProperties().getPublishTimeoutSeconds();

        assertEquals(30L, codeDefault, "코드 기본 대기가 delivery 수렴값(30s)과 어긋난다.");
        assertDoesNotThrow(() -> validator(codeDefault, "30000").validate());
    }

    @Test
    void 대기가_더_길면_통과한다() {
        assertDoesNotThrow(() -> validator(45L, "30000").validate());
    }

    /**
     * 미설정을 통과시키면 Kafka 기본값 120000ms 가 조용히 적용돼 같은 드리프트가 되살아난다.
     */
    @Test
    void delivery_가_미설정이면_기동을_실패시킨다() {
        final Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        final UiKafkaPublishProperties properties = new UiKafkaPublishProperties();
        properties.setPublishTimeoutSeconds(30L);

        assertThrows(IllegalStateException.class,
                () -> new UiPublishTimeoutInvariantValidator(
                        properties, new DefaultKafkaProducerFactory<>(config)).validate());
    }

    @Test
    void delivery_가_정수가_아니면_기동을_실패시킨다() {
        final IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> validator(30L, "30s").validate());

        assertTrue(ex.getMessage().contains("30s"), "실패 메시지에 원문 값이 없다. message=" + ex.getMessage());
    }

    private UiPublishTimeoutInvariantValidator validator(
            final long publishTimeoutSeconds,
            final String deliveryTimeoutMs
    ) {
        final Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, deliveryTimeoutMs);
        final UiKafkaPublishProperties properties = new UiKafkaPublishProperties();
        properties.setPublishTimeoutSeconds(publishTimeoutSeconds);
        return new UiPublishTimeoutInvariantValidator(properties, new DefaultKafkaProducerFactory<>(config));
    }
}

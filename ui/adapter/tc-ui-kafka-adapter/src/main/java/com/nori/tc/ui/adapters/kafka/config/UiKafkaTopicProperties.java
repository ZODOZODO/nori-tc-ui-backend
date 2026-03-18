package com.nori.tc.ui.adapters.kafka.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * UI Backend Kafka 토픽 이름 바인딩 설정 클래스입니다.
 *
 * <p>tc-ui-backend-app이 발행/구독하는 핵심 토픽 3개를 프로퍼티에서 주입받아
 * 발행/구독 어댑터 전반에서 동일한 토픽 계약을 일관되게 사용하도록 중앙화합니다.</p>
 *
 * <p>바인딩 prefix: {@code tc.ui.backend.kafka}</p>
 *
 * <pre>
 * tc.ui.backend.kafka.gateway-events-topic=tc.ui.events.gateway
 * tc.ui.backend.kafka.business-events-topic=tc.ui.events.business
 * tc.ui.backend.kafka.commands-topic=tc.ui.commands
 * </pre>
 */
@ConfigurationProperties(prefix = "tc.ui.backend.kafka")
public class UiKafkaTopicProperties {

    private static final Logger log = LoggerFactory.getLogger(UiKafkaTopicProperties.class);

    /**
     * Gateway 대상 UI 이벤트 발행 토픽입니다.
     *
     * <p>eqp_create / eqp_update / eqp_delete / eqp_start / eqp_end 이벤트를
     * Gateway로 전달합니다. route_partition을 이용한 명시적 파티션 발행 시에도
     * 이 토픽 이름이 기준으로 사용됩니다.</p>
     */
    private String gatewayEventsTopic;

    /**
     * Business Core 대상 UI 이벤트 발행 토픽입니다.
     *
     * <p>eqp_create / eqp_update / eqp_delete 이벤트를 Business Core로 전달합니다.
     * eqp_start / eqp_end는 Gateway 전담이므로 이 토픽으로 발행하지 않습니다.</p>
     */
    private String businessEventsTopic;

    /**
     * Gateway / Business Core 응답 수신 토픽입니다.
     *
     * <p>Gateway와 Business Core가 처리 결과를 이 토픽에 발행하면,
     * UiCommandKafkaSubscriber가 수신하여 eventType에 따라 후속 처리합니다.</p>
     */
    private String commandsTopic;

    /**
     * 기동 시 토픽 설정 유효성을 검증합니다.
     *
     * <p>필수 토픽 중 하나라도 누락되면 즉시 예외를 발생시켜 잘못된 설정 상태로
     * 서비스가 기동되지 않도록 보호합니다.</p>
     */
    @PostConstruct
    public void validate() {
        requireText("tc.ui.backend.kafka.gateway-events-topic", gatewayEventsTopic);
        requireText("tc.ui.backend.kafka.business-events-topic", businessEventsTopic);
        requireText("tc.ui.backend.kafka.commands-topic", commandsTopic);

        log.info(
                "UiKafkaTopicProperties 검증 완료. gatewayEvents={}, businessEvents={}, commands={}",
                gatewayEventsTopic,
                businessEventsTopic,
                commandsTopic
        );
    }

    /**
     * 필수 문자열 설정값이 비어 있는지 검증합니다.
     *
     * @param key   프로퍼티 키 (예외 메시지 구분용)
     * @param value 검증 대상 값
     * @throws IllegalStateException 값이 null 또는 공백인 경우
     */
    private static void requireText(final String key, final String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(key + " 는 필수 설정입니다.");
        }
    }

    public String getGatewayEventsTopic() {
        return gatewayEventsTopic;
    }

    public void setGatewayEventsTopic(final String gatewayEventsTopic) {
        this.gatewayEventsTopic = gatewayEventsTopic;
    }

    public String getBusinessEventsTopic() {
        return businessEventsTopic;
    }

    public void setBusinessEventsTopic(final String businessEventsTopic) {
        this.businessEventsTopic = businessEventsTopic;
    }

    public String getCommandsTopic() {
        return commandsTopic;
    }

    public void setCommandsTopic(final String commandsTopic) {
        this.commandsTopic = commandsTopic;
    }
}

package com.nori.tc.ui.adapters.kafka.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Business consumer group 조회 설정입니다(설비 화면 business_app 분류 기준).
 *
 * <p>기본값은 라이브 배포 설정과 같은 값이며, 운영 값은 exec 설정이 소유합니다 — 상수로 박지 않고
 * property 로 노출합니다. 값이 실제 Business 앱 설정
 * ({@code spring.kafka.consumer.group-id} / {@code tc.messaging.kafka.topic.eqp-events})과
 * 어긋나면 멤버가 0 으로 보이므로 두 값은 함께 관리합니다.</p>
 *
 * <p>바인딩 prefix: {@code tc.ui.backend.business-consumer}</p>
 */
@ConfigurationProperties(prefix = "tc.ui.backend.business-consumer")
public class UiBusinessConsumerProperties {

    private static final Logger log = LoggerFactory.getLogger(UiBusinessConsumerProperties.class);

    /** Business 앱의 Kafka consumer group id. */
    private String groupId = "tc-business-group";

    /** route partition 축이 되는 설비 이벤트 토픽. 이 토픽의 할당만 business_app 분류에 쓴다. */
    private String eqpEventsTopic = "tc.eqp.events";

    /** AdminClient 조회 대기 한도(ms). 화면 조회 경로라 브로커 지연이 화면을 붙잡지 않게 짧게 둔다. */
    private long adminTimeoutMs = 3_000L;

    /**
     * 기동 시 설정 유효성을 검증합니다.
     */
    @PostConstruct
    public void validate() {
        requireText("tc.ui.backend.business-consumer.group-id", groupId);
        requireText("tc.ui.backend.business-consumer.eqp-events-topic", eqpEventsTopic);
        if (adminTimeoutMs <= 0) {
            throw new IllegalStateException(
                    "tc.ui.backend.business-consumer.admin-timeout-ms 는 양수여야 합니다. value=" + adminTimeoutMs);
        }

        log.info(
                "UiBusinessConsumerProperties 검증 완료. groupId={}, eqpEventsTopic={}, adminTimeoutMs={}",
                groupId,
                eqpEventsTopic,
                adminTimeoutMs
        );
    }

    private static void requireText(final String key, final String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(key + " 는 필수 설정입니다.");
        }
    }

    public String getGroupId() {
        return groupId;
    }

    public void setGroupId(final String groupId) {
        this.groupId = groupId;
    }

    public String getEqpEventsTopic() {
        return eqpEventsTopic;
    }

    public void setEqpEventsTopic(final String eqpEventsTopic) {
        this.eqpEventsTopic = eqpEventsTopic;
    }

    public long getAdminTimeoutMs() {
        return adminTimeoutMs;
    }

    public void setAdminTimeoutMs(final long adminTimeoutMs) {
        this.adminTimeoutMs = adminTimeoutMs;
    }
}

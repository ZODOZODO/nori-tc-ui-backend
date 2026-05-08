package com.nori.tc.ui.core.port.messaging;

import com.nori.tc.ui.core.model.UiCommandMessage;

/**
 * tc.ui.events.gateway 토픽 발행 포트입니다.
 *
 * <p>역할:</p>
 * <p>eqp_create / eqp_update / eqp_delete / eqp_start / eqp_end 요청 시
 * Gateway로 Kafka 메시지를 발행합니다.</p>
 *
 * <p>U13 route_partition 규칙:</p>
 * <p>구현체(UiGatewayEventKafkaPublisher)는 발행 전 {@link UiGatewayEqpRoutePartitionLookupPort}를
 * 호출하여 route_partition을 조회한 후 ProducerRecord의 명시적 파티션으로 지정합니다.
 * route_partition이 미배정(빈 Optional) 또는 음수이면 발행을 차단하고 ERROR 로그를 남깁니다.</p>
 *
 * <p>구현체:</p>
 * <p>tc-ui-kafka-adapter의 UiGatewayEventKafkaPublisher가 이 인터페이스를 구현합니다.</p>
 */
@FunctionalInterface
public interface UiGatewayEventPublishPort {

    /**
     * Gateway 이벤트 토픽으로 Kafka 메시지를 발행합니다.
     *
     * <p>브로커 승인까지 동기 대기하며, 발행 실패는 즉시 예외로 전파합니다.</p>
     *
     * @param message 발행할 UI 명령 메시지
     * @throws IllegalStateException eqpId의 route_partition이 미배정인 경우
     */
    void publish(UiCommandMessage message);

    /**
     * Gateway 이벤트 토픽의 명시적 partition으로 Kafka 메시지를 발행합니다.
     *
     * <p>route_partition 이동 시 옛 owner pod에게 통보를 보내야 하므로 DB lookup이 아닌
     * 호출 측이 지정한 partition을 그대로 사용합니다. 주로 {@code EQP_PARTITION_RELEASE} 발행에 사용됩니다.</p>
     *
     * <p>본 메서드는 SAM(@FunctionalInterface) 호환을 위해 default 구현입니다. 운영 publisher는 반드시 override 해야 하며,
     * 미구현 publisher가 본 메서드를 호출하면 명시적으로 실패하여 운영 사고를 차단합니다.</p>
     *
     * @param message 발행할 UI 명령 메시지
     * @param partition 명시적 발행 대상 partition 번호 (0 이상)
     */
    default void publishToPartition(UiCommandMessage message, int partition) {
        throw new UnsupportedOperationException(
                "publishToPartition is not implemented by this publisher; route_partition release publishing is unavailable."
        );
    }

    /**
     * 테스트 또는 초기 구성에 사용할 noop(아무 동작 없음) 구현체를 반환합니다.
     *
     * @return 발행 동작을 무시하는 noop 구현체
     */
    static UiGatewayEventPublishPort noop() {
        return new UiGatewayEventPublishPort() {
            @Override
            public void publish(final UiCommandMessage message) {
                /* noop: 아무 동작 없음 */
            }

            @Override
            public void publishToPartition(final UiCommandMessage message, final int partition) {
                /* noop: 아무 동작 없음 */
            }
        };
    }
}

package com.nori.tc.ui.core.port.messaging;

import com.nori.tc.ui.core.model.UiCommandMessage;

/**
 * tc.ui.events.business 토픽 발행 포트입니다.
 *
 * <p>역할:</p>
 * <p>eqp_create / eqp_update / eqp_delete 요청 시 Business Core로 Kafka 메시지를 발행합니다.
 * Business Core는 이 토픽을 구독하여 자체 Bean 동기화(추가/수정/삭제)를 수행합니다.</p>
 *
 * <p>Gateway와의 차이:</p>
 * <ul>
 *   <li>eqp_start / eqp_end 는 Gateway 전담 → Business에 발행하지 않음</li>
 *   <li>route_partition 불필요 → 일반 {@code KafkaTemplate.send(topic, key, value)} 사용</li>
 * </ul>
 *
 * <p>구현체:</p>
 * <p>tc-ui-kafka-adapter의 UiBusinessEventKafkaPublisher가 이 인터페이스를 구현합니다.</p>
 */
@FunctionalInterface
public interface UiBusinessEventPublishPort {

    /**
     * Business 이벤트 토픽으로 Kafka 메시지를 발행합니다.
     *
     * <p>브로커 승인까지 동기 대기하며, 발행 실패는 즉시 예외로 전파합니다.</p>
     *
     * @param message 발행할 UI 명령 메시지
     */
    void publish(UiCommandMessage message);

    /**
     * 테스트 또는 초기 구성에 사용할 noop(아무 동작 없음) 구현체를 반환합니다.
     *
     * @return 발행 동작을 무시하는 noop 구현체
     */
    static UiBusinessEventPublishPort noop() {
        return message -> { /* noop: 아무 동작 없음 */ };
    }
}

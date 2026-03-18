package com.nori.tc.ui.core.port.messaging;

import com.nori.tc.ui.core.model.UiCommandReply;

/**
 * tc.ui.commands 토픽 수신 처리 포트입니다.
 *
 * <p>역할:</p>
 * <p>tc.ui.commands 토픽에서 수신한 Gateway / Business Core 응답 메시지를
 * 처리 유형에 따라 적절한 핸들러로 라우팅합니다.</p>
 *
 * <p>처리 분기:</p>
 * <ul>
 *   <li>EQP_CREATE_REP / EQP_UPDATE_REP / EQP_DELETE_REP →
 *       {@link com.nori.tc.ui.core.registry.DualResponseRegistry}에서
 *       traceId + source로 양방향 응답을 수집</li>
 *   <li>EQP_START_REP / EQP_END_REP →
 *       {@link com.nori.tc.ui.core.port.redis.AsyncResultStorePort}에 Redis 임시 저장
 *       (Gateway lifecycle 상태머신 완료 후 지연 발행)</li>
 * </ul>
 *
 * <p>구현체:</p>
 * <p>tc-ui-core의 {@link com.nori.tc.ui.core.service.UiCommandIngressService}가
 * 이 인터페이스를 구현합니다. tc-ui-kafka-adapter의 UiCommandKafkaSubscriber가
 * Kafka 메시지 수신 후 이 포트를 호출합니다.</p>
 */
@FunctionalInterface
public interface UiCommandIngressPort {

    /**
     * 수신된 tc.ui.commands 메시지를 처리합니다.
     *
     * <p>eventType에 따라 DualResponseRegistry 또는 AsyncResultStorePort로 라우팅합니다.</p>
     *
     * @param reply 수신된 명령 응답 DTO (eventType, traceId, source 포함)
     */
    void handle(UiCommandReply reply);

    /**
     * 테스트 또는 초기 구성에 사용할 noop(아무 동작 없음) 구현체를 반환합니다.
     *
     * @return 수신된 메시지를 무시하는 noop 구현체
     */
    static UiCommandIngressPort noop() {
        return reply -> { /* noop: 아무 동작 없음 */ };
    }
}

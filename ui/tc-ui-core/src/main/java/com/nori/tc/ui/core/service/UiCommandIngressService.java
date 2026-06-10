package com.nori.tc.ui.core.service;

import com.nori.tc.ui.core.model.UiCommandReply;
import com.nori.tc.ui.core.port.messaging.UiCommandIngressPort;
import com.nori.tc.ui.core.port.redis.AsyncResultStorePort;
import com.nori.tc.ui.core.registry.DualResponseRegistry;
import com.nori.tc.ui.domain.task.UiTaskResult;
import com.nori.tc.ui.domain.task.UiTaskStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * tc.ui.commands 토픽 수신 메시지 처리 서비스입니다.
 *
 * <p>역할:</p>
 * <p>{@link UiCommandIngressPort}의 구현체로, tc-ui-kafka-adapter의
 * UiCommandKafkaSubscriber가 Kafka에서 수신한 메시지를 넘겨주면
 * eventType에 따라 적절한 핸들러로 라우팅합니다.</p>
 *
 * <p>라우팅 규칙:</p>
 * <ul>
 *   <li>EQP_CREATE_REP / EQP_UPDATE_REP / EQP_MODEL_UPDATE_REP / EQP_DELETE_REP /
 *       EQP_GATEWAY_JARFILE_UPDATE_REP / EQP_BUSINESS_JARFILE_UPDATE_REP →
 *       {@link DualResponseRegistry}: Gateway와 Business 양쪽 응답을 수집하여
 *       DeferredResult 완료 처리</li>
 *   <li>EQP_START_REP / EQP_END_REP / EQP_RECONNECT_REP /
 *       EQP_SEND_MESSAGE_REP / EQP_PARAMETER_UPDATE_REP /
 *       EQP_PLC_GROUP_START_REP / EQP_PLC_GROUP_END_REP →
 *       {@link AsyncResultStorePort}: Gateway 단일 응답을 Redis에 저장,
 *       front가 polling으로 확인</li>
 * </ul>
 *
 * <p>eventType 계약:</p>
 * <p>Gateway와 Business Core는 처리 완료 후 원본 요청 eventType에 {@code _REP} 접미사를
 * 붙여 tc.ui.commands에 발행합니다. 예) EQP_CREATE 요청 → EQP_CREATE_REP 응답.</p>
 */
@Component
public class UiCommandIngressService implements UiCommandIngressPort {

    private static final Logger log = LoggerFactory.getLogger(UiCommandIngressService.class);

    private final DeployService deployService;
    private final DualResponseRegistry dualResponseRegistry;
    private final AsyncResultStorePort asyncResultStorePort;

    /**
     * 필수 의존성을 주입받습니다.
     *
     * @param dualResponseRegistry eqp_create/update/delete DeferredResult 관리 레지스트리
     * @param asyncResultStorePort eqp_start/end 비동기 결과 Redis 저장 포트
     */
    public UiCommandIngressService(
            final DeployService deployService,
            final DualResponseRegistry dualResponseRegistry,
            final AsyncResultStorePort asyncResultStorePort
    ) {
        this.deployService = Objects.requireNonNull(deployService, "deployService is null");
        this.dualResponseRegistry = Objects.requireNonNull(dualResponseRegistry, "dualResponseRegistry is null");
        this.asyncResultStorePort = Objects.requireNonNull(asyncResultStorePort, "asyncResultStorePort is null");
    }

    /**
     * 수신된 tc.ui.commands 메시지를 eventType에 따라 라우팅합니다.
     *
     * <p>알 수 없는 eventType은 WARN 로그 후 무시합니다.
     * 수신 INFO 로그는 UiCommandKafkaSubscriber에서 이미 출력하므로
     * 이 서비스 계층에서는 라우팅 실패 경우에만 WARN을 기록합니다.</p>
     *
     * @param reply 수신된 Kafka reply 메시지
     */
    @Override
    public void handle(final UiCommandReply reply) {
        Objects.requireNonNull(reply, "reply is null");

        if (deployService.handleReply(reply)) {
            return;
        }

        final String eventType = reply.eventType().trim().toUpperCase();
        final String traceId   = reply.traceId();
        final String source    = reply.source();

        switch (eventType) {
            // dual-response: Gateway + Business 양방향 응답 수집
            case "EQP_CREATE_REP",
                 "EQP_UPDATE_REP",
                 "EQP_MODEL_UPDATE_REP",
                 "EQP_DELETE_REP",
                 "EQP_GATEWAY_JARFILE_UPDATE_REP",
                 "EQP_BUSINESS_JARFILE_UPDATE_REP" ->
                handleDualResponse(traceId, source, reply);

            // 단일 응답: polling 결과 저장
            case "EQP_START_REP",
                 "EQP_END_REP",
                 "EQP_RECONNECT_REP",
                 "EQP_SEND_MESSAGE_REP",
                 "EQP_PARAMETER_UPDATE_REP",
                 "EQP_PLC_GROUP_START_REP",
                 "EQP_PLC_GROUP_END_REP" ->
                handleAsyncResult(traceId, reply);

            default -> log.warn("처리되지 않은 eventType - 무시. eventType={}, traceId={}, source={}",
                    eventType, traceId, source);
        }
    }

    /**
     * eqp_create / eqp_update / eqp_delete 응답을 DualResponseRegistry에 기록합니다.
     *
     * <p>Gateway(TC-GATEWAY-APP)와 Business(TC-BUSINESS-APP) 양쪽 응답을
     * traceId + source 조합으로 매핑합니다. 양쪽 모두 수신 완료 시 Registry가
     * 자동으로 CompletableFuture를 완료시킵니다.</p>
     *
     * @param traceId 작업 추적 ID
     * @param source  응답 출처 (TC-GATEWAY-APP 또는 TC-BUSINESS-APP)
     * @param reply   수신된 reply 메시지
     */
    private void handleDualResponse(
            final String traceId,
            final String source,
            final UiCommandReply reply
    ) {
        final UiTaskResult result = toUiTaskResult(traceId, source, reply);
        log.info("DualResponse 기록. traceId={}, source={}, status={}",
                traceId, source, result.status());
        dualResponseRegistry.record(traceId, source, result);
    }

    /**
     * eqp_start / eqp_end 응답을 Redis에 저장합니다.
     *
     * <p>front가 GET /api/async/{traceId} polling으로 결과를 확인합니다.
     * Gateway 단일 응답이므로 DualResponseRegistry를 거치지 않습니다.</p>
     *
     * @param traceId 작업 추적 ID
     * @param reply   수신된 reply 메시지
     */
    private void handleAsyncResult(final String traceId, final UiCommandReply reply) {
        log.info("비동기 결과 COMPLETED 저장. traceId={}, status={}", traceId, reply.status());
        asyncResultStorePort.markCompleted(traceId, reply);
    }

    /**
     * UiCommandReply를 UiTaskResult 도메인 객체로 변환합니다.
     *
     * @param traceId 작업 추적 ID
     * @param source  응답 출처
     * @param reply   변환할 reply 메시지
     * @return 변환된 UiTaskResult
     */
    private UiTaskResult toUiTaskResult(
            final String traceId,
            final String source,
            final UiCommandReply reply
    ) {
        if (reply.status() == UiTaskStatus.PASS) {
            return UiTaskResult.pass(traceId, source);
        }
        return UiTaskResult.fail(traceId, source, reply.errorCode(), reply.errorMsg());
    }
}

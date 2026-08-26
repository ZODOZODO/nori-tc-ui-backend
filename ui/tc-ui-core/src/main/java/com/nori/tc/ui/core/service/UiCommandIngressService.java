package com.nori.tc.ui.core.service;

import com.nori.tc.ui.core.coordination.UiReplyProofIngestService;
import com.nori.tc.ui.core.model.UiCommandReply;
import com.nori.tc.ui.core.port.messaging.UiCommandIngressPort;
import org.springframework.beans.factory.ObjectProvider;
import com.nori.tc.ui.core.port.redis.AsyncResultStorePort;
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
 *       {@link UiReplyProofIngestService}: 조정 원장에 proof 를 기록하고, front 는
 *       operationId 로 진행 상태를 조회합니다</li>
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
    private final AsyncResultStorePort asyncResultStorePort;
    private final ObjectProvider<UiReplyProofIngestService> replyProofIngestProvider;

    /**
     * 필수 의존성을 주입받습니다.
     *
     * @param deployService deploy reply 라우팅 서비스
     * @param asyncResultStorePort eqp_start/end 비동기 결과 Redis 저장 포트
     * @param replyProofIngestProvider 조정 reply proof 기록 서비스(비조정 구성이면 비어 있음)
     */
    public UiCommandIngressService(
            final DeployService deployService,
            final AsyncResultStorePort asyncResultStorePort,
            final ObjectProvider<UiReplyProofIngestService> replyProofIngestProvider
    ) {
        this.deployService = Objects.requireNonNull(deployService, "deployService is null");
        this.asyncResultStorePort = Objects.requireNonNull(asyncResultStorePort, "asyncResultStorePort is null");
        this.replyProofIngestProvider =
                Objects.requireNonNull(replyProofIngestProvider, "replyProofIngestProvider is null");
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

        /*
         * §13.5 — proof 기록이 먼저다. 원장 기록에 실패하면 여기서 예외가 올라가 ACK 가 막히고,
         * 레거시 registry/Pub/Sub 경로가 없어도 operation 은 이 경로만으로 전진한다.
         */
        final UiReplyProofIngestService replyProofIngest = replyProofIngestProvider.getIfAvailable();
        if (replyProofIngest != null && reply.hasCoordinationProof()) {
            replyProofIngest.ingest(reply, System.currentTimeMillis());
        }

        if (deployService.handleReply(reply)) {
            return;
        }

        final String eventType = reply.eventType().trim().toUpperCase();
        final String traceId   = reply.traceId();
        final String source    = reply.source();

        switch (eventType) {
            /*
             * 설비 mutation reply 의 상태 기록은 위 proof ingest 가 이미 마쳤다(§13.5).
             * 이 분기는 알려진 eventType 임을 표시해 default 의 "처리되지 않은 eventType" 경고를
             * 피하기 위해 남긴다 — 종전의 DualResponseRegistry 기록은 그 future 를 기다리는 호출부가
             * 워크스페이스 전체에 없어(register 호출 0건) 아무도 읽지 않는 쓰기였으므로 제거했다.
             */
            case "EQP_CREATE_REP",
                 "EQP_UPDATE_REP",
                 "EQP_MODEL_UPDATE_REP",
                 "EQP_DELETE_REP",
                 "EQP_GATEWAY_JARFILE_UPDATE_REP",
                 "EQP_BUSINESS_JARFILE_UPDATE_REP" ->
                log.debug("설비 mutation reply 수신. traceId={}, source={}, status={}",
                        traceId, source, reply.status());

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
     * eqp_start / eqp_end 응답을 Redis에 저장합니다.
     *
     * <p>front가 GET /api/async/{traceId} polling으로 결과를 확인합니다.</p>
     *
     * @param traceId 작업 추적 ID
     * @param reply   수신된 reply 메시지
     */
    private void handleAsyncResult(final String traceId, final UiCommandReply reply) {
        log.info("비동기 결과 COMPLETED 저장. traceId={}, status={}", traceId, reply.status());
        asyncResultStorePort.markCompleted(traceId, reply);
    }
}

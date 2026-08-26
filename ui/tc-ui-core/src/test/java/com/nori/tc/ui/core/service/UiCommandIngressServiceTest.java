package com.nori.tc.ui.core.service;

import com.nori.tc.ui.core.coordination.UiReplyProofIngestService;
import com.nori.tc.ui.core.model.UiCommandReply;
import com.nori.tc.ui.core.model.UiCommandReplyProof;
import com.nori.tc.ui.core.port.redis.AsyncResultStorePort;
import com.nori.tc.ui.domain.task.UiTaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UiCommandIngressServiceTest {

    @Mock
    private DeployService deployService;

    @Mock
    private AsyncResultStorePort asyncResultStorePort;

    @Mock
    private UiReplyProofIngestService replyProofIngestService;

    private ObjectProvider<UiReplyProofIngestService> replyProofIngestProvider;
    private UiCommandIngressService ingressService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        replyProofIngestProvider = mock(ObjectProvider.class);
        ingressService = new UiCommandIngressService(
                deployService, asyncResultStorePort, replyProofIngestProvider);
    }

    private static UiCommandReply replyOf(final String traceId, final String eventType) {
        return new UiCommandReply(
                traceId, "TC-BUSINESS-APP", eventType, "PHOTO_001", "SOCKET",
                UiTaskStatus.PASS, null, null);
    }

    @Test
    void handleShouldRejectNullReplyBeforeRouting() {
        final NullPointerException exception = assertThrows(
                NullPointerException.class,
                () -> ingressService.handle(null)
        );

        assertEquals("reply is null", exception.getMessage());
        verify(deployService, never()).handleReply(any());
        verify(asyncResultStorePort, never()).markCompleted(any(), any());
    }

    @Test
    void deployTraceReplyIsHandledBeforeLegacyRouting() {
        final UiCommandReply reply = replyOf("DEPLOY-trace-001", "EQP_UPDATE_REP");
        when(deployService.handleReply(reply)).thenReturn(true);

        ingressService.handle(reply);

        verify(deployService).handleReply(reply);
        verify(asyncResultStorePort, never()).markCompleted(any(), any());
    }

    @Test
    void directTraceReplyIsHandledBeforeLegacyRouting() {
        final UiCommandReply reply = replyOf("DIRECT-trace-001", "EQP_START_REP");
        when(deployService.handleReply(reply)).thenReturn(true);

        ingressService.handle(reply);

        verify(deployService).handleReply(reply);
        verify(asyncResultStorePort, never()).markCompleted(any(), any());
    }

    @Test
    @DisplayName("설비 mutation reply 는 async 결과 저장소로 새지 않는다")
    void mutationReplyDoesNotLeakIntoAsyncResultStore() {
        final UiCommandReply reply = replyOf("trace-create-001", "EQP_CREATE_REP");
        when(deployService.handleReply(reply)).thenReturn(false);

        ingressService.handle(reply);

        verify(deployService).handleReply(reply);
        /*
         * mutation reply 의 상태 기록은 조정 원장 proof 가 담당한다. 이 분기가 async 저장소로
         * 흘러가면 front 가 lifecycle 결과와 mutation 결과를 같은 traceId 로 섞어 읽는다.
         */
        verify(asyncResultStorePort, never()).markCompleted(any(), any());
    }

    @Test
    @DisplayName("proof 를 가진 reply 는 라우팅보다 먼저 원장에 기록된다")
    void proofBearingReplyIsIngestedBeforeRouting() {
        final UiCommandReply reply = new UiCommandReply(
                "trace-create-002", "TC-BUSINESS-APP", "EQP_CREATE_REP", "PHOTO_001", "SOCKET",
                UiTaskStatus.PASS, null, null,
                new UiCommandReplyProof(
                        "op-1", UiCommandReplyProof.STATE_APPLIED,
                        "tc-business:pod-1:sess-1", null, 3L, 3L, "digest"));
        when(deployService.handleReply(reply)).thenReturn(false);
        when(replyProofIngestProvider.getIfAvailable()).thenReturn(replyProofIngestService);

        ingressService.handle(reply);

        verify(replyProofIngestService).ingest(eq(reply), anyLong());
    }

    @Test
    @DisplayName("원장 기록이 실패하면 예외가 올라가 ACK 가 막힌다")
    void proofIngestFailurePropagatesSoAckIsBlocked() {
        final UiCommandReply reply = new UiCommandReply(
                "trace-create-003", "TC-BUSINESS-APP", "EQP_CREATE_REP", "PHOTO_001", "SOCKET",
                UiTaskStatus.PASS, null, null,
                new UiCommandReplyProof(
                        "op-2", UiCommandReplyProof.STATE_APPLIED,
                        "tc-business:pod-1:sess-1", null, 3L, 3L, "digest"));
        when(replyProofIngestProvider.getIfAvailable()).thenReturn(replyProofIngestService);
        doThrowOnIngest(reply);

        assertThrows(IllegalStateException.class, () -> ingressService.handle(reply));

        // proof 기록 전에는 아무 라우팅도 일어나지 않아야 한다(§13.5).
        verify(deployService, never()).handleReply(any());
        verify(asyncResultStorePort, never()).markCompleted(any(), any());
    }

    private void doThrowOnIngest(final UiCommandReply reply) {
        org.mockito.Mockito.doThrow(new IllegalStateException("ledger write failed"))
                .when(replyProofIngestService).ingest(eq(reply), anyLong());
    }

    @Test
    void sendMessageReplyRoutesToLegacyAsyncStore() {
        final UiCommandReply reply = replyOf("trace-send-001", "EQP_SEND_MESSAGE_REP");
        when(deployService.handleReply(reply)).thenReturn(false);

        ingressService.handle(reply);

        verify(deployService).handleReply(reply);
        verify(asyncResultStorePort).markCompleted("trace-send-001", reply);
    }
}

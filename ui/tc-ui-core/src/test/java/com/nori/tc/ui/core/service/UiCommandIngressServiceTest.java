package com.nori.tc.ui.core.service;

import com.nori.tc.ui.core.model.UiCommandReply;
import com.nori.tc.ui.core.port.redis.AsyncResultStorePort;
import com.nori.tc.ui.core.registry.DualResponseRegistry;
import com.nori.tc.ui.core.registry.DualResponseRedisLookupResult;
import com.nori.tc.ui.domain.task.UiTaskResult;
import com.nori.tc.ui.domain.task.UiTaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UiCommandIngressServiceTest {

    @Mock
    private DeployService deployService;

    @Mock
    private AsyncResultStorePort asyncResultStorePort;

    private RecordingDualResponseRedisPort dualResponseRedisPort;
    private DualResponseRegistry dualResponseRegistry;
    private UiCommandIngressService ingressService;

    @BeforeEach
    void setUp() {
        dualResponseRedisPort = new RecordingDualResponseRedisPort();
        dualResponseRegistry = new DualResponseRegistry(dualResponseRedisPort);
        ingressService = new UiCommandIngressService(deployService, dualResponseRegistry, asyncResultStorePort);
    }

    @Test
    void deployTraceReplyIsHandledBeforeLegacyRouting() {
        final UiCommandReply reply = new UiCommandReply(
                "DEPLOY-trace-001",
                "TC-GATEWAY-APP",
                "EQP_UPDATE_REP",
                "PHOTO_001",
                "SOCKET",
                UiTaskStatus.PASS,
                null,
                null
        );
        when(deployService.handleReply(reply)).thenReturn(true);

        ingressService.handle(reply);

        verify(deployService).handleReply(reply);
        verify(asyncResultStorePort, never()).markCompleted(any(), any());
        assertEquals(0, dualResponseRedisPort.recordedResults.size());
    }

    @Test
    void directTraceReplyIsHandledBeforeLegacyRouting() {
        final UiCommandReply reply = new UiCommandReply(
                "DIRECT-trace-001",
                "TC-GATEWAY-APP",
                "EQP_START_REP",
                "PHOTO_001",
                "SOCKET",
                UiTaskStatus.PASS,
                null,
                null
        );
        when(deployService.handleReply(reply)).thenReturn(true);

        ingressService.handle(reply);

        verify(deployService).handleReply(reply);
        verify(asyncResultStorePort, never()).markCompleted(any(), any());
        assertEquals(0, dualResponseRedisPort.recordedResults.size());
    }

    @Test
    void createReplyRoutesToDualResponseRegistry() {
        final UiCommandReply reply = new UiCommandReply(
                "trace-create-001",
                "TC-BUSINESS-APP",
                "EQP_CREATE_REP",
                "PHOTO_001",
                "SOCKET",
                UiTaskStatus.FAIL,
                "BUSINESS_CREATE_FAILED",
                "Business create failed"
        );
        when(deployService.handleReply(reply)).thenReturn(false);

        ingressService.handle(reply);

        verify(deployService).handleReply(reply);
        assertEquals(1, dualResponseRedisPort.recordedResults.size());

        final RecordingDualResponseRedisPort.RecordedResult recorded = dualResponseRedisPort.recordedResults.getFirst();
        assertEquals("trace-create-001", recorded.traceId());
        assertEquals("TC-BUSINESS-APP", recorded.source());
        assertFalse(recorded.result().isSuccess());
        assertEquals("BUSINESS_CREATE_FAILED", recorded.result().errorCode());
    }

    @Test
    void sendMessageReplyRoutesToLegacyAsyncStore() {
        final UiCommandReply reply = new UiCommandReply(
                "trace-send-001",
                "TC-GATEWAY-APP",
                "EQP_SEND_MESSAGE_REP",
                "PHOTO_001",
                "HSMS",
                UiTaskStatus.PASS,
                null,
                null
        );
        when(deployService.handleReply(reply)).thenReturn(false);

        ingressService.handle(reply);

        verify(deployService).handleReply(reply);
        verify(asyncResultStorePort).markCompleted("trace-send-001", reply);
        assertTrue(dualResponseRedisPort.recordedResults.isEmpty());
    }

    private static final class RecordingDualResponseRedisPort implements com.nori.tc.ui.core.port.redis.DualResponseRedisPort {

        private final java.util.List<RecordedResult> recordedResults = new java.util.ArrayList<>();

        @Override
        public void register(final String traceId, final long timeoutMs) {
        }

        @Override
        public void record(final String traceId, final String source, final UiTaskResult result) {
            recordedResults.add(new RecordedResult(traceId, source, result));
        }

        @Override
        public DualResponseRedisLookupResult getResult(final String traceId) {
            return DualResponseRedisLookupResult.pending();
        }

        @Override
        public void cancel(final String traceId) {
        }

        private record RecordedResult(
                String traceId,
                String source,
                UiTaskResult result
        ) {
        }
    }
}

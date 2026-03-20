package com.nori.tc.ui.adapters.web.controller;

import com.nori.tc.ui.core.model.AsyncResultEntry;
import com.nori.tc.ui.core.model.UiCommandReply;
import com.nori.tc.ui.core.port.redis.AsyncResultStorePort;
import com.nori.tc.ui.domain.task.UiTaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AsyncResultControllerTest {

    @Mock
    private AsyncResultStorePort asyncResultStorePort;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new AsyncResultController(asyncResultStorePort)).build();
    }

    @Test
    void pendingTraceReturnsAccepted() throws Exception {
        when(asyncResultStorePort.getWithStatus("trace-pending"))
                .thenReturn(Optional.of(AsyncResultEntry.pending("trace-pending", 1_900_000_000_000L)));

        mockMvc.perform(get("/api/async/trace-pending"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.traceId").value("trace-pending"))
                .andExpect(jsonPath("$.data.status").value("PENDING"));
    }

    @Test
    void completedTraceReturnsReplyPayload() throws Exception {
        final UiCommandReply reply = new UiCommandReply(
                "trace-completed",
                "TC-GATEWAY-APP",
                "EQP_START_REP",
                "PHOTO_001",
                "SOCKET",
                UiTaskStatus.FAIL,
                "START_FAILED",
                "설비 시작에 실패했습니다."
        );
        when(asyncResultStorePort.getWithStatus("trace-completed"))
                .thenReturn(Optional.of(AsyncResultEntry.completed("trace-completed", reply)));

        mockMvc.perform(get("/api/async/trace-completed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.traceId").value("trace-completed"))
                .andExpect(jsonPath("$.data.eqpId").value("PHOTO_001"))
                .andExpect(jsonPath("$.data.status").value("FAIL"))
                .andExpect(jsonPath("$.data.errorCode").value("START_FAILED"));
    }

    @Test
    void timeoutTraceReturnsRequestTimeout() throws Exception {
        when(asyncResultStorePort.getWithStatus("trace-timeout"))
                .thenReturn(Optional.of(AsyncResultEntry.timeout("trace-timeout")));

        mockMvc.perform(get("/api/async/trace-timeout"))
                .andExpect(status().isRequestTimeout())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("TIMEOUT"))
                .andExpect(jsonPath("$.data.errorCode").value("TIMEOUT"));
    }

    @Test
    void missingTraceReturnsNotFound() throws Exception {
        when(asyncResultStorePort.getWithStatus("trace-missing"))
                .thenReturn(Optional.empty());

        mockMvc.perform(get("/api/async/trace-missing"))
                .andExpect(status().isNotFound());
    }
}

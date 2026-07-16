package com.nori.tc.ui.adapters.web.controller;

import tools.jackson.databind.node.ObjectNode;
import com.nori.tc.ui.adapters.web.config.UiDualRequestProperties;
import com.nori.tc.ui.adapters.web.dto.request.EqpLifecycleRequest;
import com.nori.tc.ui.adapters.web.dto.response.ApiResponse;
import com.nori.tc.ui.adapters.web.dto.response.AsyncAcceptResponse;
import com.nori.tc.ui.adapters.web.dto.response.EqpRuntimeStateResponse;
import com.nori.tc.ui.core.eqp.EqpAsyncAcceptResult;
import com.nori.tc.ui.core.port.db.EqpQueryPort;

import java.util.Optional;
import com.nori.tc.ui.core.service.EqpManagementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EqpControllerLifecycleTest {

    @Mock
    private EqpQueryPort eqpQueryPort;

    @Mock
    private EqpManagementService eqpManagementService;

    private EqpController eqpController;

    @BeforeEach
    void setUp() {
        final UiDualRequestProperties properties = new UiDualRequestProperties();
        properties.setDualRequestTimeoutMs(15_000L);
        properties.validate();

        eqpController = new EqpController(
                eqpQueryPort,
                eqpManagementService,
                properties
        );
    }

    @Test
    void startReturnsAcceptedAndDelegatesToScenarioService() {
        when(eqpManagementService.startAsync(eq("PHOTO_001"), eq("SOCKET"), any(), eq(15_000L)))
                .thenReturn(EqpAsyncAcceptResult.accepted("trace-start-001"));

        final ResponseEntity<ApiResponse<AsyncAcceptResponse>> response = eqpController.start(
                "PHOTO_001",
                new EqpLifecycleRequest("SOCKET", uiMessage("USER_REQUEST"))
        );

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(true, response.getBody().success());
        assertNotNull(response.getBody().data());
        assertEquals("trace-start-001", response.getBody().data().traceId());

        verify(eqpManagementService).startAsync(eq("PHOTO_001"), eq("SOCKET"), any(), eq(15_000L));
    }

    @Test
    void endReturnsServerErrorWhenAsyncAcceptFails() {
        when(eqpManagementService.endAsync(eq("PHOTO_001"), eq("HSMS"), any(), eq(15_000L)))
                .thenReturn(EqpAsyncAcceptResult.error(500, "PUBLISH_FAILED", "Kafka 발행 중 오류가 발생했습니다."));

        final ResponseEntity<ApiResponse<AsyncAcceptResponse>> response = eqpController.end(
                "PHOTO_001",
                new EqpLifecycleRequest("HSMS", uiMessage("USER_REQUEST"))
        );

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertNotNull(response.getBody());
        assertFalse(response.getBody().success());
        assertEquals("PUBLISH_FAILED", response.getBody().errorCode());
        verify(eqpManagementService).endAsync(eq("PHOTO_001"), eq("HSMS"), any(), eq(15_000L));
    }

    @Test
    void reconnectReturnsAcceptedAndDelegatesToReconnectAsync() {
        when(eqpManagementService.reconnectAsync(eq("PHOTO_001"), eq("SOCKET"), any(), eq(15_000L)))
                .thenReturn(EqpAsyncAcceptResult.accepted("trace-reconnect-001"));

        final ResponseEntity<ApiResponse<AsyncAcceptResponse>> response = eqpController.reconnect(
                "PHOTO_001",
                new EqpLifecycleRequest("SOCKET", uiMessage("RECOVER_STUCK"))
        );

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(true, response.getBody().success());
        assertEquals("trace-reconnect-001", response.getBody().data().traceId());
        // AC-B4: POST /eqp/{id}/reconnect 는 reconnectAsync(EQP_RECONNECT)로 위임한다.
        verify(eqpManagementService).reconnectAsync(eq("PHOTO_001"), eq("SOCKET"), any(), eq(15_000L));
    }

    @Test
    void getRuntimeStateIncludesDesiredState() {
        when(eqpQueryPort.findRuntimeStateByEqpId("PHOTO_001"))
                .thenReturn(Optional.of(new EqpQueryPort.EqpRuntimeStateView("OFFLINE", "IDLE", "STARTED", "STARTED")));

        final ResponseEntity<ApiResponse<EqpRuntimeStateResponse>> response = eqpController.getRuntimeState("PHOTO_001");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertNotNull(response.getBody().data());
        // AC-C1: runtime-state 응답이 desiredState 를 포함하고 DB 값과 일치한다.
        assertEquals("STARTED", response.getBody().data().desiredState());
        assertEquals("OFFLINE", response.getBody().data().controlState());
    }

    private ObjectNode uiMessage(final String reason) {
        final ObjectNode uiMessage = new tools.jackson.databind.ObjectMapper().createObjectNode();
        uiMessage.put("reason", reason);
        return uiMessage;
    }
}

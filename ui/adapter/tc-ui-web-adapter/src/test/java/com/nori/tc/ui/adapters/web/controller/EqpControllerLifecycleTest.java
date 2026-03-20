package com.nori.tc.ui.adapters.web.controller;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import com.nori.tc.ui.adapters.web.config.UiDualRequestProperties;
import com.nori.tc.ui.adapters.web.dto.request.EqpLifecycleRequest;
import com.nori.tc.ui.adapters.web.dto.response.ApiResponse;
import com.nori.tc.ui.adapters.web.dto.response.AsyncAcceptResponse;
import com.nori.tc.ui.core.model.UiCommandEventType;
import com.nori.tc.ui.core.model.UiCommandMessage;
import com.nori.tc.ui.core.port.db.EqpQueryPort;
import com.nori.tc.ui.core.port.messaging.UiGatewayEventPublishPort;
import com.nori.tc.ui.core.port.redis.AsyncResultStorePort;
import com.nori.tc.ui.core.service.EqpManagementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class EqpControllerLifecycleTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private EqpQueryPort eqpQueryPort;

    @Mock
    private EqpManagementService eqpManagementService;

    @Mock
    private UiGatewayEventPublishPort gatewayEventPublishPort;

    @Mock
    private AsyncResultStorePort asyncResultStorePort;

    private EqpController eqpController;

    @BeforeEach
    void setUp() {
        final UiDualRequestProperties properties = new UiDualRequestProperties();
        properties.setDualRequestTimeoutMs(15_000L);
        properties.validate();

        eqpController = new EqpController(
                eqpQueryPort,
                eqpManagementService,
                gatewayEventPublishPort,
                asyncResultStorePort,
                properties
        );
    }

    @Test
    void startReturnsAcceptedAndRegistersPending() {
        final ResponseEntity<ApiResponse<AsyncAcceptResponse>> response = eqpController.start(
                "PHOTO_001",
                new EqpLifecycleRequest("SOCKET", uiMessage("USER_REQUEST"))
        );

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(true, response.getBody().success());
        assertNotNull(response.getBody().data());
        assertNotNull(response.getBody().data().traceId());

        final ArgumentCaptor<String> traceIdCaptor = ArgumentCaptor.forClass(String.class);
        final ArgumentCaptor<UiCommandMessage> messageCaptor = ArgumentCaptor.forClass(UiCommandMessage.class);
        verify(asyncResultStorePort).registerPending(traceIdCaptor.capture(), eq(15_000L));
        verify(gatewayEventPublishPort).publish(messageCaptor.capture());

        final String traceId = traceIdCaptor.getValue();
        final UiCommandMessage message = messageCaptor.getValue();
        assertEquals(traceId, response.getBody().data().traceId());
        assertEquals(UiCommandEventType.EQP_START, message.eventType());
        assertEquals("TC-UI-APP", message.source());
        assertEquals("PHOTO_001", message.eqpId());
        assertEquals("SOCKET", message.interfaceType());
        assertEquals("USER_REQUEST", message.uiMessage().path("reason").asString());
    }

    @Test
    void endPublishFailureMarksTimeoutAndReturnsServerError() {
        doThrow(new IllegalStateException("publish failed"))
                .when(gatewayEventPublishPort).publish(org.mockito.ArgumentMatchers.any(UiCommandMessage.class));

        final ResponseEntity<ApiResponse<AsyncAcceptResponse>> response = eqpController.end(
                "PHOTO_001",
                new EqpLifecycleRequest("HSMS", uiMessage("USER_REQUEST"))
        );

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertNotNull(response.getBody());
        assertFalse(response.getBody().success());
        assertEquals("PUBLISH_FAILED", response.getBody().errorCode());

        final ArgumentCaptor<String> traceIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(asyncResultStorePort).registerPending(traceIdCaptor.capture(), eq(15_000L));
        verify(asyncResultStorePort).markTimeout(eq(traceIdCaptor.getValue()));
    }

    private ObjectNode uiMessage(final String reason) {
        final ObjectNode uiMessage = objectMapper.createObjectNode();
        uiMessage.put("reason", reason);
        return uiMessage;
    }
}

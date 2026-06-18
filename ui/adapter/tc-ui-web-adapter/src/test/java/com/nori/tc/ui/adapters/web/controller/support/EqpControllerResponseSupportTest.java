package com.nori.tc.ui.adapters.web.controller.support;

import com.nori.tc.ui.adapters.web.dto.response.EqpManageDetailResponse;
import com.nori.tc.ui.core.eqp.EqpManagementSnapshot;
import com.nori.tc.ui.domain.common.model.UiProtocolType;
import com.nori.tc.ui.domain.eqp.UiEqp;
import com.nori.tc.ui.domain.eqp.UiEqpPlc;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class EqpControllerResponseSupportTest {

    private static final OffsetDateTime NOW = OffsetDateTime.now(ZoneOffset.UTC);

    @Test
    void plcEquipmentDetailExposesPlcSettings() {
        final EqpManagementSnapshot snapshot = new EqpManagementSnapshot(
                uiEqp("PLC_001", UiProtocolType.PLC),
                null,
                null,
                null,
                new UiEqpPlc(1L, "MODBUS_TCP", "TCP", 1, 1000, 3, List.of(), 0L, NOW, NOW),
                null, null, null, null, null, null, null, null
        );

        final EqpManageDetailResponse response = EqpControllerResponseSupport.toManageDetailResponse(snapshot);

        // Model Info Update 왕복 시 detail.plcSettings 가 null 이면 PLC 검증(plcSettings 필수)에서 400 이 난다.
        // detail 응답이 plcSettings 를 운반해야 클라이언트가 그대로 되돌려보내 round-trip 이 성립한다.
        assertNotNull(response.plcSettings());
        assertEquals("MODBUS_TCP", response.plcSettings().plcProtocol());
        assertEquals("TCP", response.plcSettings().transport());
        assertEquals(1, response.plcSettings().unitId().intValue());
        assertEquals(1000, response.plcSettings().pollTimeoutMs().intValue());
        assertEquals(3, response.plcSettings().reconnectMaxRetries().intValue());
    }

    @Test
    void nonPlcEquipmentDetailHasNullPlcSettings() {
        final EqpManagementSnapshot snapshot = new EqpManagementSnapshot(
                uiEqp("SOCKET_001", UiProtocolType.SOCKET),
                null, null, null, null, null, null, null, null, null, null, null, null
        );

        final EqpManageDetailResponse response = EqpControllerResponseSupport.toManageDetailResponse(snapshot);

        assertNull(response.plcSettings());
    }

    private UiEqp uiEqp(final String eqpId, final UiProtocolType commInterface) {
        return new UiEqp(
                1L,
                eqpId,
                1L,
                commInterface,
                "ACTIVE",
                false,
                1,
                "10.10.10.1",
                502,
                1001L,
                null,
                null,
                null,
                true,
                NOW,
                NOW,
                "SYSTEM",
                "SYSTEM"
        );
    }
}

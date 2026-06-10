package com.nori.tc.ui.core.service;

import com.nori.tc.ui.core.eqp.EqpManagementCommand;
import com.nori.tc.ui.core.exception.UiBadRequestException;
import com.nori.tc.ui.core.port.db.ModelCrudPort;
import com.nori.tc.ui.domain.common.model.UiProtocolType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

/**
 * PLC plcSettings 저장 경계 검증(SPEC Phase 5 AC L1331/L1341/L1342).
 *
 * <p>PoC = MODBUS_TCP + TCP 만 허용. plc_protocol≠MODBUS_TCP / 잘못된 transport 조합은 저장 전 400 거부한다
 * (gateway 기동 거부와 별개의 ui-backend 편집 경계 검증).</p>
 */
class EqpManagementCommandValidatorPlcTest {

    private final EqpManagementCommandValidator validator =
            new EqpManagementCommandValidator(mock(ModelCrudPort.class));

    private EqpManagementCommand.Create createWithPlc(final EqpManagementCommand.PlcSettings plc) {
        return new EqpManagementCommand.Create(
                "tester", "EQP-PLC", 1L, UiProtocolType.PLC, "ACTIVE", false,
                0, "10.30.0.1", 502, 301L, null, null, null, null, null, null, plc
        );
    }

    @Test
    @DisplayName("PLC plcSettings 누락 → 400")
    void rejectsMissingPlcSettings() {
        assertThrows(UiBadRequestException.class,
                () -> validator.validateCreateCommand(createWithPlc(null)));
    }

    @Test
    @DisplayName("PoC 미지원 plc_protocol(S7) → 400")
    void rejectsNonModbusTcpProtocol() {
        assertThrows(UiBadRequestException.class, () -> validator.validateCreateCommand(
                createWithPlc(new EqpManagementCommand.PlcSettings("S7", "TCP", 1, 3000, 3))));
    }

    @Test
    @DisplayName("잘못된 protocol↔transport 조합(MODBUS_TCP+SERIAL) → 400")
    void rejectsInvalidTransportCombo() {
        assertThrows(UiBadRequestException.class, () -> validator.validateCreateCommand(
                createWithPlc(new EqpManagementCommand.PlcSettings("MODBUS_TCP", "SERIAL", 1, 3000, 3))));
    }

    @Test
    @DisplayName("MODBUS_TCP + TCP 정상 조합은 통과")
    void acceptsModbusTcpWithTcp() {
        assertDoesNotThrow(() -> validator.validateCreateCommand(
                createWithPlc(new EqpManagementCommand.PlcSettings("MODBUS_TCP", "TCP", 1, 3000, 3))));
    }
}

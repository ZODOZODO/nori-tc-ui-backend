package com.nori.tc.ui.adapters.web.simulator;

import com.nori.tc.ui.core.exception.UiBadRequestException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * AC-1 [R-2 매핑] — testType E2E/SOCKET_ONLY/MES_ONLY → mode ALL/EQUIPMENT_ONLY/UPSTREAM_ONLY 정확 매핑(3건 AND),
 * 미지원 값 거부.
 */
class SimulatorModeMapperTest {

    private final SimulatorModeMapper mapper = new SimulatorModeMapper();

    @Test
    void mapsAllThreeTestTypesToModes() {
        assertEquals("ALL", mapper.toMode("E2E"), "E2E → ALL");
        assertEquals("EQUIPMENT_ONLY", mapper.toMode("SOCKET_ONLY"), "SOCKET_ONLY → EQUIPMENT_ONLY");
        assertEquals("UPSTREAM_ONLY", mapper.toMode("MES_ONLY"), "MES_ONLY → UPSTREAM_ONLY");
    }

    @Test
    void rejectsUnsupportedTestType() {
        assertThrows(UiBadRequestException.class, () -> mapper.toMode("UNKNOWN"), "미지원 testType 거부");
        assertThrows(UiBadRequestException.class, () -> mapper.toMode(null), "null testType 거부");
        // anti-vacuous: 레거시 토큰(ALL 등 mode 토큰)을 testType 으로 넘기면 거부(testType 도메인 아님).
        assertThrows(UiBadRequestException.class, () -> mapper.toMode("ALL"), "mode 토큰은 testType 아님 → 거부");
    }
}

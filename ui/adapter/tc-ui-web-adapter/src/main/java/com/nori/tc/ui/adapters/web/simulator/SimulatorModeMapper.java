package com.nori.tc.ui.adapters.web.simulator;

import com.nori.tc.ui.core.exception.UiBadRequestException;

/**
 * ui-front testType → orchestrator mode 매핑(R-2) — <b>단일 출처</b>(분기 산재 금지). 레거시 SimulatorMode 매핑과 일치:
 * {@code E2E→ALL}, {@code SOCKET_ONLY→EQUIPMENT_ONLY}, {@code MES_ONLY→UPSTREAM_ONLY}. 미지원 값 거부(400).
 */
public final class SimulatorModeMapper {

    public String toMode(final String testType) {
        if (testType == null) {
            throw new UiBadRequestException("testType은 필수입니다.");
        }
        return switch (testType) {
            case "E2E" -> "ALL";
            case "SOCKET_ONLY" -> "EQUIPMENT_ONLY";
            case "MES_ONLY" -> "UPSTREAM_ONLY";
            default -> throw new UiBadRequestException("지원하지 않는 testType입니다: " + testType);
        };
    }
}

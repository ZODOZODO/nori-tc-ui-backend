package com.nori.tc.ui.core.model;

import tools.jackson.databind.JsonNode;

/**
 * UI Backend -> Gateway/Business 발행용 기술 중립 명령 메시지입니다.
 *
 * <p>핵심 의도:</p>
 * <ul>
 *   <li>core 계층에서 Kafka 계약 타입 의존 제거</li>
 *   <li>메시징 어댑터(Kafka)가 이 도메인 메시지를 실제 Kafka 계약으로 변환</li>
 * </ul>
 *
 * @param eventType 발행 이벤트 타입
 * @param traceId 요청/응답 상관관계 추적 ID
 * @param source 발행 주체(예: TC-UI-APP)
 * @param eqpId 설비 ID
 * @param interfaceType 인터페이스 타입(HSMS/SOCKET 등)
 * @param uiMessage eventType별 업무 payload(JSON object, 선택)
 * @param equipmentProfile 설비 프로파일 스냅샷(선택, EQP_CREATE/EQP_UPDATE에서 사용)
 * @param coordination coordination metadata v2(§10.1). null 이면 v1 명령으로 발행됩니다
 */
public record UiCommandMessage(
        UiCommandEventType eventType,
        String traceId,
        String source,
        String eqpId,
        String interfaceType,
        JsonNode uiMessage,
        UiEquipmentProfileSnapshot equipmentProfile,
        UiCommandCoordination coordination
) {

    /**
     * coordination metadata 없이 v1 명령을 만듭니다.
     *
     * @param eventType 발행 이벤트 타입
     * @param traceId 요청/응답 상관관계 추적 ID
     * @param source 발행 주체
     * @param eqpId 설비 ID
     * @param interfaceType 인터페이스 타입
     * @param uiMessage 업무 payload
     * @param equipmentProfile 설비 프로파일 스냅샷
     */
    public UiCommandMessage(
            final UiCommandEventType eventType,
            final String traceId,
            final String source,
            final String eqpId,
            final String interfaceType,
            final JsonNode uiMessage,
            final UiEquipmentProfileSnapshot equipmentProfile
    ) {
        this(eventType, traceId, source, eqpId, interfaceType, uiMessage, equipmentProfile, null);
    }

    /**
     * coordination metadata 를 덧붙인 사본을 만듭니다.
     *
     * @param newCoordination 실을 coordination metadata
     * @return 새 메시지
     */
    public UiCommandMessage withCoordination(final UiCommandCoordination newCoordination) {
        return new UiCommandMessage(
                eventType, traceId, source, eqpId, interfaceType, uiMessage, equipmentProfile, newCoordination);
    }

    /**
     * 필수값을 검증합니다.
     */
    public UiCommandMessage {
        requireText("traceId", traceId);
        requireText("source", source);
        requireText("eqpId", eqpId);
        requireText("interfaceType", interfaceType);
        if (eventType == null) {
            throw new IllegalArgumentException("eventType is required");
        }
    }

    private static void requireText(final String fieldName, final String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
    }
}

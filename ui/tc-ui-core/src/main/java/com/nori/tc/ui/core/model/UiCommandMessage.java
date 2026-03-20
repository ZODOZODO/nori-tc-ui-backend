package com.nori.tc.ui.core.model;

import tools.jackson.databind.JsonNode;
import com.nori.tc.messaging.kafka.contract.GatewayEquipmentProfileSnapshot;

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
 */
public record UiCommandMessage(
        UiCommandEventType eventType,
        String traceId,
        String source,
        String eqpId,
        String interfaceType,
        JsonNode uiMessage,
        GatewayEquipmentProfileSnapshot equipmentProfile
) {

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

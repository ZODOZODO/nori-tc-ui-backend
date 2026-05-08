package com.nori.tc.ui.core.model;

/**
 * UI Backend가 Gateway/Business에 발행하는 명령 이벤트 타입입니다.
 *
 * <p>Kafka 계약 타입에 직접 의존하지 않도록 core 계층의 기술 중립 enum으로 정의합니다.</p>
 */
public enum UiCommandEventType {
    EQP_CREATE,
    EQP_UPDATE,
    EQP_MODEL_UPDATE,
    EQP_DELETE,
    EQP_START,
    EQP_END,
    EQP_SEND_MESSAGE,
    EQP_RECONNECT,
    EQP_PARAMETER_UPDATE,
    EQP_GATEWAY_JARFILE_UPDATE,
    EQP_BUSINESS_JARFILE_UPDATE,
    EQP_REGISTER_DEPLOY,
    /**
     * route_partition 이동 시 옛 owner pod에 release를 통보하기 위한 이벤트입니다.
     * UI가 새 partition으로 EQP_UPDATE를 발행하기 직전에 옛 partition으로 본 이벤트를 발행합니다.
     */
    EQP_PARTITION_RELEASE;

    /**
     * 문자열 eventType을 enum으로 변환합니다.
     *
     * @param text 원본 eventType 문자열
     * @return 정규화된 enum 값
     */
    public static UiCommandEventType fromText(final String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("eventType is required");
        }
        try {
            return UiCommandEventType.valueOf(text.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unsupported eventType: " + text, ex);
        }
    }
}

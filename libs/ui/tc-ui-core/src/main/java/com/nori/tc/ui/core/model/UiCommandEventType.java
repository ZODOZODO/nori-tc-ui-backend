package com.nori.tc.ui.core.model;

/**
 * UI Backend가 Gateway/Business에 발행하는 명령 이벤트 타입입니다.
 *
 * <p>Kafka 계약 타입에 직접 의존하지 않도록 core 계층의 기술 중립 enum으로 정의합니다.</p>
 */
public enum UiCommandEventType {
    EQP_CREATE,
    EQP_UPDATE,
    EQP_DELETE,
    EQP_START,
    EQP_END,
    EQP_SEND_MESSAGE,
    EQP_UPDATE_JARFILE;

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

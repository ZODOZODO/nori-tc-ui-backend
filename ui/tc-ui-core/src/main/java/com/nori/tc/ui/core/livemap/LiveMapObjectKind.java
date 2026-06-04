package com.nori.tc.ui.core.livemap;

import java.util.Locale;

/**
 * live-map 합성 객체의 종류입니다.
 *
 * <p>EQUIPMENT 배치는 실설비(eqp_key)에 바인딩되어야 하고, STRUCTURE 배치는 eqp_key 를 가질 수 없습니다.
 * 이 상호 배타는 object_kind 가 placement 와 다른 테이블이라 DB CHECK 로 강제할 수 없어
 * {@code LiveMapLayoutService} 의 서버 검증으로 강제합니다.</p>
 */
public enum LiveMapObjectKind {
    STRUCTURE,
    EQUIPMENT;

    /**
     * 외부 입력(JSON) 문자열을 enum 으로 변환합니다. 미지원 값은 예외입니다.
     */
    public static LiveMapObjectKind fromText(final String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("objectKind는 필수입니다.");
        }
        return switch (raw.trim().toUpperCase(Locale.ROOT)) {
            case "STRUCTURE" -> STRUCTURE;
            case "EQUIPMENT" -> EQUIPMENT;
            default -> throw new IllegalArgumentException("알 수 없는 objectKind입니다: " + raw);
        };
    }
}

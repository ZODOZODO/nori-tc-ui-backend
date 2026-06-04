package com.nori.tc.ui.core.service;

import com.nori.tc.ui.core.exception.UiBadRequestException;
import tools.jackson.databind.JsonNode;

/**
 * live-map JSONB 페이로드의 최소 스키마 검증 유틸입니다.
 *
 * <p>SPEC JSONB 원칙: 서버는 konva 직렬화 트리를 강타입으로 풀지 않고 최상위 스키마 버전 {@code v} 만
 * 검증한다(나머지 키는 자유 확장 — 새 도형/속성 추가 시 서버 변경 0). {@code v} 누락/비정수면 400.</p>
 */
final class LiveMapSchemaSupport {

    private LiveMapSchemaSupport() {
    }

    /**
     * JSONB 페이로드의 최상위 스키마 버전 {@code v}(정수)를 강제합니다.
     *
     * @param json  검증 대상(null/누락이면 400)
     * @param field 오류 메시지에 쓸 필드명
     */
    static void requireSchemaVersion(final JsonNode json, final String field) {
        if (json == null || json.isNull() || json.isMissingNode()) {
            throw new UiBadRequestException(field + "는 필수입니다.");
        }
        final JsonNode version = json.get("v");
        if (version == null || !version.isIntegralNumber()) {
            throw new UiBadRequestException(field + "의 스키마 버전(v)이 누락되었거나 정수가 아닙니다.");
        }
    }
}

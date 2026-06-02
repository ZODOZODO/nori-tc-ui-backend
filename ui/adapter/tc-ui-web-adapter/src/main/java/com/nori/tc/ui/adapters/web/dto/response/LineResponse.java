package com.nori.tc.ui.adapters.web.dto.response;

import com.nori.tc.ui.domain.line.UiLine;

/**
 * 라인 조회 응답 DTO입니다.
 *
 * @param lineKey 라인 PK
 * @param lineCode 라인 코드(드롭다운 value)
 * @param lineName 라인 이름(드롭다운 label)
 */
public record LineResponse(
        long lineKey,
        String lineCode,
        String lineName
) {

    /**
     * UI 도메인 라인을 응답 DTO로 변환합니다.
     *
     * @param line UI 라인 도메인
     * @return 응답 DTO
     */
    public static LineResponse from(final UiLine line) {
        return new LineResponse(line.lineKey(), line.lineCode(), line.lineName());
    }
}

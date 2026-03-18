package com.nori.tc.ui.adapters.web.dto.response;

import java.util.List;

/**
 * model parent commit diff의 상세 노드별 섹션 응답 DTO입니다.
 *
 * @param detailNode 상세 노드 식별자
 * @param columns 렌더링용 컬럼명 목록
 * @param added 추가 항목 목록
 * @param changed 변경 항목 목록
 * @param deleted 삭제 항목 목록
 */
public record ModelDiffSectionResponse(
        String detailNode,
        List<String> columns,
        List<ModelDiffItemResponse> added,
        List<ModelDiffItemResponse> changed,
        List<ModelDiffItemResponse> deleted
) {
}

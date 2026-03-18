package com.nori.tc.ui.adapters.web.dto.response;

import java.util.List;

/**
 * Model 상세 노드 데이터 응답 DTO입니다.
 *
 * <p>MDF 노드는 mdfContents만 사용하고, 나머지 노드는 rows를 사용합니다.</p>
 *
 * @param columns 일반 상세 테이블 컬럼 목록
 * @param rows 일반 상세 테이블 row 목록
 * @param mdfContents MDF(XML) 목록
 */
public record ModelDetailDataResponse(
        List<String> columns,
        List<ModelDetailRowResponse> rows,
        List<ModelMdfContentResponse> mdfContents
) {
}

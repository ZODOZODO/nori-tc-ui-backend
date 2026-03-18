package com.nori.tc.ui.adapters.web.dto.response;

import java.util.List;

/**
 * branch 일괄 삭제 결과 응답 DTO입니다.
 *
 * @param deletedCount 삭제된 branch 개수
 * @param deletedModelKeys 삭제된 branch model_key 목록
 * @param deletedModelNames 삭제된 branch model_name 목록
 */
public record ModelDeleteBatchResponse(
        int deletedCount,
        List<Long> deletedModelKeys,
        List<String> deletedModelNames
) {
}

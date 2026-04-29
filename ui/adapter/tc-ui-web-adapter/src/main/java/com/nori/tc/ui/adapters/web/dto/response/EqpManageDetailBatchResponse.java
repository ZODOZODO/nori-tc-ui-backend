package com.nori.tc.ui.adapters.web.dto.response;

import java.util.List;

/**
 * EQP 관리 상세 일괄 조회 응답 DTO입니다.
 *
 * <p>그룹 화면(Gateway / gateway_app) 처럼 다건을 동시에 보여줘야 하는 화면에서
 * 클라이언트의 N-fan-out HTTP 호출을 제거하기 위해 사용합니다.</p>
 *
 * <p>요청한 eqpId 중 DB에 존재하지 않는 항목은 {@code missingEqpIds}에 담겨 반환되며,
 * 존재하는 설비의 상세는 {@code items}에 입력 순서대로 포함됩니다.</p>
 */
public record EqpManageDetailBatchResponse(
        List<EqpManageDetailResponse> items,
        List<String> missingEqpIds
) {
}

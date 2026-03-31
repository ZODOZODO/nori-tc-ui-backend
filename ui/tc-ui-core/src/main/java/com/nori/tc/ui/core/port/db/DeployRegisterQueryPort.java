package com.nori.tc.ui.core.port.db;

import com.nori.tc.ui.core.deploy.DeployRegisterTargetView;
import com.nori.tc.ui.core.model.PagedResponse;
import com.nori.tc.ui.core.model.UiPageRequest;

/**
 * Deploy Register 대상 설비 조회 포트입니다.
 */
public interface DeployRegisterQueryPort {

    /**
     * Register 화면 대상 설비 목록을 조회합니다.
     *
     * @param modelName 현재 연결 model name 필터
     * @param modelVersionKey 현재 연결 model version key 필터
     * @param eqpId eqp id 부분 검색 필터
     * @param pageRequest 페이지 요청
     * @return 대상 설비 페이지
     */
    PagedResponse<DeployRegisterTargetView> findRegisterTargets(
            String modelName,
            Long modelVersionKey,
            String eqpId,
            UiPageRequest pageRequest
    );
}

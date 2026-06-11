package com.nori.tc.ui.core.service;

import com.nori.tc.ui.core.deploy.DeployRegisterTargetView;
import com.nori.tc.ui.core.model.PagedResponse;
import com.nori.tc.ui.core.model.UiPageRequest;
import com.nori.tc.ui.core.port.db.DeployRegisterQueryPort;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * Deploy Register 대상 설비 조회를 총괄하는 서비스입니다.
 */
@Service
public class DeployRegisterQueryService {

    private final DeployRegisterQueryPort deployRegisterQueryPort;

    public DeployRegisterQueryService(final DeployRegisterQueryPort deployRegisterQueryPort) {
        this.deployRegisterQueryPort = Objects.requireNonNull(
                deployRegisterQueryPort,
                "deployRegisterQueryPort is null"
        );
    }

    /**
     * Register 화면 대상 설비 페이지를 조회합니다.
     *
     * @param modelName model name 필터
     * @param modelVersionKey model version key 필터
     * @param eqpId eqp id 필터
     * @param lineKey 설비 소속 라인 필터 (null 이면 전체 라인)
     * @param pageRequest 페이지 요청
     * @return 조회 결과 페이지
     */
    public PagedResponse<DeployRegisterTargetView> findRegisterTargets(
            final String modelName,
            final Long modelVersionKey,
            final String eqpId,
            final Long lineKey,
            final UiPageRequest pageRequest
    ) {
        final UiPageRequest effectivePageRequest = pageRequest == null ? UiPageRequest.defaultPage() : pageRequest;

        return deployRegisterQueryPort.findRegisterTargets(
                normalizeText(modelName),
                modelVersionKey,
                normalizeText(eqpId),
                lineKey,
                effectivePageRequest
        );
    }

    private static String normalizeText(final String value) {
        if (value == null) {
            return null;
        }

        final String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}

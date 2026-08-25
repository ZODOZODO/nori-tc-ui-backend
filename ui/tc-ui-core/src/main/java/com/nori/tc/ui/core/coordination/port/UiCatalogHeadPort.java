package com.nori.tc.ui.core.coordination.port;

import com.nori.tc.ui.core.coordination.model.UiCatalogHeadView;

/**
 * catalog head commit/조회 포트입니다(§13.9/§13.10).
 */
public interface UiCatalogHeadPort {

    /** deploy scenario catalog 타입 식별자입니다. */
    String CATALOG_TYPE_SCENARIO = "deploy-scenario";

    /** API permission catalog 타입 식별자입니다. */
    String CATALOG_TYPE_PERMISSION = "api-permission";

    /**
     * validation 을 통과한 candidate 를 head 로 commit 합니다.
     *
     * @param catalogType catalog 타입
     * @param newRevision 새 head revision(monotonic)
     * @param digest canonical digest
     * @param expectedRevision CAS 기준 현재 revision(검사 생략이면 null)
     * @param nowEpochMs 현재 시각
     * @return 결과 코드
     */
    String commitHead(String catalogType, long newRevision, String digest, Long expectedRevision, long nowEpochMs);

    /**
     * catalog head 를 조회합니다.
     *
     * @param catalogType catalog 타입
     * @return head 스냅샷
     */
    UiCatalogHeadView readHead(String catalogType);
}

package com.nori.tc.ui.core.port.db;

import com.nori.tc.ui.core.livemap.LiveMapLayoutView;
import com.nori.tc.ui.core.livemap.UpsertLiveMapPlacement;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Optional;

/**
 * 라인 캔버스(tc_live_map_layout) + 배치(tc_live_map_placement) 저장/조회 포트입니다.
 *
 * <p>layout 은 라인당 1행(line_key UNIQUE)이고 placement 는 그 자식(CASCADE)이라,
 * 별도 placement 포트 없이 layout 포트가 일괄 처리합니다(GET 시 join, PUT 시 delete-then-insert).</p>
 */
public interface LiveMapLayoutStorePort {

    /**
     * 라인의 캔버스 + 배치 목록을 조회합니다.
     *
     * <p>반환 view 의 {@code objects} 는 비어 있습니다 — 참조 객체 동봉은 서비스가 채웁니다.</p>
     */
    Optional<LiveMapLayoutView> findByLineKey(long lineKey);

    /**
     * 캔버스 메타 upsert(line_key 기준) + 배치 일괄 replace 를 단일 트랜잭션으로 저장합니다.
     *
     * @return 저장된 layout_key
     */
    long saveLayout(long lineKey, JsonNode canvasJson, List<UpsertLiveMapPlacement> placements);

    /**
     * eqp_key 의 설비가 소속된 line_key 를 조회합니다(배치 eqp_key 의 존재·동일라인 검증용).
     *
     * <p>tc_eqp 는 nori-tc-common 공유 테이블이나 PK(eqp_key) 단건 조회 포트가 없어,
     * common 변경 없이 ui-backend JDBC 어댑터에서 직접 조회합니다(D1 — common 무변경).</p>
     *
     * @return 존재하면 line_key, 없으면 empty
     */
    Optional<Long> findEqpLineKey(long eqpKey);
}

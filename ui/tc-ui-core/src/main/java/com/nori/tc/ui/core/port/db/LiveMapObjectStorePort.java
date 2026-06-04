package com.nori.tc.ui.core.port.db;

import com.nori.tc.ui.core.livemap.LiveMapObject;
import com.nori.tc.ui.core.livemap.LiveMapObjectKind;
import tools.jackson.databind.JsonNode;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 재사용 합성 객체(tc_live_map_object) CRUD 포트입니다.
 *
 * <p>JSONB(shape_json) 보유 테이블이라 nori-tc-common 공유 ORM(트랙 1, scalar 전용)이 아닌
 * ui-backend 소유 JDBC 어댑터(트랙 2, tc_deploy* 답습)로 구현합니다.</p>
 */
public interface LiveMapObjectStorePort {

    /**
     * 합성 객체를 신규 저장하고 발급된 object_key 를 반환합니다.
     */
    long save(String objectName, LiveMapObjectKind objectKind, JsonNode shapeJson);

    /**
     * object_key 의 객체를 수정합니다.
     *
     * @return 갱신된 행이 있으면 true(없으면 false)
     */
    boolean update(long objectKey, String objectName, LiveMapObjectKind objectKind, JsonNode shapeJson);

    /**
     * object_key 의 객체를 삭제합니다(참조 placement 는 FK CASCADE 로 함께 삭제).
     *
     * @return 삭제된 행이 있으면 true
     */
    boolean delete(long objectKey);

    /**
     * 활성 객체 전체를 조회합니다(팔레트/라이브러리 목록).
     */
    List<LiveMapObject> findAllActive();

    /**
     * object_key 단건 조회(배치 검증 시 object_kind 확인용).
     */
    Optional<LiveMapObject> findByKey(long objectKey);

    /**
     * object_key 집합으로 다건 조회(레이아웃 GET 의 참조 객체 동봉용, 비활성 포함).
     *
     * <p>입력이 비어 있으면 DB 를 조회하지 않고 빈 목록을 반환합니다.</p>
     */
    List<LiveMapObject> findByKeyIn(Collection<Long> objectKeys);
}

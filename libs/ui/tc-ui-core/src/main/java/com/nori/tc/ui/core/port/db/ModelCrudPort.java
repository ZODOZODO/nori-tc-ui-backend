package com.nori.tc.ui.core.port.db;

import com.nori.tc.db.core.common.PageRequest;
import com.nori.tc.db.core.model.upsert.UpsertTcModel;
import com.nori.tc.db.domain.model.TcModel;
import com.nori.tc.ui.core.model.PagedResponse;

import java.util.Optional;

/**
 * 모델 정보 관리(Model Info) CRUD 포트입니다.
 *
 * <p>UI 관리 페이지에서 사용하는 모델 조회/등록/수정/삭제 계약을 정의합니다.</p>
 * <ul>
 *   <li>{@code GET /api/model}</li>
 *   <li>{@code GET /api/model/{modelVersionKey}}</li>
 *   <li>{@code POST /api/model}</li>
 *   <li>{@code PUT /api/model/{modelVersionKey}}</li>
 *   <li>{@code DELETE /api/model/{modelVersionKey}}</li>
 * </ul>
 *
 * <p>설계 의도:</p>
 * <p>Core 계층은 DB 기술(JPA/MyBatis)을 몰라야 하므로,
 * 이 포트만 기준으로 상위 UseCase를 구성합니다.</p>
 */
public interface ModelCrudPort {

    /**
     * 모델 정보를 업서트합니다.
     *
     * <p>{@code command.modelKey()}는 DB 스토어 계약과 동일하게
     * model_version_key 의미로 해석됩니다.</p>
     *
     * @param command 저장/수정 입력 명령
     * @return 저장 후 모델 스냅샷
     */
    TcModel upsert(UpsertTcModel command);

    /**
     * 모델 버전 키 기준 단건을 조회합니다.
     *
     * @param modelVersionKey 모델 버전 키
     * @return 조회 결과, 미존재 시 빈 Optional
     */
    Optional<TcModel> findByModelVersionKey(long modelVersionKey);

    /**
     * 모델 목록을 페이지 단위로 조회합니다.
     *
     * @param pageRequest offset/limit 기반 페이지 요청
     * @return 페이지 응답(목록 + 전체 건수)
     */
    PagedResponse<TcModel> findAll(PageRequest pageRequest);

    /**
     * 모델 버전 키 기준으로 삭제합니다.
     *
     * @param modelVersionKey 삭제 대상 모델 버전 키
     */
    void deleteByModelVersionKey(long modelVersionKey);
}

package com.nori.tc.ui.core.port.db;

import com.nori.tc.db.core.common.PageRequest;
import com.nori.tc.db.domain.eqp.TcEqp;
import com.nori.tc.db.domain.eqp.TcEqpParam;
import com.nori.tc.ui.core.model.PagedResponse;

import java.util.List;
import java.util.Optional;

/**
 * 설비(EQP) 조회 전용 DB 접근 포트입니다.
 *
 * <p>본 포트는 UI 관리 페이지의 읽기 API와 직접 연결됩니다.</p>
 * <ul>
 *   <li>{@code GET /api/eqp}      : 설비 목록 조회</li>
 *   <li>{@code GET /api/eqp/{eqpId}} : 설비 상세 조회</li>
 * </ul>
 *
 * <p>중요 제약:</p>
 * <ul>
 *   <li>명령성 API(POST/PUT/DELETE/start/end)는 이 포트를 사용하지 않습니다.</li>
 *   <li>조회 기준의 SSOT는 {@code tc_eqp} 테이블이며, 구현체는 DB Store 계층을 통해 접근해야 합니다.</li>
 * </ul>
 */
public interface EqpQueryPort {

    /**
     * 설비 상세 화면의 상태 표시를 위한 런타임 상태 스냅샷입니다.
     *
     * @param controlState tc_eqp_state.control_state 문자열
     * @param eqpState tc_eqp_state.eqp_state 문자열
     * @param connectionState tc_eqp_state_hist(CONN)의 최신 to_state 문자열
     */
    record EqpRuntimeStateView(
            String controlState,
            String eqpState,
            String connectionState
    ) {
    }

    /**
     * 설비 체크아웃 상태 스냅샷입니다.
     *
     * <p>EDIT 버전 파라미터 존재 여부와 체크아웃한 사용자 ID를 담습니다.</p>
     *
     * @param checkedOut EDIT 버전 파라미터가 존재하면 true (= 체크아웃 중)
     * @param checkedOutBy 체크아웃한 사용자 ID (체크아웃 중이 아니면 null)
     */
    record EqpCheckoutStateView(
            boolean checkedOut,
            String checkedOutBy
    ) {
    }

    /**
     * 설비 목록을 페이지 단위로 조회합니다.
     *
     * @param pageRequest offset/limit 기반 페이지 요청
     * @return 페이지 응답(목록 + 전체 건수)
     */
    PagedResponse<TcEqp> findAll(PageRequest pageRequest);

    /**
     * 설비 식별자(eqpId)로 단건을 조회합니다.
     *
     * @param eqpId 설비 비즈니스 키
     * @return 조회 결과, 미존재 시 빈 Optional
     */
    Optional<TcEqp> findByEqpId(String eqpId);

    /**
     * 설비 식별자(eqpId)에 매핑된 파라미터 버전 목록을 조회합니다.
     *
     * <p>데이터 소스는 {@code tc_eqp_param.param_version}이며,
     * 중복 없는 버전 문자열 목록을 반환합니다.
     * EDIT 버전은 내부 잠금용이므로 결과에서 제외됩니다.</p>
     *
     * @param eqpId 설비 비즈니스 키
     * @return 버전 문자열 목록(해당 설비가 없거나 버전 데이터가 없으면 빈 목록)
     */
    List<String> findParamVersionsByEqpId(String eqpId);

    /**
     * 설비 식별자(eqpId)와 버전으로 파라미터 목록을 조회합니다.
     *
     * @param eqpId 설비 비즈니스 키
     * @param version 파라미터 버전 (예: "v1.0", "EDIT")
     * @return 파라미터 목록(설비 미존재 또는 해당 버전 없으면 빈 목록)
     */
    List<TcEqpParam> findParamsByEqpIdAndVersion(String eqpId, String version);

    /**
     * 설비 식별자(eqpId)에 매핑된 체크아웃 상태를 조회합니다.
     *
     * <p>EDIT 버전 파라미터 존재 여부와 created_by(체크아웃한 사용자)를 반환합니다.</p>
     *
     * @param eqpId 설비 비즈니스 키
     * @return 설비 존재 시 체크아웃 상태(체크아웃 중이 아닌 경우에도 present), 설비 미존재 시 empty
     */
    Optional<EqpCheckoutStateView> findCheckoutStateByEqpId(String eqpId);

    /**
     * 설비 식별자(eqpId)에 매핑된 런타임 상태 정보를 조회합니다.
     *
     * <p>조회 소스:</p>
     * <ul>
     *   <li>{@code tc_eqp_state} (control_state, eqp_state)</li>
     *   <li>{@code tc_eqp_state_hist}의 최신 CONN 이력(to_state)</li>
     * </ul>
     *
     * @param eqpId 설비 비즈니스 키
     * @return 설비 존재 시 상태 스냅샷(상태 데이터가 없어도 Optional은 present), 설비 미존재 시 empty
     */
    Optional<EqpRuntimeStateView> findRuntimeStateByEqpId(String eqpId);
}

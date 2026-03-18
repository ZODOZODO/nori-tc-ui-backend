package com.nori.tc.ui.core.port.db;

import java.util.List;

/**
 * 설비(EQP) 파라미터 체크아웃/저장/체크인 커맨드 포트입니다.
 *
 * <p>역할:</p>
 * <ul>
 *   <li>체크아웃: 선택 버전의 파라미터를 EDIT 버전으로 복사하여 DB 잠금 생성</li>
 *   <li>EDIT 파라미터 저장: EDIT 버전 전체를 요청 목록 기준으로 재구성</li>
 *   <li>체크아웃 되돌리기: EDIT 버전 전체 삭제</li>
 *   <li>체크인: EDIT 버전을 새 버전으로 저장 후 EDIT 삭제</li>
 * </ul>
 *
 * <p>체크아웃 잠금 메커니즘: {@code tc_eqp_param.param_version='EDIT'} 존재 여부 = 체크아웃 상태</p>
 */
public interface EqpParamCommandPort {

    /**
     * 체크아웃 결과로 반환되는 파라미터 뷰입니다.
     *
     * @param paramName 파라미터 이름
     * @param paramValue 파라미터 값
     * @param description 파라미터 설명
     * @param createdBy 체크아웃한 사용자 ID
     */
    record EqpParamView(
            String paramName,
            String paramValue,
            String description,
            String createdBy
    ) {
    }

    /**
     * EDIT 파라미터 수정 요청 단건입니다.
     *
     * @param paramName 파라미터 이름
     * @param paramValue 새 파라미터 값
     * @param description 새 파라미터 설명
     */
    record EqpParamEdit(
            String paramName,
            String paramValue,
            String description
    ) {
    }

    /**
     * 설비 파라미터를 체크아웃합니다.
     *
     * <p>처리 순서:</p>
     * <ol>
     *   <li>eqpId로 eqpKey 확보</li>
     *   <li>EDIT 버전 존재 시 {@link com.nori.tc.ui.core.exception.EqpAlreadyCheckedOutException} 발생 (409)</li>
     *   <li>sourceVersion 파라미터를 EDIT 버전으로 복사 (created_by = currentUser)</li>
     * </ol>
     *
     * @param eqpId 설비 비즈니스 ID
     * @param sourceVersion 복사 기준 버전 (예: "v1.0"). null 또는 빈 문자열이면 빈 EDIT 버전 생성
     * @param currentUser 체크아웃을 수행하는 사용자 ID (Spring Security에서 추출)
     * @return 생성된 EDIT 파라미터 목록
     * @throws com.nori.tc.ui.core.exception.EqpAlreadyCheckedOutException EDIT 버전이 이미 존재하는 경우
     */
    List<EqpParamView> checkout(String eqpId, String sourceVersion, String currentUser);

    /**
     * EDIT 버전의 파라미터를 수정합니다.
     *
     * <p>현재 EDIT 버전 전체를 삭제한 뒤, 요청 목록 기준으로 다시 저장합니다.
     * 이 방식으로 paramName 변경과 행 삭제/추가를 함께 반영합니다.</p>
     *
     * @param eqpId 설비 비즈니스 ID
     * @param params 수정할 파라미터 목록
     * @param currentUser 저장을 수행하는 사용자 ID (로그용)
     */
    void saveEditParams(String eqpId, List<EqpParamEdit> params, String currentUser);

    /**
     * 체크아웃을 되돌립니다.
     *
     * <p>현재 설비의 EDIT 버전 전체를 삭제하여 편집 잠금을 해제합니다.</p>
     *
     * @param eqpId 설비 비즈니스 ID
     * @param currentUser 취소를 수행하는 사용자 ID (로그용)
     */
    void undoCheckout(String eqpId, String currentUser);

    /**
     * 체크인을 수행합니다.
     *
     * <p>처리 순서:</p>
     * <ol>
     *   <li>EDIT 파라미터 목록 조회</li>
     *   <li>시스템이 현재 날짜 기준 다음 버전명을 생성</li>
     *   <li>버전 설명을 tc_eqp_param_version에 저장</li>
     *   <li>각 EDIT 파라미터를 생성된 버전으로 INSERT (description = 파라미터 설명 유지)</li>
     *   <li>EDIT 버전 전체 DELETE</li>
     * </ol>
     *
     * @param eqpId 설비 비즈니스 ID
     * @param versionDescription 버전 설명 (null 허용)
     * @param currentUser 체크인을 수행하는 사용자 ID
     */
    void checkin(String eqpId, String versionDescription, String currentUser);
}

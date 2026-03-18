package com.nori.tc.ui.core.port.db;

import com.nori.tc.db.domain.model.TcModelMdf;

import java.util.List;

/**
 * Model 상세 노드 저장 포트입니다.
 *
 * <p>현재는 일반 상세 테이블 row 저장과 MDF 업로드/저장 정책을 담당합니다.</p>
 */
public interface ModelDetailCommandPort {

    /**
     * 일반 상세 테이블 row 목록을 현재 model version에 반영합니다.
     *
     * <p>detailNode별 기존 row는 모두 교체되며, 저장 후 조회 시점의 순서는 요청 순서를 따릅니다.</p>
     *
     * @param modelVersionKey 대상 모델 버전 키
     * @param detailNode 상세 노드 식별자
     * @param rows 컬럼 순서 기반 row 목록
     */
    void saveDetailRows(long modelVersionKey, String detailNode, List<DetailRowCommand> rows);

    /**
     * MDF XML을 저장합니다.
     *
     * <p>업로드 본문은 UTF-8 XML이어야 하며, XML 형식 정합성 검증을 통과해야 합니다.</p>
     *
     * @param modelVersionKey 대상 모델 버전 키
     * @param mdfName 저장할 MDF 이름
     * @param mdfFile UTF-8 XML 바이트
     * @return 저장된 MDF
     */
    TcModelMdf saveMdf(long modelVersionKey, String mdfName, byte[] mdfFile);

    /**
     * 일반 상세 테이블 row 저장 명령입니다.
     *
     * @param id 기존 row 식별자. 신규 row는 null/blank 허용
     * @param values 컬럼 순서 기반 셀 값 목록
     */
    record DetailRowCommand(
            String id,
            List<String> values
    ) {
    }
}

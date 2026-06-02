package com.nori.tc.ui.core.port.db;

import java.util.List;

import com.nori.tc.ui.domain.line.UiLine;

/**
 * 라인(tc_line) 조회 포트입니다.
 *
 * <p>Phase 1 범위에서는 활성 라인 목록 조회만 제공합니다(상단바 드롭다운 / eqp 폼 옵션).</p>
 */
public interface LineQueryPort {

    /**
     * 활성 라인 목록을 line_code 오름차순으로 조회합니다.
     *
     * @return 활성 라인 목록
     */
    List<UiLine> findActiveLines();
}

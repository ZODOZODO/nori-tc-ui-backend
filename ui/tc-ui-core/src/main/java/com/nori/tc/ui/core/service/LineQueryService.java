package com.nori.tc.ui.core.service;

import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;

import com.nori.tc.ui.core.port.db.LineQueryPort;
import com.nori.tc.ui.domain.line.UiLine;

/**
 * 라인 조회 전용 서비스입니다.
 *
 * <p>상단바 라인 드롭다운 / eqp 폼 라인 옵션에 사용할 활성 라인 목록을 제공합니다.</p>
 */
@Service
public class LineQueryService {

    private final LineQueryPort lineQueryPort;

    public LineQueryService(final LineQueryPort lineQueryPort) {
        this.lineQueryPort = Objects.requireNonNull(lineQueryPort, "lineQueryPort is null");
    }

    /**
     * 활성 라인 목록을 line_code 오름차순으로 조회합니다.
     *
     * @return 활성 라인 목록
     */
    public List<UiLine> getActiveLines() {
        return lineQueryPort.findActiveLines();
    }
}

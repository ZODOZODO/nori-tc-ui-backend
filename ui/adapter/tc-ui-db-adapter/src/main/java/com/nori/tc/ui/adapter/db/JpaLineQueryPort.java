package com.nori.tc.ui.adapter.db;

import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Repository;

import com.nori.tc.db.core.common.PageRequest;
import com.nori.tc.db.core.line.store.TcLineStore;
import com.nori.tc.ui.core.exception.UiBadRequestException;
import com.nori.tc.ui.core.port.db.LineQueryPort;
import com.nori.tc.ui.domain.line.UiLine;

/**
 * {@link LineQueryPort}의 공통 {@link TcLineStore} 기반 구현체입니다.
 *
 * <p>JpaGroupCrudPort 패턴 답습 — 자체 엔티티 없이 nori-tc-common 의 tc_line Store 를 래핑합니다.</p>
 */
@Repository
public class JpaLineQueryPort implements LineQueryPort {

    private static final int LINE_FIND_ALL_LIMIT = 500;

    private final TcLineStore lineStore;

    public JpaLineQueryPort(final TcLineStore lineStore) {
        this.lineStore = Objects.requireNonNull(lineStore, "lineStore is null");
    }

    @Override
    public List<UiLine> findActiveLines() {
        try {
            return lineStore.findAll(PageRequest.of(0, LINE_FIND_ALL_LIMIT)).stream()
                    .filter(line -> line != null && line.isActive())
                    .map(line -> new UiLine(line.lineKey(), line.lineCode(), line.lineName(), line.isActive()))
                    .toList();
        } catch (RuntimeException e) {
            if (UiDbAdapterExceptionSupport.isBadRequest(e)) {
                throw new UiBadRequestException("라인 목록 조회 입력이 올바르지 않습니다.", e);
            }
            throw e;
        }
    }
}

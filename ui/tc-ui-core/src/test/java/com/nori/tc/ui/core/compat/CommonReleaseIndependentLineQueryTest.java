package com.nori.tc.ui.core.compat;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.nori.tc.ui.core.port.db.LineQueryPort;
import com.nori.tc.ui.core.service.LineQueryService;
import com.nori.tc.ui.domain.line.UiLine;

/**
 * {@link CommonReleaseIndependentLineQuery} 가 조회 값과 순서를 보존함을 고정합니다(SPEC §10.1).
 *
 * <p>목적: {@link LineQueryPort#findActiveLines()} 의 고정 두 {@link UiLine} 이 서비스와 fixture 를 거쳐 같은 값·순서로
 * 반환되는지 서비스 저장소의 test 만으로 확인합니다. 제약: Spring context·mock 없이 port 의 고정 구현만 씁니다.</p>
 */
class CommonReleaseIndependentLineQueryTest {

    private static final UiLine FIRST = new UiLine(10L, "LINE-A", "Line A", true);
    private static final UiLine SECOND = new UiLine(20L, "LINE-B", "Line B", true);

    @Test
    void activeLinesPreservesPortValuesAndOrder() {
        final FixedLineQueryPort port = new FixedLineQueryPort();
        final CommonReleaseIndependentLineQuery query =
                new CommonReleaseIndependentLineQuery(new LineQueryService(port));

        final List<UiLine> lines = query.activeLines();

        Assertions.assertEquals(List.of(FIRST, SECOND), lines);
        Assertions.assertEquals("LINE-A", lines.get(0).lineCode());
        Assertions.assertEquals("LINE-B", lines.get(1).lineCode());
        Assertions.assertEquals(1, port.calls.get(), "port 는 한 번만 조회되어야 한다.");
    }

    @Test
    void constructorRejectsNullService() {
        Assertions.assertThrows(NullPointerException.class, () -> new CommonReleaseIndependentLineQuery(null));
    }

    /**
     * 고정 두 라인을 정해진 순서로 돌려주는 port 구현입니다.
     */
    private static final class FixedLineQueryPort implements LineQueryPort {

        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public List<UiLine> findActiveLines() {
            calls.incrementAndGet();
            return List.of(FIRST, SECOND);
        }
    }
}

package com.nori.tc.ui.adapter.db;

import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.nori.tc.db.core.common.PageRequest;
import com.nori.tc.db.core.eqp.store.TcEqpParamStore;
import com.nori.tc.db.core.eqp.store.TcEqpPortStatusStore;
import com.nori.tc.db.core.eqp.store.TcEqpStateHistStore;
import com.nori.tc.db.core.eqp.store.TcEqpStateStore;
import com.nori.tc.db.core.eqp.store.TcEqpStore;
import com.nori.tc.db.core.line.store.TcLineStore;
import com.nori.tc.db.core.model.store.TcModelParamStore;
import com.nori.tc.db.domain.common.eqp.ControlState;
import com.nori.tc.db.domain.common.eqp.EqpState;
import com.nori.tc.db.domain.common.eqp.EqpStateType;
import com.nori.tc.db.domain.common.model.ProtocolType;
import com.nori.tc.db.domain.eqp.TcEqp;
import com.nori.tc.db.domain.eqp.TcEqpParam;
import com.nori.tc.db.domain.eqp.TcEqpPortStatus;
import com.nori.tc.db.domain.eqp.TcEqpState;
import com.nori.tc.db.domain.eqp.TcEqpStateHist;
import com.nori.tc.db.domain.line.TcLine;
import com.nori.tc.db.domain.model.TcModelParam;
import com.nori.tc.ui.core.eqp.LiveMapEqpPrefetchRow;
import com.nori.tc.ui.core.eqp.LiveMapPrefetchSnapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link JpaLiveMapPrefetchPort} 단위 테스트.
 *
 * <p>핵심 검증:</p>
 * <ul>
 *   <li>EQP 가 없으면 후속 5 batch store 호출이 일어나지 않는다 (early return).</li>
 *   <li>정상 케이스에서 6 sub-query 만 호출되고 row 조립이 정확히 이뤄진다.</li>
 *   <li>EQP 필터: enabled=false 는 제외된다.</li>
 *   <li>param 필터: PROCESS_TYPE / WAFER_MODE 외 paramName 은 row 에 포함되지 않는다.</li>
 * </ul>
 */
class JpaLiveMapPrefetchPortTest {

    private final TcEqpStore eqpStore = mock(TcEqpStore.class);
    private final TcEqpStateStore eqpStateStore = mock(TcEqpStateStore.class);
    private final TcEqpStateHistStore eqpStateHistStore = mock(TcEqpStateHistStore.class);
    private final TcEqpParamStore eqpParamStore = mock(TcEqpParamStore.class);
    private final TcEqpPortStatusStore eqpPortStatusStore = mock(TcEqpPortStatusStore.class);
    private final TcModelParamStore modelParamStore = mock(TcModelParamStore.class);
    private final TcLineStore lineStore = mock(TcLineStore.class);

    private JpaLiveMapPrefetchPort newPort() {
        return new JpaLiveMapPrefetchPort(
                eqpStore,
                eqpStateStore,
                eqpStateHistStore,
                eqpParamStore,
                eqpPortStatusStore,
                modelParamStore,
                lineStore
        );
    }

    @Test
    @DisplayName("EQP 가 없으면 빈 snapshot 을 반환하고 후속 5 batch store 는 호출하지 않는다")
    void shouldShortCircuitWhenNoEqp() {
        when(eqpStore.findAll(any(PageRequest.class))).thenReturn(List.of());

        final LiveMapPrefetchSnapshot snapshot = newPort().prefetch();

        assertTrue(snapshot.rows().isEmpty(), "rows should be empty when no EQP exists");
        assertTrue(snapshot.modelParamsByModelVersionKey().isEmpty(), "modelParam map should be empty when no EQP exists");

        verify(eqpStateStore, never()).findAllByEqpKeyIn(anyCollection());
        verify(eqpStateHistStore, never()).findLatestConnStateByEqpKeyIn(anyCollection());
        verify(eqpParamStore, never()).findAllByEqpKeyIn(anyCollection());
        verify(eqpPortStatusStore, never()).findAllByEqpKeyIn(anyCollection());
        verify(modelParamStore, never()).findAllByModelVersionKeyIn(anyCollection());
    }

    @Test
    @DisplayName("정상 케이스: enabled EQP 만 row 로, 6 sub-query 만 호출, PROCESS_TYPE/WAFER_MODE 만 필터링")
    void shouldAssembleRowsViaSixBatchQueries() {
        final TcEqp eqp1 = newEqp(1L, "EQP-1", 10L, "v1", true);
        final TcEqp eqp2 = newEqp(2L, "EQP-2", 10L, "v1", true);
        final TcEqp eqpDisabled = newEqp(3L, "EQP-3", 11L, "v1", false);

        when(eqpStore.findAll(any(PageRequest.class))).thenReturn(List.of(eqp1, eqp2, eqpDisabled));

        when(eqpStateStore.findAllByEqpKeyIn(anyCollection())).thenReturn(List.of(
                new TcEqpState(1L, ControlState.REMOTE, EqpState.RUN, null, null, null, null, null),
                new TcEqpState(2L, ControlState.LOCAL, EqpState.IDLE, null, null, null, null, null)
        ));

        final OffsetDateTime ts = OffsetDateTime.parse("2026-05-14T05:00:00Z");
        when(eqpStateHistStore.findLatestConnStateByEqpKeyIn(anyCollection())).thenReturn(List.of(
                new TcEqpStateHist(901L, 1L, EqpStateType.CONN, null, "CONN", ts, null, null),
                new TcEqpStateHist(902L, 2L, EqpStateType.CONN, null, "CONN ALERT", ts, null, null)
        ));

        when(eqpParamStore.findAllByEqpKeyIn(anyCollection())).thenReturn(List.of(
                new TcEqpParam(50L, 1L, "PROCESS_TYPE", "v1", "CLN", null, null, null),
                new TcEqpParam(51L, 1L, "WAFER_MODE", "v1", "MIX", null, null, null),
                // 무관한 paramName 는 row 에 포함되지 않아야 한다.
                new TcEqpParam(52L, 1L, "MAX_TEMPERATURE", "v1", "300", null, null, null),
                new TcEqpParam(53L, 2L, "PROCESS_TYPE", "v1", "WET", null, null, null)
        ));

        when(eqpPortStatusStore.findAllByEqpKeyIn(anyCollection())).thenReturn(List.of(
                new TcEqpPortStatus(700L, 1L, "P1", null, "LOAD", null, null, null, null),
                new TcEqpPortStatus(701L, 2L, "P2", null, "IDLE", null, null, null, null)
        ));

        when(modelParamStore.findAllByModelVersionKeyIn(anyCollection())).thenReturn(List.of(
                new TcModelParam(900L, 10L, "PROCESS_TYPE", "CMP", null, null)
        ));

        when(lineStore.findAllByKeyIn(anyCollection())).thenReturn(List.of(
                new TcLine(100L, "A_LINE", "A Line", null, true, null, null)
        ));

        final LiveMapPrefetchSnapshot snapshot = newPort().prefetch();

        // disabled 제외 — 2 row 만
        assertEquals(2, snapshot.rows().size());
        final LiveMapEqpPrefetchRow row1 = snapshot.rows().get(0);
        final LiveMapEqpPrefetchRow row2 = snapshot.rows().get(1);

        assertEquals("EQP-1", row1.eqp().eqpId());
        // 7번째 batch query(tc_line) 결과가 row 에 부착되어 lineValue/lineLabel 실데이터가 채워진다.
        assertEquals("A_LINE", row1.lineCode());
        assertEquals("A Line", row1.lineName());
        assertEquals(List.of("PROCESS_TYPE", "WAFER_MODE"), row1.params().stream().map(p -> p.paramName()).toList());
        assertEquals(List.of("CLN", "MIX"), row1.params().stream().map(p -> p.paramValue()).toList());
        assertEquals(List.of("P1"), row1.portStatuses().stream().map(p -> p.portId()).toList());
        assertEquals("REMOTE", row1.runtimeState().controlState());
        assertEquals("RUN", row1.runtimeState().eqpState());
        assertEquals("CONN", row1.runtimeState().connectionState());

        assertEquals("EQP-2", row2.eqp().eqpId());
        assertEquals(List.of("PROCESS_TYPE"), row2.params().stream().map(p -> p.paramName()).toList());
        assertEquals(List.of("P2"), row2.portStatuses().stream().map(p -> p.portId()).toList());
        assertEquals("CONN ALERT", row2.runtimeState().connectionState());

        // modelParam map: model_version_key = 10 만 (disabled eqp 의 11 은 호출에 포함되지 않음)
        assertEquals(List.of(10L), snapshot.modelParamsByModelVersionKey().keySet().stream().toList());
        assertEquals(List.of("CMP"), snapshot.modelParamsByModelVersionKey().get(10L).stream().map(p -> p.paramValue()).toList());

        // 6 sub-query 정확히 1회씩
        verify(eqpStore, times(1)).findAll(any(PageRequest.class));
        verify(eqpStateStore, times(1)).findAllByEqpKeyIn(anyCollection());
        verify(eqpStateHistStore, times(1)).findLatestConnStateByEqpKeyIn(anyCollection());
        verify(eqpParamStore, times(1)).findAllByEqpKeyIn(anyCollection());
        verify(eqpPortStatusStore, times(1)).findAllByEqpKeyIn(anyCollection());
        verify(modelParamStore, times(1)).findAllByModelVersionKeyIn(anyCollection());
    }

    @Test
    @DisplayName("modelVersionKey 가 0 이하인 EQP 만 있으면 model_param batch 호출은 생략된다")
    void shouldSkipModelParamBatchWhenNoValidModelVersionKey() {
        // modelVersionKey = 0 (invalid) — distinct set 이 비어서 modelParamStore 호출이 생략돼야 한다.
        final TcEqp eqp = newEqp(1L, "EQP-1", 0L, "v1", true);
        when(eqpStore.findAll(any(PageRequest.class))).thenReturn(List.of(eqp));
        when(eqpStateStore.findAllByEqpKeyIn(anyCollection())).thenReturn(List.of());
        when(eqpStateHistStore.findLatestConnStateByEqpKeyIn(anyCollection())).thenReturn(List.of());
        when(eqpParamStore.findAllByEqpKeyIn(anyCollection())).thenReturn(List.of());
        when(eqpPortStatusStore.findAllByEqpKeyIn(anyCollection())).thenReturn(List.of());

        final LiveMapPrefetchSnapshot snapshot = newPort().prefetch();

        assertEquals(1, snapshot.rows().size());
        assertTrue(snapshot.modelParamsByModelVersionKey().isEmpty(), "modelParam map should be empty when no valid modelVersionKey");
        verify(modelParamStore, never()).findAllByModelVersionKeyIn(anyCollection());
    }

    private static TcEqp newEqp(final long eqpKey, final String eqpId, final long modelVersionKey, final String appliedParamVersion, final boolean enabled) {
        final OffsetDateTime now = OffsetDateTime.parse("2026-05-14T00:00:00Z");
        return new TcEqp(
                eqpKey,
                eqpId,
                100L,
                ProtocolType.SECS,
                "ACTIVE",
                false,
                1,
                "127.0.0.1",
                7000,
                modelVersionKey,
                appliedParamVersion,
                null,
                null,
                enabled,
                now,
                now,
                "SYSTEM",
                "SYSTEM"
        );
    }
}

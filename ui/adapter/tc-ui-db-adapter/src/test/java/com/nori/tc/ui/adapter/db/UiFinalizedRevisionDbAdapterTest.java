package com.nori.tc.ui.adapter.db;

import com.nori.tc.db.core.eqp.store.TcEqpStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * §13.7 마지막 finalized revision 기록/조회의 회귀망.
 *
 * <p>종전 배선은 {@code UiFinalizedRevisionProvider.absent()} 라 executor 의 기록이 언제나 실패로
 * 보고됐고({@code UI_FINALIZED_REVISION_NOT_RECORDED}), §11.11/§12.3 후퇴 검증의 기준값이 영원히
 * 비어 있었다.</p>
 *
 * <p>여기서 특히 고정하는 것은 <b>0 행의 두 의미</b>다 — "이미 같거나 큰 값이 있다"(성공: executor
 * crash 후 반복 확정)와 "설비 행이 없다"(실패)를 구분하지 않으면 삭제된 설비의 확정이 성공으로
 * 보고된다.</p>
 */
class UiFinalizedRevisionDbAdapterTest {

    @Test
    @DisplayName("갱신된 행이 있으면 기록 성공이다")
    void updatedRowMeansRecorded() {
        final TcEqpStore store = mock(TcEqpStore.class);
        when(store.updateLastFinalizedRevisionIfGreater("EQP-1", 10L)).thenReturn(1);

        assertTrue(new UiFinalizedRevisionDbAdapter(store).recordFinalizedRevision("EQP-1", 10L));
        verify(store, never()).findLastFinalizedRevision(anyString());
    }

    @Test
    @DisplayName("0 행이어도 이미 같거나 큰 값이 있으면 성공이다 — 반복 확정은 같은 결과로 수렴한다")
    void alreadyAtOrAboveTargetIsSuccess() {
        final TcEqpStore store = mock(TcEqpStore.class);
        when(store.updateLastFinalizedRevisionIfGreater("EQP-1", 10L)).thenReturn(0);
        when(store.findLastFinalizedRevision("EQP-1")).thenReturn(OptionalLong.of(11L));

        assertTrue(new UiFinalizedRevisionDbAdapter(store).recordFinalizedRevision("EQP-1", 10L));
    }

    @Test
    @DisplayName("0 행이고 기록도 없으면 실패다 — 설비 행이 없는 확정을 성공으로 보고하지 않는다")
    void missingEquipmentIsFailure() {
        final TcEqpStore store = mock(TcEqpStore.class);
        when(store.updateLastFinalizedRevisionIfGreater("EQP-GONE", 10L)).thenReturn(0);
        when(store.findLastFinalizedRevision("EQP-GONE")).thenReturn(OptionalLong.empty());

        assertFalse(new UiFinalizedRevisionDbAdapter(store).recordFinalizedRevision("EQP-GONE", 10L));
    }

    @Test
    @DisplayName("기록된 revision 을 그대로 조회한다")
    void readsRecordedRevision() {
        final TcEqpStore store = mock(TcEqpStore.class);
        when(store.findLastFinalizedRevision("EQP-1")).thenReturn(OptionalLong.of(7L));

        assertEquals(OptionalLong.of(7L),
                new UiFinalizedRevisionDbAdapter(store).lastFinalizedRevision("EQP-1"));
    }

    @Test
    @DisplayName("잘못된 입력은 저장소를 때리지 않는다")
    void invalidInputDoesNotQuery() {
        final TcEqpStore store = mock(TcEqpStore.class);
        final UiFinalizedRevisionDbAdapter adapter = new UiFinalizedRevisionDbAdapter(store);

        assertTrue(adapter.lastFinalizedRevision("  ").isEmpty());
        assertFalse(adapter.recordFinalizedRevision("EQP-1", 0L));
        assertFalse(adapter.recordFinalizedRevision(null, 5L));
        verify(store, never()).findLastFinalizedRevision(anyString());
        verify(store, never()).updateLastFinalizedRevisionIfGreater(anyString(), anyLong());
    }
}

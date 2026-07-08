package com.nori.tc.ui.adapter.db;

import com.nori.tc.db.core.model.store.TcModelDcopItemStore;
import com.nori.tc.db.core.model.store.TcModelEventIdStore;
import com.nori.tc.db.core.model.store.TcModelMdfStore;
import com.nori.tc.db.core.model.store.TcModelMesMessageStore;
import com.nori.tc.db.core.model.store.TcModelMessageFlowDcopItemStore;
import com.nori.tc.db.core.model.store.TcModelMessageFlowStore;
import com.nori.tc.db.core.model.store.TcModelParamStore;
import com.nori.tc.db.core.model.store.TcModelPlcGroupStore;
import com.nori.tc.db.core.model.store.TcModelPlcRegisterStore;
import com.nori.tc.db.core.model.store.TcModelReportIdStore;
import com.nori.tc.db.core.model.store.TcModelSecsMessageStore;
import com.nori.tc.db.core.model.store.TcModelSocketMessageStore;
import com.nori.tc.db.core.model.store.TcModelVariableIdStore;
import com.nori.tc.db.domain.model.TcModelDcopItem;
import com.nori.tc.db.domain.model.TcModelMessageFlow;
import com.nori.tc.db.domain.model.TcModelMessageFlowDcopItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link JpaModelDetailQueryPort#findFlowDcopItemNamesByModelVersionKey(long)} 단위 검증.
 *
 * <p>Collect 바인딩(tc_model_message_flow_dcop_item)을 workflow 노드 diff 에 실을 수 있도록,
 * flow 별 dcop item 이름을 정렬해 매핑하는지 확인한다.</p>
 */
class JpaModelDetailQueryPortFlowDcopTest {

    private static final OffsetDateTime TS = OffsetDateTime.parse("2026-03-11T10:15:30+09:00");
    private static final long MODEL_VERSION_KEY = 42L;

    @Test
    @DisplayName("flow 별 바인딩된 dcop item 이름을 이름 오름차순으로 매핑하고, 바인딩 없는 flow 는 제외한다")
    void mapsSortedDcopItemNamesPerFlow() {
        final TcModelMessageFlowStore messageFlowStore = mock(TcModelMessageFlowStore.class);
        final TcModelDcopItemStore dcopItemStore = mock(TcModelDcopItemStore.class);
        final TcModelMessageFlowDcopItemStore bindingStore = mock(TcModelMessageFlowDcopItemStore.class);

        // flow 10, 11 존재. dcop item 100="DCITEM_B", 101="DCITEM_A".
        when(messageFlowStore.findAllByModelVersionKey(anyLong(), any())).thenReturn(List.of(
                flow(10L),
                flow(11L)
        ));
        when(dcopItemStore.findAllByModelVersionKey(anyLong(), any())).thenReturn(List.of(
                dcopItem(100L, "DCITEM_B"),
                dcopItem(101L, "DCITEM_A")
        ));
        // flow 10 에 (100=B) 다음 (101=A) 순으로 바인딩 — 삽입 순서가 [B, A] 라 정렬이 실제로 동작해야 [A, B].
        when(bindingStore.findAllByMessageFlowKeyIn(any())).thenReturn(List.of(
                binding(10L, 100L),
                binding(10L, 101L)
        ));

        final JpaModelDetailQueryPort port = new JpaModelDetailQueryPort(
                mock(TcModelParamStore.class),
                mock(TcModelSecsMessageStore.class),
                mock(TcModelSocketMessageStore.class),
                mock(TcModelMesMessageStore.class),
                mock(TcModelVariableIdStore.class),
                mock(TcModelReportIdStore.class),
                mock(TcModelEventIdStore.class),
                messageFlowStore,
                mock(TcModelMdfStore.class),
                dcopItemStore,
                bindingStore,
                mock(TcModelPlcGroupStore.class),
                mock(TcModelPlcRegisterStore.class)
        );

        final Map<Long, List<String>> result = port.findFlowDcopItemNamesByModelVersionKey(MODEL_VERSION_KEY);

        assertEquals(List.of("DCITEM_A", "DCITEM_B"), result.get(10L));
        assertFalse(result.containsKey(11L));
    }

    private static TcModelMessageFlow flow(final long messageFlowKey) {
        return new TcModelMessageFlow(
                messageFlowKey, MODEL_VERSION_KEY, "FLOW_" + messageFlowKey, "WO_WORK_INFORM",
                null, null, null, "LOG_ONLY", null, TS);
    }

    private static TcModelDcopItem dcopItem(final long dcopItemKey, final String name) {
        return new TcModelDcopItem(dcopItemKey, MODEL_VERSION_KEY, name, null, "VID", null, null, null, TS);
    }

    private static TcModelMessageFlowDcopItem binding(final long messageFlowKey, final long dcopItemKey) {
        return new TcModelMessageFlowDcopItem(0L, messageFlowKey, dcopItemKey, MODEL_VERSION_KEY, TS);
    }
}

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
import com.nori.tc.db.domain.common.model.PlcArea;
import com.nori.tc.db.domain.common.model.PlcDataType;
import com.nori.tc.db.domain.model.TcModelPlcGroup;
import com.nori.tc.db.domain.model.TcModelPlcRegister;
import com.nori.tc.ui.domain.model.UiModelPlcGroup;
import com.nori.tc.ui.domain.model.UiModelPlcRegister;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link JpaModelDetailQueryPort} 의 PLC 그룹/레지스터 조회(평탄화 + 소속 그룹명 운반 + enum→String)를 검증합니다.
 */
class JpaModelDetailQueryPortPlcTest {

    @Test
    @DisplayName("findPlcGroupsByModelVersionKey 는 그룹키 순으로 정렬하고 area enum 을 String 으로 운반한다")
    void findPlcGroups_sortsAndMapsAreaToString() {
        final Fixture fixture = new Fixture();
        when(fixture.modelPlcGroupStore.findAllByModelVersionKey(101L)).thenReturn(List.of(
                group(8L, 101L, "CARRIER_INFO", PlcArea.INPUT_REGISTER),
                group(7L, 101L, "STATUS_REPORT", PlcArea.HOLDING_REGISTER)
        ));

        final List<UiModelPlcGroup> groups = fixture.port.findPlcGroupsByModelVersionKey(101L);

        assertEquals(2, groups.size());
        assertEquals("STATUS_REPORT", groups.get(0).messageName());
        assertEquals("HOLDING_REGISTER", groups.get(0).area());
        assertEquals("CARRIER_INFO", groups.get(1).messageName());
        assertEquals("INPUT_REGISTER", groups.get(1).area());
    }

    @Test
    @DisplayName("findPlcRegistersByModelVersionKey 는 모든 그룹의 레지스터를 평탄화하고 소속 그룹 message_name 을 col0 으로 운반한다")
    void findPlcRegisters_flattensWithGroupMessageName() {
        final Fixture fixture = new Fixture();
        when(fixture.modelPlcGroupStore.findAllByModelVersionKey(101L)).thenReturn(List.of(
                group(7L, 101L, "STATUS_REPORT", PlcArea.HOLDING_REGISTER),
                group(8L, 101L, "CARRIER_INFO", PlcArea.HOLDING_REGISTER)
        ));
        when(fixture.modelPlcRegisterStore.findAllByGroupKey(7L)).thenReturn(List.of(
                register(101L, 7L, 0, "RUN_STATE", PlcDataType.BOOL, 0),
                register(102L, 7L, 1, "TEMP", PlcDataType.INT16, null)
        ));
        when(fixture.modelPlcRegisterStore.findAllByGroupKey(8L)).thenReturn(List.of(
                register(103L, 8L, 0, "CAR_ID", PlcDataType.STRING, null)
        ));

        final List<UiModelPlcRegister> registers = fixture.port.findPlcRegistersByModelVersionKey(101L);

        assertEquals(3, registers.size());
        assertEquals("STATUS_REPORT", registers.get(0).groupMessageName());
        assertEquals("RUN_STATE", registers.get(0).variableName());
        assertEquals("BOOL", registers.get(0).dataType());
        assertEquals(Integer.valueOf(0), registers.get(0).bitIndex());
        assertEquals("STATUS_REPORT", registers.get(1).groupMessageName());
        assertEquals("TEMP", registers.get(1).variableName());
        assertEquals("CARRIER_INFO", registers.get(2).groupMessageName());
        assertEquals("CAR_ID", registers.get(2).variableName());
    }

    private static TcModelPlcGroup group(
            final long groupKey,
            final long modelVersionKey,
            final String messageName,
            final PlcArea area
    ) {
        return new TcModelPlcGroup(
                groupKey, modelVersionKey, messageName, area, 0, 10, null, 1000, null,
                OffsetDateTime.parse("2026-03-13T10:15:30+09:00"));
    }

    private static TcModelPlcRegister register(
            final long registerKey,
            final long groupKey,
            final int wordOffset,
            final String variableName,
            final PlcDataType dataType,
            final Integer bitIndex
    ) {
        return new TcModelPlcRegister(
                registerKey, groupKey, wordOffset, variableName, dataType, null, bitIndex, null,
                null, null, null, null, null, null,
                OffsetDateTime.parse("2026-03-13T10:15:30+09:00"));
    }

    private static final class Fixture {

        private final TcModelPlcGroupStore modelPlcGroupStore = mock(TcModelPlcGroupStore.class);
        private final TcModelPlcRegisterStore modelPlcRegisterStore = mock(TcModelPlcRegisterStore.class);
        private final JpaModelDetailQueryPort port = new JpaModelDetailQueryPort(
                mock(TcModelParamStore.class),
                mock(TcModelSecsMessageStore.class),
                mock(TcModelSocketMessageStore.class),
                mock(TcModelMesMessageStore.class),
                mock(TcModelVariableIdStore.class),
                mock(TcModelReportIdStore.class),
                mock(TcModelEventIdStore.class),
                mock(TcModelMessageFlowStore.class),
                mock(TcModelMdfStore.class),
                mock(TcModelDcopItemStore.class),
                mock(TcModelMessageFlowDcopItemStore.class),
                modelPlcGroupStore,
                modelPlcRegisterStore
        );
    }
}

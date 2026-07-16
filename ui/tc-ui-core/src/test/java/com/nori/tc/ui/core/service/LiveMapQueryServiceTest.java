package com.nori.tc.ui.core.service;

import com.nori.tc.ui.core.eqp.LiveMapEqpPrefetchRow;
import com.nori.tc.ui.core.eqp.LiveMapEquipmentView;
import com.nori.tc.ui.core.eqp.LiveMapPrefetchSnapshot;
import com.nori.tc.ui.core.port.db.EqpQueryPort;
import com.nori.tc.ui.core.port.db.LiveMapPrefetchPort;
import com.nori.tc.ui.domain.common.eqp.UiCarrierState;
import com.nori.tc.ui.domain.common.eqp.UiPortType;
import com.nori.tc.ui.domain.common.model.UiProtocolType;
import com.nori.tc.ui.domain.eqp.UiEqp;
import com.nori.tc.ui.domain.eqp.UiEqpParam;
import com.nori.tc.ui.domain.eqp.UiEqpPortStatus;
import com.nori.tc.ui.domain.model.UiModelParam;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link LiveMapQueryService} 단위 테스트.
 *
 * <p>service 는 더 이상 3 port (eqp / manage / model) 에 의존하지 않고 {@link LiveMapPrefetchPort}
 * 1개에만 의존한다. 본 테스트는 prefetch 결과 row 를 직접 구성하여 매핑 로직 5 시나리오를 검증한다.</p>
 */
class LiveMapQueryServiceTest {

    private LiveMapPrefetchPort liveMapPrefetchPort;
    private LiveMapQueryService liveMapQueryService;

    @BeforeEach
    void setUp() {
        liveMapPrefetchPort = mock(LiveMapPrefetchPort.class);
        liveMapQueryService = new LiveMapQueryService(liveMapPrefetchPort);
    }

    @Test
    void getEquipmentViewsUsesEqpProcessTypeBeforeModelProcessType() {
        final UiEqp eqp = createEqp("EQP-01", "v1");
        final LiveMapEqpPrefetchRow row = new LiveMapEqpPrefetchRow(
                eqp,
                "C_LINE",
                "C Line",
                List.of(
                        new UiEqpParam(1L, 1L, "PROCESS_TYPE", "v1", "CVD", null, "tester", OffsetDateTime.now()),
                        new UiEqpParam(2L, 1L, "WAFER_MODE", "v1", "SINGLE", null, "tester", OffsetDateTime.now())
                ),
                List.of(new UiEqpPortStatus(
                        1L,
                        1L,
                        "LP1",
                        UiPortType.LOAD_PORT,
                        "load",
                        "CAR-01",
                        null,
                        UiCarrierState.CLAMPED,
                        OffsetDateTime.now()
                )),
                new EqpQueryPort.EqpRuntimeStateView("REMOTE", "RUN", "CONNECTED", "STARTED")
        );
        // eqp param 우선이라 modelParam 값은 의도적으로 무시되어야 함을 확인하기 위해 다른 값 주입.
        final Map<Long, List<UiModelParam>> modelParams = Map.of(
                101L, List.of(new UiModelParam(1L, 101L, "PROCESS_TYPE", "CMP", null, OffsetDateTime.now()))
        );
        when(liveMapPrefetchPort.prefetch()).thenReturn(new LiveMapPrefetchSnapshot(List.of(row), modelParams));

        final List<LiveMapEquipmentView> result = liveMapQueryService.getEquipmentViews();

        assertEquals(1, result.size());
        assertEquals("cvd", result.getFirst().clusterId());
        assertEquals("RUN", result.getFirst().status());
        assertEquals("SINGLE", result.getFirst().equipmentModeLabel());
        assertEquals("CAR-01", result.getFirst().portItems().getFirst().carrierId());
        assertEquals("LP1 · CARID CAR-01", result.getFirst().tooltipMeta());
    }

    @Test
    void getEquipmentViewsFallsBackToModelProcessTypeAndMapsOfflineToDown() {
        final UiEqp eqp = createEqp("EQP-02", "v2");
        final LiveMapEqpPrefetchRow row = new LiveMapEqpPrefetchRow(
                eqp,
                "C_LINE",
                "C Line",
                List.of(),
                List.of(new UiEqpPortStatus(
                        2L,
                        2L,
                        "LP2",
                        UiPortType.LOAD_PORT,
                        "idle",
                        null,
                        null,
                        UiCarrierState.UNKNOWN,
                        OffsetDateTime.now()
                )),
                new EqpQueryPort.EqpRuntimeStateView("OFFLINE", "IDLE", "ENDED", "ENDED")
        );
        final Map<Long, List<UiModelParam>> modelParams = Map.of(
                101L, List.of(
                        new UiModelParam(2L, 101L, "PROCESS_TYPE", "PHOTO", null, OffsetDateTime.now()),
                        new UiModelParam(3L, 101L, "WAFER_MODE", "BATCH", null, OffsetDateTime.now())
                )
        );
        when(liveMapPrefetchPort.prefetch()).thenReturn(new LiveMapPrefetchSnapshot(List.of(row), modelParams));

        final List<LiveMapEquipmentView> result = liveMapQueryService.getEquipmentViews();

        assertEquals("photo", result.getFirst().clusterId());
        assertEquals("DOWN", result.getFirst().status());
        assertEquals("BATCH", result.getFirst().equipmentModeLabel());
        assertEquals(null, result.getFirst().portItems().getFirst().carrierId());
        assertEquals("LP2 · CARID Empty", result.getFirst().tooltipMeta());
    }

    @Test
    void getEquipmentViewsMapsMaintenanceToError() {
        final UiEqp eqp = createEqp("EQP-03", "v3");
        final LiveMapEqpPrefetchRow row = new LiveMapEqpPrefetchRow(
                eqp,
                "C_LINE",
                "C Line",
                List.of(new UiEqpParam(3L, 1L, "PROCESS_TYPE", "v3", "CMP", null, "tester", OffsetDateTime.now())),
                List.of(),
                new EqpQueryPort.EqpRuntimeStateView("LOCAL", "MAINTENANCE", "CONNECTED", "STARTED")
        );
        when(liveMapPrefetchPort.prefetch()).thenReturn(new LiveMapPrefetchSnapshot(List.of(row), Map.of()));

        final List<LiveMapEquipmentView> result = liveMapQueryService.getEquipmentViews();

        assertEquals("ERROR", result.getFirst().status());
    }

    @Test
    void getEquipmentViewsMapsServiceUnavailableToDownWhenControlStateIsRemote() {
        final UiEqp eqp = createEqp("EQP-04", "v4");
        final LiveMapEqpPrefetchRow row = new LiveMapEqpPrefetchRow(
                eqp,
                "C_LINE",
                "C Line",
                List.of(new UiEqpParam(4L, 1L, "PROCESS_TYPE", "v4", "ETCH", null, "tester", OffsetDateTime.now())),
                List.of(),
                new EqpQueryPort.EqpRuntimeStateView("REMOTE", "SERVICE_UNAVAILABLE", "CONNECTED", "STARTED")
        );
        when(liveMapPrefetchPort.prefetch()).thenReturn(new LiveMapPrefetchSnapshot(List.of(row), Map.of()));

        final List<LiveMapEquipmentView> result = liveMapQueryService.getEquipmentViews();

        assertEquals("DOWN", result.getFirst().status());
    }

    @Test
    void getEquipmentViewsMapsClnProcessTypeToWetCluster() {
        final UiEqp eqp = createEqp("EQP-05", "v5");
        final LiveMapEqpPrefetchRow row = new LiveMapEqpPrefetchRow(
                eqp,
                "C_LINE",
                "C Line",
                List.of(new UiEqpParam(5L, 1L, "PROCESS_TYPE", "v5", "CLN", null, "tester", OffsetDateTime.now())),
                List.of(),
                new EqpQueryPort.EqpRuntimeStateView("REMOTE", "RUN", "CONNECTED", "STARTED")
        );
        when(liveMapPrefetchPort.prefetch()).thenReturn(new LiveMapPrefetchSnapshot(List.of(row), Map.of()));

        final List<LiveMapEquipmentView> result = liveMapQueryService.getEquipmentViews();

        // CLN(세정) 공정은 WET 공정과 동일 의미이므로 wet 클러스터에 배치되어야 합니다.
        assertEquals("wet", result.getFirst().clusterId());
        assertEquals("WET", result.getFirst().clusterName());
    }

    @Test
    void getEquipmentViewsReturnsLineValueAndLabelFromEqpTcLine() {
        // 하드코딩 "A_LINE" 회귀 차단 — 설비 row 에 부착된 실제 tc_line 의 code/name 이 view 에 반영되어야 한다.
        final UiEqp eqp = createEqp("EQP-LINE-01", "v1");
        final LiveMapEqpPrefetchRow row = new LiveMapEqpPrefetchRow(
                eqp,
                "B_LINE",
                "B Line",
                List.of(new UiEqpParam(9L, 1L, "PROCESS_TYPE", "v1", "ETCH", null, "tester", OffsetDateTime.now())),
                List.of(),
                new EqpQueryPort.EqpRuntimeStateView("REMOTE", "RUN", "CONNECTED", "STARTED")
        );
        when(liveMapPrefetchPort.prefetch()).thenReturn(new LiveMapPrefetchSnapshot(List.of(row), Map.of()));

        final List<LiveMapEquipmentView> result = liveMapQueryService.getEquipmentViews();

        assertEquals("B_LINE", result.getFirst().lineValue());
        assertEquals("B Line", result.getFirst().lineLabel());
    }

    @Test
    void getEquipmentViewsReturnsEqpKeyForLayoutPlacementBinding() {
        // live-map 빌더 placement 는 eqp_key(BIGINT PK)로 실설비를 바인딩한다 — 뷰가 eqpKey 를 노출해야
        // 프론트가 placement.eqpKey ↔ 실시간 상태(RUN/IDLE/ERROR/DOWN)를 매칭할 수 있다.
        final UiEqp eqp = createEqp("EQP-KEY-01", "v1");
        final LiveMapEqpPrefetchRow row = new LiveMapEqpPrefetchRow(
                eqp,
                "B_LINE",
                "B Line",
                List.of(new UiEqpParam(11L, 1L, "PROCESS_TYPE", "v1", "ETCH", null, "tester", OffsetDateTime.now())),
                List.of(),
                new EqpQueryPort.EqpRuntimeStateView("REMOTE", "RUN", "CONNECTED", "STARTED")
        );
        when(liveMapPrefetchPort.prefetch()).thenReturn(new LiveMapPrefetchSnapshot(List.of(row), Map.of()));

        final List<LiveMapEquipmentView> result = liveMapQueryService.getEquipmentViews();

        assertEquals(1L, result.getFirst().eqpKey());
    }

    @Test
    void liveMapProjectionExposesDesiredStateToDistinguishStuckFromEndedWhileStatusEnumStays() {
        // AC-C3: (STARTED,OFFLINE)=stuck 과 (ENDED,OFFLINE)=정상-ended 를 desiredState 로 구분 가능해야 한다.
        // status enum 은 4값 유지(둘 다 DOWN) — 5번째 값 신설 없음(D6).
        final LiveMapEqpPrefetchRow stuckRow = new LiveMapEqpPrefetchRow(
                createEqp("EQP-STUCK", "v1"), "B_LINE", "B Line",
                List.of(new UiEqpParam(21L, 1L, "PROCESS_TYPE", "v1", "ETCH", null, "tester", OffsetDateTime.now())),
                List.of(),
                new EqpQueryPort.EqpRuntimeStateView("OFFLINE", "IDLE", "STARTED", "STARTED")
        );
        final LiveMapEqpPrefetchRow endedRow = new LiveMapEqpPrefetchRow(
                createEqp("EQP-ENDED", "v1"), "B_LINE", "B Line",
                List.of(new UiEqpParam(22L, 1L, "PROCESS_TYPE", "v1", "ETCH", null, "tester", OffsetDateTime.now())),
                List.of(),
                new EqpQueryPort.EqpRuntimeStateView("OFFLINE", "IDLE", "ENDED", "ENDED")
        );
        when(liveMapPrefetchPort.prefetch())
                .thenReturn(new LiveMapPrefetchSnapshot(List.of(stuckRow, endedRow), Map.of()));

        final List<LiveMapEquipmentView> result = liveMapQueryService.getEquipmentViews();

        final LiveMapEquipmentView stuck = result.stream()
                .filter(v -> "STARTED".equals(v.desiredState())).findFirst().orElseThrow();
        final LiveMapEquipmentView ended = result.stream()
                .filter(v -> "ENDED".equals(v.desiredState())).findFirst().orElseThrow();
        // status enum 은 4값 유지 — 둘 다 DOWN(끊김)이라 status 만으로는 구분 불가.
        assertEquals("DOWN", stuck.status());
        assertEquals("DOWN", ended.status());
        // desiredState 노출로 stuck 과 정상-ended 를 구분한다.
        assertEquals("STARTED", stuck.desiredState());
        assertEquals("ENDED", ended.desiredState());
    }

    private UiEqp createEqp(final String eqpId, final String appliedParamVersion) {
        return new UiEqp(
                1L,
                eqpId,
                7L,
                UiProtocolType.SOCKET,
                "ACTIVE",
                false,
                0,
                "127.0.0.1",
                5000,
                101L,
                appliedParamVersion,
                null,
                null,
                true,
                OffsetDateTime.now(),
                OffsetDateTime.now(),
                "tester",
                "tester"
        );
    }
}

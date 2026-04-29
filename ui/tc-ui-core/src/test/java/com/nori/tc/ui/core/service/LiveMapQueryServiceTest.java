package com.nori.tc.ui.core.service;

import com.nori.tc.ui.core.eqp.EqpManagementSnapshot;
import com.nori.tc.ui.core.eqp.LiveMapEquipmentView;
import com.nori.tc.ui.core.model.PagedResponse;
import com.nori.tc.ui.core.model.UiPageRequest;
import com.nori.tc.ui.core.port.db.EqpManageQueryPort;
import com.nori.tc.ui.core.port.db.EqpQueryPort;
import com.nori.tc.ui.core.port.db.ModelDetailQueryPort;
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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LiveMapQueryServiceTest {

    private EqpQueryPort eqpQueryPort;
    private EqpManageQueryPort eqpManageQueryPort;
    private ModelDetailQueryPort modelDetailQueryPort;
    private LiveMapQueryService liveMapQueryService;

    @BeforeEach
    void setUp() {
        eqpQueryPort = mock(EqpQueryPort.class);
        eqpManageQueryPort = mock(EqpManageQueryPort.class);
        modelDetailQueryPort = mock(ModelDetailQueryPort.class);
        liveMapQueryService = new LiveMapQueryService(eqpQueryPort, eqpManageQueryPort, modelDetailQueryPort);
    }

    @Test
    void getEquipmentViewsUsesEqpProcessTypeBeforeModelProcessType() {
        final UiEqp eqp = createEqp("EQP-01", "v1");
        when(eqpQueryPort.findAll(any(UiPageRequest.class)))
                .thenReturn(PagedResponse.of(List.of(eqp), 0, 1000, 1));
        when(eqpManageQueryPort.findManageSnapshotByEqpId("EQP-01"))
                .thenReturn(Optional.of(new EqpManagementSnapshot(
                        eqp,
                        null,
                        null,
                        null,
                        null,
                        null,
                        "CONNECTED",
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
                        List.of(new UiEqpParam(
                                1L,
                                1L,
                                "PROCESS_TYPE",
                                "v1",
                                "CVD",
                                null,
                                "tester",
                                OffsetDateTime.now()
                        ), new UiEqpParam(
                                2L,
                                1L,
                                "WAFER_MODE",
                                "v1",
                                "SINGLE",
                                null,
                                "tester",
                                OffsetDateTime.now()
                        )),
                        List.of(),
                        null,
                        null
                )));
        when(modelDetailQueryPort.findParamsByModelVersionKey(101L))
                .thenReturn(List.of(new UiModelParam(
                        1L,
                        101L,
                        "PROCESS_TYPE",
                        "CMP",
                        null,
                        OffsetDateTime.now()
                )));
        when(eqpQueryPort.findRuntimeStateByEqpId("EQP-01"))
                .thenReturn(Optional.of(new EqpQueryPort.EqpRuntimeStateView("REMOTE", "RUN", "CONNECTED")));

        final List<LiveMapEquipmentView> result = liveMapQueryService.getEquipmentViews();

        assertEquals(1, result.size());
        assertEquals("cvd", result.getFirst().clusterId());
        assertEquals("RUN", result.getFirst().status());
        assertEquals("SINGLE", result.getFirst().equipmentModeLabel());
        assertEquals("LP1 · CARID CAR-01", result.getFirst().tooltipMeta());
    }

    @Test
    void getEquipmentViewsFallsBackToModelProcessTypeAndMapsOfflineToDown() {
        final UiEqp eqp = createEqp("EQP-02", "v2");
        when(eqpQueryPort.findAll(any(UiPageRequest.class)))
                .thenReturn(PagedResponse.of(List.of(eqp), 0, 1000, 1));
        when(eqpManageQueryPort.findManageSnapshotByEqpId("EQP-02"))
                .thenReturn(Optional.of(new EqpManagementSnapshot(
                        eqp,
                        null,
                        null,
                        null,
                        null,
                        null,
                        "ENDED",
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
                        List.of(),
                        List.of(),
                        null,
                        null
                )));
        when(modelDetailQueryPort.findParamsByModelVersionKey(101L))
                .thenReturn(List.of(new UiModelParam(
                        2L,
                        101L,
                        "PROCESS_TYPE",
                        "PHOTO",
                        null,
                        OffsetDateTime.now()
                ), new UiModelParam(
                        3L,
                        101L,
                        "WAFER_MODE",
                        "BATCH",
                        null,
                        OffsetDateTime.now()
                )));
        when(eqpQueryPort.findRuntimeStateByEqpId("EQP-02"))
                .thenReturn(Optional.of(new EqpQueryPort.EqpRuntimeStateView("OFFLINE", "IDLE", "ENDED")));

        final List<LiveMapEquipmentView> result = liveMapQueryService.getEquipmentViews();

        assertEquals("photo", result.getFirst().clusterId());
        assertEquals("DOWN", result.getFirst().status());
        assertEquals("BATCH", result.getFirst().equipmentModeLabel());
        assertEquals("LP2 · CARID Empty", result.getFirst().tooltipMeta());
    }

    @Test
    void getEquipmentViewsMapsMaintenanceToError() {
        final UiEqp eqp = createEqp("EQP-03", "v3");
        when(eqpQueryPort.findAll(any(UiPageRequest.class)))
                .thenReturn(PagedResponse.of(List.of(eqp), 0, 1000, 1));
        when(eqpManageQueryPort.findManageSnapshotByEqpId("EQP-03"))
                .thenReturn(Optional.of(new EqpManagementSnapshot(
                        eqp,
                        null,
                        null,
                        null,
                        null,
                        null,
                        "CONNECTED",
                        List.of(),
                        List.of(new UiEqpParam(
                                3L,
                                1L,
                                "PROCESS_TYPE",
                                "v3",
                                "CMP",
                                null,
                                "tester",
                                OffsetDateTime.now()
                        )),
                        List.of(),
                        null,
                        null
                )));
        when(modelDetailQueryPort.findParamsByModelVersionKey(101L))
                .thenReturn(List.of());
        when(eqpQueryPort.findRuntimeStateByEqpId("EQP-03"))
                .thenReturn(Optional.of(new EqpQueryPort.EqpRuntimeStateView("LOCAL", "MAINTENANCE", "CONNECTED")));

        final List<LiveMapEquipmentView> result = liveMapQueryService.getEquipmentViews();

        assertEquals("ERROR", result.getFirst().status());
    }

    @Test
    void getEquipmentViewsMapsServiceUnavailableToDownWhenControlStateIsRemote() {
        final UiEqp eqp = createEqp("EQP-04", "v4");
        when(eqpQueryPort.findAll(any(UiPageRequest.class)))
                .thenReturn(PagedResponse.of(List.of(eqp), 0, 1000, 1));
        when(eqpManageQueryPort.findManageSnapshotByEqpId("EQP-04"))
                .thenReturn(Optional.of(new EqpManagementSnapshot(
                        eqp,
                        null,
                        null,
                        null,
                        null,
                        null,
                        "CONNECTED",
                        List.of(),
                        List.of(new UiEqpParam(
                                4L,
                                1L,
                                "PROCESS_TYPE",
                                "v4",
                                "ETCH",
                                null,
                                "tester",
                                OffsetDateTime.now()
                        )),
                        List.of(),
                        null,
                        null
                )));
        when(modelDetailQueryPort.findParamsByModelVersionKey(101L))
                .thenReturn(List.of());
        when(eqpQueryPort.findRuntimeStateByEqpId("EQP-04"))
                .thenReturn(Optional.of(new EqpQueryPort.EqpRuntimeStateView("REMOTE", "SERVICE_UNAVAILABLE", "CONNECTED")));

        final List<LiveMapEquipmentView> result = liveMapQueryService.getEquipmentViews();

        assertEquals("DOWN", result.getFirst().status());
    }

    @Test
    void getEquipmentViewsMapsClnProcessTypeToWetCluster() {
        final UiEqp eqp = createEqp("EQP-05", "v5");
        when(eqpQueryPort.findAll(any(UiPageRequest.class)))
                .thenReturn(PagedResponse.of(List.of(eqp), 0, 1000, 1));
        when(eqpManageQueryPort.findManageSnapshotByEqpId("EQP-05"))
                .thenReturn(Optional.of(new EqpManagementSnapshot(
                        eqp,
                        null,
                        null,
                        null,
                        null,
                        null,
                        "CONNECTED",
                        List.of(),
                        List.of(new UiEqpParam(
                                5L,
                                1L,
                                "PROCESS_TYPE",
                                "v5",
                                "CLN",
                                null,
                                "tester",
                                OffsetDateTime.now()
                        )),
                        List.of(),
                        null,
                        null
                )));
        when(modelDetailQueryPort.findParamsByModelVersionKey(101L))
                .thenReturn(List.of());
        when(eqpQueryPort.findRuntimeStateByEqpId("EQP-05"))
                .thenReturn(Optional.of(new EqpQueryPort.EqpRuntimeStateView("REMOTE", "RUN", "CONNECTED")));

        final List<LiveMapEquipmentView> result = liveMapQueryService.getEquipmentViews();

        // CLN(세정) 공정은 WET 공정과 동일 의미이므로 wet 클러스터에 배치되어야 합니다.
        assertEquals("wet", result.getFirst().clusterId());
        assertEquals("WET", result.getFirst().clusterName());
    }

    private UiEqp createEqp(final String eqpId, final String appliedParamVersion) {
        return new UiEqp(
                1L,
                eqpId,
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

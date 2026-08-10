package com.nori.tc.ui.core.service;

import com.nori.tc.ui.core.eqp.EqpManagementSnapshot;
import com.nori.tc.ui.core.port.db.EqpManageQueryPort;
import com.nori.tc.ui.core.port.db.EqpOptionsQueryPort;
import com.nori.tc.ui.core.port.redis.AsyncResultStorePort;
import com.nori.tc.ui.domain.common.model.UiProtocolType;
import com.nori.tc.ui.domain.eqp.UiEqp;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code GET /api/eqp/manage-batch} 가 설비 수와 무관하게 고정 횟수로 조회하는지 검증합니다.
 *
 * <p>예전 구현은 설비마다 단건 조회를 돌렸고, 단건 하나가 다시 서브 store 를 10 여 회 호출했습니다.
 * 설비 150 대짜리 그룹 화면 한 번이 1,500 회 넘는 DB 왕복을 냈고, 그 부하가 같은 순간의
 * EQP_CREATE 를 10s timeout 밖으로 밀어냈습니다(2026-08-10 실측: manage-batch 13~17s,
 * EQP_CREATE 12.2s 로 timeout). 호출 횟수를 세는 단언이 없어 그 회귀가 그대로 배포됐습니다.</p>
 */
class EqpManagementServiceManageBatchTest {

    private static final OffsetDateTime TS = OffsetDateTime.now(ZoneOffset.UTC);

    private final EqpManageQueryPort queryPort = mock(EqpManageQueryPort.class);

    private final EqpManagementService service = new EqpManagementService(
            queryPort,
            mock(EqpOptionsQueryPort.class),
            mock(EqpDirectScenarioService.class),
            mock(AsyncResultStorePort.class),
            mock(Executor.class)
    );

    @Test
    @DisplayName("설비가 몇 대든 포트 배치 조회를 한 번만 부르고 단건 조회는 부르지 않습니다")
    void usesSingleBatchLookupRegardlessOfEqpCount() {
        final List<String> eqpIds = List.of("E1", "E2", "E3", "E4", "E5");
        when(queryPort.findManageSnapshotsByEqpIds(any())).thenReturn(snapshots(eqpIds));

        final Map<String, EqpManagementSnapshot> result = service.getManageDetailsByEqpIds(eqpIds);

        assertEquals(5, result.size());
        verify(queryPort, times(1)).findManageSnapshotsByEqpIds(eqpIds);
        verify(queryPort, never()).findManageSnapshotByEqpId(anyString());
    }

    @Test
    @DisplayName("응답 순서는 배치 조회 결과 순서가 아니라 요청 순서를 따릅니다")
    void preservesRequestOrder() {
        // 배치 조회는 store 스캔 순서를 따르므로 요청 순서와 다를 수 있다.
        final LinkedHashMap<String, EqpManagementSnapshot> outOfOrder = new LinkedHashMap<>();
        outOfOrder.put("E3", snapshot("E3"));
        outOfOrder.put("E1", snapshot("E1"));
        outOfOrder.put("E2", snapshot("E2"));
        when(queryPort.findManageSnapshotsByEqpIds(any())).thenReturn(outOfOrder);

        final Map<String, EqpManagementSnapshot> result =
                service.getManageDetailsByEqpIds(List.of("E1", "E2", "E3"));

        assertEquals(List.of("E1", "E2", "E3"), List.copyOf(result.keySet()));
    }

    @Test
    @DisplayName("공백/중복 eqpId 는 정규화해 배치 조회에 한 번씩만 넘깁니다")
    void normalizesBlankAndDuplicateIds() {
        when(queryPort.findManageSnapshotsByEqpIds(any())).thenReturn(snapshots(List.of("E1", "E2")));

        service.getManageDetailsByEqpIds(List.of(" E1 ", "E1", "", "  ", "E2"));

        verify(queryPort, times(1)).findManageSnapshotsByEqpIds(List.of("E1", "E2"));
    }

    @Test
    @DisplayName("배치 결과에 없는 설비는 결과 맵에서 빠집니다")
    void dropsMissingEqpIds() {
        when(queryPort.findManageSnapshotsByEqpIds(any())).thenReturn(snapshots(List.of("E1")));

        final Map<String, EqpManagementSnapshot> result =
                service.getManageDetailsByEqpIds(List.of("E1", "GONE"));

        assertEquals(List.of("E1"), List.copyOf(result.keySet()));
    }

    @Test
    @DisplayName("유효한 eqpId 가 하나도 없으면 조회 자체를 하지 않습니다")
    void skipsLookupWhenNoValidId() {
        assertTrue(service.getManageDetailsByEqpIds(List.of(" ", "")).isEmpty());

        verify(queryPort, never()).findManageSnapshotsByEqpIds(any());
    }

    private static Map<String, EqpManagementSnapshot> snapshots(final List<String> eqpIds) {
        final LinkedHashMap<String, EqpManagementSnapshot> map = new LinkedHashMap<>();
        eqpIds.forEach(eqpId -> map.put(eqpId, snapshot(eqpId)));
        return map;
    }

    private static EqpManagementSnapshot snapshot(final String eqpId) {
        final UiEqp eqp = new UiEqp(
                1L, eqpId, 1L, UiProtocolType.SOCKET, "ACTIVE", false, 0,
                "127.0.0.1", 5000, 1L, null, null, null, true, TS, TS, "SYSTEM", "SYSTEM"
        );
        return new EqpManagementSnapshot(
                eqp, null, null, null, null, null, null, null,
                List.of(), List.of(), List.of(), null, null
        );
    }
}

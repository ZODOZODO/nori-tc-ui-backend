package com.nori.tc.ui.core.service;

import com.nori.tc.ui.core.eqp.EqpManagementSnapshot;
import com.nori.tc.ui.core.model.UiCommandEventType;
import com.nori.tc.ui.domain.common.model.UiProtocolType;
import com.nori.tc.ui.domain.eqp.UiEqp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class EqpRuntimeSyncCoordinatorTest {

    @Mock
    private EqpManagementRuntimeSupport runtimeSupport;

    @InjectMocks
    private EqpRuntimeSyncCoordinator coordinator;

    @BeforeEach
    void setUp() {
        coordinator = new EqpRuntimeSyncCoordinator(runtimeSupport);
    }

    @Test
    void syncUpdateShouldPublishReleaseAndUpdateWhenRoutePartitionChanged() throws Exception {
        final EqpManagementSnapshot existing = snapshot(2);
        final EqpManagementSnapshot updated = snapshot(0);

        coordinator.syncUpdate(existing, updated, 5_000L);

        verify(runtimeSupport, times(1)).publishPartitionReleaseQuietly(updated, 2);
        verify(runtimeSupport, times(1)).awaitDualSuccess(eq(UiCommandEventType.EQP_UPDATE), eq(updated), eq(5_000L));
    }

    @Test
    void syncUpdateShouldSkipReleaseWhenRoutePartitionUnchanged() throws Exception {
        final EqpManagementSnapshot existing = snapshot(2);
        final EqpManagementSnapshot updated = snapshot(2);

        coordinator.syncUpdate(existing, updated, 5_000L);

        verify(runtimeSupport, never()).publishPartitionReleaseQuietly(any(), anyInt());
        verify(runtimeSupport, times(1)).awaitDualSuccess(eq(UiCommandEventType.EQP_UPDATE), eq(updated), eq(5_000L));
    }

    @Test
    void syncUpdateShouldSkipReleaseWhenOldRoutePartitionIsNull() throws Exception {
        final EqpManagementSnapshot existing = snapshot(null);
        final EqpManagementSnapshot updated = snapshot(0);

        coordinator.syncUpdate(existing, updated, 5_000L);

        verify(runtimeSupport, never()).publishPartitionReleaseQuietly(any(), anyInt());
        verify(runtimeSupport, times(1)).awaitDualSuccess(eq(UiCommandEventType.EQP_UPDATE), eq(updated), eq(5_000L));
    }

    @Test
    void syncUpdateShouldSkipReleaseWhenOldRoutePartitionIsNegative() throws Exception {
        final EqpManagementSnapshot existing = snapshot(-1);
        final EqpManagementSnapshot updated = snapshot(0);

        coordinator.syncUpdate(existing, updated, 5_000L);

        verify(runtimeSupport, never()).publishPartitionReleaseQuietly(any(), anyInt());
        verify(runtimeSupport, times(1)).awaitDualSuccess(eq(UiCommandEventType.EQP_UPDATE), eq(updated), eq(5_000L));
    }

    private static EqpManagementSnapshot snapshot(final Integer routePartition) {
        final OffsetDateTime now = OffsetDateTime.now();
        final UiEqp eqp = new UiEqp(
                1L,
                "EQP_TEST",
                UiProtocolType.SOCKET,
                "PASSIVE",
                false,
                routePartition,
                "0.0.0.0",
                6100,
                1L,
                "v1",
                null,
                null,
                true,
                now,
                now,
                "user",
                "user"
        );
        return new EqpManagementSnapshot(
                eqp,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }
}

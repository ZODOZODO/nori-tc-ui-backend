package com.nori.tc.ui.core.service;

import com.nori.tc.ui.core.deploy.DeployRegisterTargetView;
import com.nori.tc.ui.core.model.PagedResponse;
import com.nori.tc.ui.core.model.UiPageRequest;
import com.nori.tc.ui.core.port.db.DeployRegisterQueryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeployRegisterQueryServiceTest {

    private DeployRegisterQueryPort deployRegisterQueryPort;
    private DeployRegisterQueryService deployRegisterQueryService;

    @BeforeEach
    void setUp() {
        deployRegisterQueryPort = mock(DeployRegisterQueryPort.class);
        deployRegisterQueryService = new DeployRegisterQueryService(deployRegisterQueryPort);
    }

    @Test
    void findRegisterTargetsNormalizesBlankInputsBeforeDelegation() {
        final UiPageRequest pageRequest = UiPageRequest.of(0, 50);
        when(deployRegisterQueryPort.findRegisterTargets(null, 2401L, null, pageRequest))
                .thenReturn(PagedResponse.of(List.<DeployRegisterTargetView>of(), 0, 50, 0L));

        deployRegisterQueryService.findRegisterTargets("   ", 2401L, "   ", pageRequest);

        verify(deployRegisterQueryPort).findRegisterTargets(null, 2401L, null, pageRequest);
    }

    @Test
    void findRegisterTargetsTrimsTextFiltersAndUsesDefaultPageWhenPageIsNull() {
        final UiPageRequest defaultPage = UiPageRequest.defaultPage();
        when(deployRegisterQueryPort.findRegisterTargets("PHOTO", null, "EQP-01", defaultPage))
                .thenReturn(PagedResponse.of(List.<DeployRegisterTargetView>of(), defaultPage.offset(), defaultPage.limit(), 0L));

        deployRegisterQueryService.findRegisterTargets(" PHOTO ", null, " EQP-01 ", null);

        verify(deployRegisterQueryPort).findRegisterTargets("PHOTO", null, "EQP-01", defaultPage);
    }
}

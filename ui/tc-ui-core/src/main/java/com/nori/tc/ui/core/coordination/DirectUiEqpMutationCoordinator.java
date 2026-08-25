package com.nori.tc.ui.core.coordination;

import com.nori.tc.ui.core.coordination.model.UiOperationAcceptance;
import com.nori.tc.ui.core.coordination.model.UiOperationPhase;
import com.nori.tc.ui.core.coordination.model.UiOperationState;
import com.nori.tc.ui.core.eqp.EqpCommandResult;
import com.nori.tc.ui.core.eqp.EqpManagementCommand;
import com.nori.tc.ui.core.service.EqpDirectScenarioService;

import java.util.Objects;

/**
 * 조정 원장을 쓰지 않는 구성의 설비 mutation 전략입니다.
 *
 * <p>기존 동기 direct scenario 경로를 그대로 수행한 뒤 <b>이미 확정된</b> 결과를 접수 응답 형태로
 * 돌려줍니다. HTTP 계약(202 + operationId)은 구성과 무관하게 같아야 Front 가 한 가지 흐름만
 * 구현하면 됩니다 — 이 구성에서는 첫 조회부터 terminal 이 보입니다.</p>
 */
public final class DirectUiEqpMutationCoordinator implements UiEqpMutationCoordinator {

    private final EqpDirectScenarioService directScenarioService;

    /**
     * 즉시 적용 coordinator 를 만듭니다.
     *
     * @param directScenarioService 기존 direct scenario 실행 서비스
     */
    public DirectUiEqpMutationCoordinator(final EqpDirectScenarioService directScenarioService) {
        this.directScenarioService = Objects.requireNonNull(directScenarioService, "directScenarioService is null");
    }

    @Override
    public boolean appliesImmediately() {
        return true;
    }

    @Override
    public UiOperationAcceptance create(
            final EqpManagementCommand.Create command,
            final String idempotencyKey
    ) {
        return toAcceptance(idempotencyKey, directScenarioService.executeCreate(command));
    }

    @Override
    public UiOperationAcceptance update(
            final String eqpId,
            final EqpManagementCommand.Update command,
            final String idempotencyKey
    ) {
        return toAcceptance(idempotencyKey, directScenarioService.executeUpdate(eqpId, command));
    }

    @Override
    public UiOperationAcceptance delete(final String eqpId, final String idempotencyKey) {
        return toAcceptance(idempotencyKey, directScenarioService.executeDelete(eqpId));
    }

    private static UiOperationAcceptance toAcceptance(
            final String idempotencyKey,
            final EqpCommandResult result
    ) {
        return new UiOperationAcceptance(
                idempotencyKey,
                result.success() ? UiOperationState.PASS : UiOperationState.FAIL,
                result.success() ? UiOperationPhase.BARRIER_OPEN : UiOperationPhase.ABORTED,
                true,
                result.errorCode(),
                result.errorMessage());
    }
}

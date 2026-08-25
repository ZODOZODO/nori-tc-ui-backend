package com.nori.tc.ui.core.coordination;

import com.nori.tc.ui.core.coordination.model.UiOperationAcceptance;
import com.nori.tc.ui.core.eqp.EqpManagementCommand;

/**
 * 설비 create/update/delete 의 적용 전략입니다(§13.2 / Plan §8 B24).
 *
 * <p>같은 HTTP 계약(202 + operationId) 뒤에 두 전략을 둡니다 — 조정 구성은 원장에 접수만 하고
 * 실행은 executor 가, 비조정 구성은 기존 동기 경로가 수행합니다. 런타임 코드에 "조정이 켜졌는가"
 * 분기를 두지 않기 위해 구현을 빈 조립으로 고릅니다.</p>
 */
public interface UiEqpMutationCoordinator {

    /**
     * 이 전략이 요청을 받은 자리에서 곧바로 상태를 바꾸는지 반환합니다.
     *
     * @return 즉시 적용 전략이면 true
     */
    boolean appliesImmediately();

    /**
     * 설비 생성을 접수합니다.
     *
     * @param command 생성 명령
     * @param idempotencyKey 요청 idempotency key
     * @return 접수 결과
     */
    UiOperationAcceptance create(EqpManagementCommand.Create command, String idempotencyKey);

    /**
     * 설비 수정을 접수합니다.
     *
     * @param eqpId 대상 설비
     * @param command 수정 명령
     * @param idempotencyKey 요청 idempotency key
     * @return 접수 결과
     */
    UiOperationAcceptance update(String eqpId, EqpManagementCommand.Update command, String idempotencyKey);

    /**
     * 설비 삭제를 접수합니다.
     *
     * @param eqpId 대상 설비
     * @param idempotencyKey 요청 idempotency key
     * @return 접수 결과
     */
    UiOperationAcceptance delete(String eqpId, String idempotencyKey);
}

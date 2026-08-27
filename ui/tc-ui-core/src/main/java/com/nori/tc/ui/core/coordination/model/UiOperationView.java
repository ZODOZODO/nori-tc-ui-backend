package com.nori.tc.ui.core.coordination.model;

import java.util.List;
import java.util.Objects;

/**
 * Redis operation record 의 core 관측 뷰입니다(§13.1).
 *
 * <p>민감 payload 와 내부 stack trace 는 담지 않습니다(§13.3).</p>
 *
 * @param operationId operation 식별자
 * @param executionId 연관 실행 식별자(없으면 null)
 * @param eqpId 대상 설비
 * @param eventType 요청 이벤트 타입
 * @param state terminal 여부
 * @param phase 상세 phase
 * @param resourceKeys 정렬된 영향 리소스 키
 * @param participants 고정된 participant sessionId 집합(고정 전이면 빈 목록)
 * @param manifestSha256 canonical manifest digest
 * @param expectedBaseRevision 기대 base revision
 * @param desiredRevision 발급된 desired revision(미발급이면 null)
 * @param dbFinalized DB finalization proof 존재 여부
 * @param compensatesRevision 보상 대상 revision(없으면 null)
 * @param compensatesOperationId 이 operation 이 되돌리는 원 operation(보상이 아니면 null)
 * @param errorCode terminal error code
 * @param errorMsg terminal error message
 * @param repairReason 수동 복구 사유
 * @param createdAtEpochMs 생성 시각
 * @param updatedAtEpochMs 마지막 전이 시각
 * @param terminalAtEpochMs terminal 확정 시각(비terminal 이면 null)
 */
public record UiOperationView(
        String operationId,
        String executionId,
        String eqpId,
        String eventType,
        UiOperationState state,
        UiOperationPhase phase,
        List<String> resourceKeys,
        List<String> participants,
        String manifestSha256,
        Long expectedBaseRevision,
        Long desiredRevision,
        boolean dbFinalized,
        Long compensatesRevision,
        String compensatesOperationId,
        String errorCode,
        String errorMsg,
        String repairReason,
        Long createdAtEpochMs,
        Long updatedAtEpochMs,
        Long terminalAtEpochMs
) {

    /**
     * 불변 목록으로 정규화합니다.
     */
    public UiOperationView {
        Objects.requireNonNull(operationId, "operationId is null");
        Objects.requireNonNull(state, "state is null");
        Objects.requireNonNull(phase, "phase is null");
        resourceKeys = resourceKeys == null ? List.of() : List.copyOf(resourceKeys);
        participants = participants == null ? List.of() : List.copyOf(participants);
    }

    /**
     * terminal 상태 여부를 반환합니다.
     *
     * @return terminal 이면 true
     */
    public boolean terminal() {
        return state == UiOperationState.PASS
                || state == UiOperationState.FAIL
                || state == UiOperationState.REPAIR_REQUIRED;
    }
}

package com.nori.tc.ui.core.coordination.model;

import java.util.List;
import java.util.Objects;

/**
 * 운영자 관리 조회의 operation 스냅샷입니다(§20.3).
 *
 * <p>일반 조회({@link UiOperationStatusView})가 Front 진행 표시용 최소 필드만 담는 것과 달리,
 * 여기에는 barrier 가 왜 열리지 않았는지를 읽는 데 필요한 원장 내부 상태가 함께 들어갑니다 —
 * 영향 resource 의 committed/open revision, 고정 participant 의 prepare/applied proof,
 * Gateway owner epoch, DB finalization, repair reason 입니다.</p>
 *
 * <p><b>읽기 전용입니다.</b> §20.3 은 강제 PASS / revision 감소 / proof 위조 / barrier 무조건 open
 * 같은 관리 동작을 금지합니다 — 그런 수동 복구는 별도 승인·감사 계약이 필요합니다.</p>
 *
 * <p>manifest 본문은 담지 않습니다. digest 로 동일성 판정이 되고, 본문에는 설비 설정 원문이 실려
 * 조회 권한만으로 노출할 범위를 넘습니다.</p>
 *
 * @param operation operation 원장 스냅샷
 * @param resources 영향 resource 별 현재 head
 * @param participantProofs 고정 participant 별 proof(고정 전이면 빈 목록)
 * @param phaseHistory phase 전이 이력(기록 상한에 닿으면 뒤가 잘릴 수 있음)
 */
public record UiOperationAdminView(
        UiOperationView operation,
        List<UiResourceHeadView> resources,
        List<UiParticipantProofView> participantProofs,
        List<UiPhaseTransitionView> phaseHistory
) {

    /**
     * 필수 값을 검증하고 목록을 불변으로 만듭니다.
     */
    public UiOperationAdminView {
        Objects.requireNonNull(operation, "operation is null");
        resources = resources == null ? List.of() : List.copyOf(resources);
        participantProofs = participantProofs == null ? List.of() : List.copyOf(participantProofs);
        phaseHistory = phaseHistory == null ? List.of() : List.copyOf(phaseHistory);
    }
}

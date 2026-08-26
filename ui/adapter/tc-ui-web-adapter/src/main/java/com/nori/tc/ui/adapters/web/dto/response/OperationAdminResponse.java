package com.nori.tc.ui.adapters.web.dto.response;

import com.nori.tc.ui.core.coordination.model.UiOperationAdminView;

import java.util.List;

/**
 * {@code GET /api/coordination/operations/{operationId}} 관리 조회 응답 DTO 입니다(§20.3).
 *
 * <p>manifest 본문은 담지 않습니다 — digest 로 동일성 판정이 되고, 본문에는 설비 설정 원문이 실려
 * 조회 권한만으로 노출할 범위를 넘습니다.</p>
 *
 * @param operationId operation 식별자
 * @param eqpId 대상 설비
 * @param eventType 요청 이벤트 타입
 * @param status {@code PENDING} / {@code PASS} / {@code FAIL} / {@code REPAIR_REQUIRED}
 * @param phase 상세 phase
 * @param manifestSha256 목표 상태 manifest digest
 * @param expectedBaseRevision 접수 시점의 기준 revision
 * @param desiredRevision 발급된 목표 revision
 * @param dbFinalized DB 확정 완료 여부
 * @param compensatesRevision 보상 대상 revision(보상 operation 이 아니면 null)
 * @param errorCode terminal error code
 * @param errorMsg terminal error message
 * @param repairReason 수동 복구 사유
 * @param createdAtEpochMs 접수 시각
 * @param updatedAtEpochMs 마지막 전이 시각
 * @param terminalAtEpochMs terminal 확정 시각
 * @param resources 영향 resource 별 현재 head
 * @param participantProofs 고정 participant 별 proof
 * @param phaseHistory phase 전이 이력(기록 상한에 닿으면 뒤가 잘릴 수 있음)
 */
public record OperationAdminResponse(
        String operationId,
        String eqpId,
        String eventType,
        String status,
        String phase,
        String manifestSha256,
        Long expectedBaseRevision,
        Long desiredRevision,
        boolean dbFinalized,
        Long compensatesRevision,
        String errorCode,
        String errorMsg,
        String repairReason,
        Long createdAtEpochMs,
        Long updatedAtEpochMs,
        Long terminalAtEpochMs,
        List<ResourceHead> resources,
        List<ParticipantProof> participantProofs,
        List<PhaseTransition> phaseHistory
) {

    /**
     * phase 전이 한 건입니다.
     *
     * @param phase 전이한 phase
     * @param atEpochMs 전이 시각
     */
    public record PhaseTransition(String phase, long atEpochMs) {
    }

    /**
     * 영향 resource 의 현재 장벽 상태입니다.
     *
     * @param resourceKey resource key
     * @param committedRevision 커밋된 revision
     * @param openRevision 개방된 revision — committed 보다 낮으면 장벽이 닫혀 있습니다
     * @param blockedByOperationId 장벽을 닫고 있는 operation
     * @param activeOperationId 리소스를 claim 한 operation
     */
    public record ResourceHead(
            String resourceKey,
            long committedRevision,
            long openRevision,
            String blockedByOperationId,
            String activeOperationId
    ) {
    }

    /**
     * 고정 participant 의 proof 입니다.
     *
     * <p>{@code preparedRevision} 만 있고 {@code appliedRevision} 이 비어 있으면 그 participant 는
     * 준비까지만 마친 상태입니다.</p>
     *
     * @param participantId session 식별자
     * @param preparedRevision 준비를 마친 revision
     * @param preparedDigest 준비 시점 digest
     * @param appliedRevision 적용을 마친 revision
     * @param appliedDigest 적용 시점 digest
     * @param ownerEpoch Gateway participant 의 owner epoch(Business 는 null)
     */
    public record ParticipantProof(
            String participantId,
            Long preparedRevision,
            String preparedDigest,
            Long appliedRevision,
            String appliedDigest,
            Long ownerEpoch
    ) {
    }

    /**
     * core 뷰를 응답 DTO 로 변환합니다.
     *
     * @param view 관리 조회 스냅샷
     * @return 응답 DTO
     */
    public static OperationAdminResponse from(final UiOperationAdminView view) {
        return new OperationAdminResponse(
                view.operation().operationId(),
                view.operation().eqpId(),
                view.operation().eventType(),
                view.operation().state().name(),
                view.operation().phase().name(),
                view.operation().manifestSha256(),
                view.operation().expectedBaseRevision(),
                view.operation().desiredRevision(),
                view.operation().dbFinalized(),
                view.operation().compensatesRevision(),
                view.operation().errorCode(),
                view.operation().errorMsg(),
                view.operation().repairReason(),
                view.operation().createdAtEpochMs(),
                view.operation().updatedAtEpochMs(),
                view.operation().terminalAtEpochMs(),
                view.resources().stream()
                        .map(head -> new ResourceHead(
                                head.resourceKey(),
                                head.committedRevision(),
                                head.openRevision(),
                                head.blockedByOperationId(),
                                head.activeOperationId()))
                        .toList(),
                view.participantProofs().stream()
                        .map(proof -> new ParticipantProof(
                                proof.participantId(),
                                proof.preparedRevision(),
                                proof.preparedDigest(),
                                proof.appliedRevision(),
                                proof.appliedDigest(),
                                proof.ownerEpoch()))
                        .toList(),
                view.phaseHistory().stream()
                        .map(transition -> new PhaseTransition(transition.phase(), transition.atEpochMs()))
                        .toList());
    }
}

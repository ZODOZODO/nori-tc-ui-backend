package com.nori.tc.ui.core.coordination.port;

import com.nori.tc.ui.core.coordination.model.UiOperationPhase;
import com.nori.tc.ui.core.coordination.model.UiOperationState;
import com.nori.tc.ui.core.coordination.model.UiOperationView;
import com.nori.tc.ui.core.coordination.model.UiParticipantProofView;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 공통 operation ledger 포트입니다(§13.1/§7.2).
 *
 * <p>모든 메서드는 원자 연산의 결과 코드를 문자열 그대로 돌려준다 — 코드 해석은 executor 의
 * 책임이며, 어댑터가 임의로 성공/실패로 축약하면 §7.2 의 전이 규칙이 흐려진다.</p>
 */
public interface UiOperationLedgerPort {

    /**
     * operation 생성 결과입니다.
     *
     * @param code {@code CREATED} / {@code EXISTING} / {@code CONFLICT_DIGEST}
     * @param state 기존 operation 의 상태({@code EXISTING} 일 때)
     * @param phase 기존 operation 의 phase({@code EXISTING} 일 때)
     * @param errorCode 기존 terminal error code
     * @param errorMsg 기존 terminal error message
     */
    record CreateResult(String code, UiOperationState state, UiOperationPhase phase, String errorCode, String errorMsg) {

        /** 신규 생성 코드입니다. */
        public static final String CREATED = "CREATED";

        /** 같은 key+digest 재요청 코드입니다. */
        public static final String EXISTING = "EXISTING";

        /** 같은 key 다른 digest 코드입니다(§13.2 → 409). */
        public static final String CONFLICT_DIGEST = "CONFLICT_DIGEST";
    }

    /**
     * operation 을 원자 생성하거나 기존 상태를 반환합니다.
     *
     * @param operationId operation 식별자
     * @param manifestSha256 canonical manifest digest
     * @param manifestJson 불변 canonical manifest
     * @param extraFields 초기 hash field
     * @param nowEpochMs 현재 시각
     * @return 생성 결과
     */
    CreateResult createOperation(
            String operationId,
            String manifestSha256,
            String manifestJson,
            Map<String, String> extraFields,
            long nowEpochMs);

    /**
     * phase/state 를 전이합니다.
     *
     * @param operationId operation 식별자
     * @param expectedPhases 허용 현재 phase 집합
     * @param newPhase 새 phase
     * @param newState 새 state(변경 없으면 null)
     * @param executorSessionId executor session(검증 불요면 null)
     * @param executorEpoch executor epoch(검증 불요면 null)
     * @param errorCode terminal error code
     * @param errorMsg terminal error message
     * @param repairReason 수동 복구 사유
     * @param nowEpochMs 현재 시각
     * @return 결과 코드
     */
    String transition(
            String operationId,
            Set<UiOperationPhase> expectedPhases,
            UiOperationPhase newPhase,
            UiOperationState newState,
            String executorSessionId,
            Long executorEpoch,
            String errorCode,
            String errorMsg,
            String repairReason,
            long nowEpochMs);

    /**
     * participant 집합을 고정합니다.
     *
     * @param operationId operation 식별자
     * @param participantIds 고정할 sessionId 목록
     * @param force 커밋 전 재고정 여부
     * @param nowEpochMs 현재 시각
     * @return 결과 코드
     */
    String freezeParticipants(String operationId, List<String> participantIds, boolean force, long nowEpochMs);

    /**
     * 만료된 participant 를 고정 집합에서 제외합니다(§9.2 커밋 후 규칙).
     *
     * @param operationId operation 식별자
     * @param serviceName 대상 서비스명
     * @param expiredSessionId 만료 session
     * @param nowEpochMs 현재 시각
     * @return 결과 코드
     */
    String excludeExpiredParticipant(String operationId, String serviceName, String expiredSessionId, long nowEpochMs);

    /**
     * 전 participant 의 prepared proof 확인 후 리소스 장벽을 닫습니다.
     *
     * @param operationId operation 식별자
     * @param resourceHeadKeys 대상 head key
     * @param nowEpochMs 현재 시각
     * @return 결과 코드
     */
    String closeBarrier(String operationId, List<String> resourceHeadKeys, long nowEpochMs);

    /**
     * committed head 를 새 desired revision 으로 전환합니다.
     *
     * @param operationId operation 식별자
     * @param resourceHeadKeys 대상 head key
     * @param expectedBaseRevisions head 별 expected base revision
     * @param nowEpochMs 현재 시각
     * @return 결과 코드
     */
    String commitHead(
            String operationId, List<String> resourceHeadKeys, List<Long> expectedBaseRevisions, long nowEpochMs);

    /**
     * 전 participant 의 applied proof 잔여 수를 확인합니다.
     *
     * @param operationId operation 식별자
     * @param advanceToFinalizing 잔여 0 이면 FINALIZING 으로 전이할지 여부
     * @param nowEpochMs 현재 시각
     * @return 잔여 participant 수(확인 불가면 -1)
     */
    int checkApplied(String operationId, boolean advanceToFinalizing, long nowEpochMs);

    /**
     * DB finalization proof 를 기록합니다.
     *
     * @param operationId operation 식별자
     * @param executorSessionId executor session
     * @param executorEpoch executor epoch
     * @param finalizedRevision 확정된 revision
     * @param nowEpochMs 현재 시각
     * @return 결과 코드
     */
    String recordDbFinalized(
            String operationId, String executorSessionId, long executorEpoch, long finalizedRevision, long nowEpochMs);

    /**
     * 장벽을 열고 terminal PASS 를 원자 기록합니다.
     *
     * @param operationId operation 식별자
     * @param resourceHeadKeys 대상 head key
     * @param nowEpochMs 현재 시각
     * @return 결과 코드
     */
    String openBarrierAndPass(String operationId, List<String> resourceHeadKeys, long nowEpochMs);

    /**
     * commit 전 실패를 abort 로 확정하고 리소스 claim 을 해제합니다.
     *
     * @param operationId operation 식별자
     * @param resourceHeadKeys 대상 head key
     * @param errorCode error code
     * @param errorMsg error message
     * @param nowEpochMs 현재 시각
     * @return 결과 코드
     */
    String abort(String operationId, List<String> resourceHeadKeys, String errorCode, String errorMsg, long nowEpochMs);

    /**
     * participant proof 를 기록합니다(§13.5 reply ingress).
     *
     * @param operationId operation 식별자
     * @param participantId participant sessionId
     * @param applied {@code true} 면 applied proof, {@code false} 면 prepared proof
     * @param revision proof revision
     * @param digest proof digest
     * @param ownerLeaseKey owner epoch 검증 대상 lease key(불요면 null)
     * @param ownerEpoch owner epoch(불요면 null)
     * @param proofJson 부가 proof JSON
     * @param nowEpochMs 현재 시각
     * @return 결과 코드
     */
    String ackProof(
            String operationId,
            String participantId,
            boolean applied,
            long revision,
            String digest,
            String ownerLeaseKey,
            Long ownerEpoch,
            String proofJson,
            long nowEpochMs);

    /**
     * operation 을 조회합니다.
     *
     * @param operationId operation 식별자
     * @return operation 뷰
     */
    Optional<UiOperationView> readOperation(String operationId);

    /**
     * 고정된 participant 별 prepare/applied proof 를 조회합니다(§20.3).
     *
     * <p>participant 가 고정되기 전이면 빈 목록입니다.</p>
     *
     * @param operationId operation 식별자
     * @return participant proof 목록
     */
    List<UiParticipantProofView> readParticipantProofs(String operationId);

    /**
     * 불변 manifest 를 조회합니다.
     *
     * @param operationId operation 식별자
     * @return manifest JSON
     */
    Optional<String> readManifest(String operationId);

    /**
     * 미종결 operation 식별자를 조회합니다.
     *
     * @param limit 조회 상한
     * @return operationId 목록
     */
    List<String> pendingOperationIds(long limit);
}

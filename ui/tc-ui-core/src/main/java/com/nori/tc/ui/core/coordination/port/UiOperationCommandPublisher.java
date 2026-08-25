package com.nori.tc.ui.core.coordination.port;

import com.nori.tc.ui.core.coordination.model.UiOperationManifestView;

/**
 * operation protocol 단계별 명령 발행 포트입니다(§10.1 v2 metadata).
 *
 * <p>Business 는 원장 polling 으로 pending operation 을 스스로 발견하므로 명령이 없어도 진행합니다.
 * 명령은 <b>지연을 줄이는 신호</b>이자 Gateway 참여의 유일한 경로입니다 — Gateway 는 자기 reconciler 로
 * operation 을 발견하지 않고 UI 명령을 받아 처리한 뒤 reply proof 로 응답합니다.</p>
 *
 * <p>같은 단계를 두 번 발행해도 안전해야 합니다. Gateway 의 result ledger 가 같은 traceId+digest 를
 * 최초 결과로 재현하므로(§12.6), APPLY 단계 재발행은 부작용 없이 같은 proof 를 다시 만듭니다.</p>
 */
public interface UiOperationCommandPublisher {

    /**
     * prepare 단계 명령을 발행합니다.
     *
     * @param operationId operation 식별자
     * @param manifest 원장에 고정된 manifest 뷰
     * @param desiredRevision 발급된 desired revision
     * @param manifestSha256 manifest digest
     * @param deadlineAt 관찰 deadline(ISO-8601)
     */
    void publishPrepare(
            String operationId,
            UiOperationManifestView manifest,
            long desiredRevision,
            String manifestSha256,
            String deadlineAt);

    /**
     * apply 단계 명령을 발행합니다.
     *
     * @param operationId operation 식별자
     * @param manifest 원장에 고정된 manifest 뷰
     * @param desiredRevision 발급된 desired revision
     * @param manifestSha256 manifest digest
     * @param deadlineAt 관찰 deadline(ISO-8601)
     */
    void publishApply(
            String operationId,
            UiOperationManifestView manifest,
            long desiredRevision,
            String manifestSha256,
            String deadlineAt);
}

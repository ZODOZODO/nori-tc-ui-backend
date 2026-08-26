package com.nori.tc.ui.core.coordination;

import com.nori.tc.ui.core.coordination.model.UiOperationAcceptance;
import com.nori.tc.ui.core.coordination.model.UiOperationRequest;
import com.nori.tc.ui.core.coordination.port.UiOperationLedgerPort;
import com.nori.tc.ui.core.coordination.port.UiResourceHeadPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * HTTP mutation 접수를 조정 원장으로 확정합니다(§13.2/§18.1).
 *
 * <p>접수는 두 원자 연산으로 끝납니다.</p>
 * <ol>
 *   <li>{@code createOperation} — idempotency claim + manifest digest 충돌 검사</li>
 *   <li>{@code issueCandidate} — 전역 revision 발급 + 리소스 claim + base revision CAS</li>
 * </ol>
 *
 * <p>두 번째가 실패하면 방금 만든 operation 을 abort 합니다 — 남겨 두면 그 idempotency key 로는
 * 영원히 재시도할 수 없습니다(같은 key 가 비terminal operation 을 가리키므로).</p>
 *
 * <p>Kafka 발행과 DB/runtime 변경은 이 단계에서 하지 않습니다(§13.2) — 실행은 executor 의 몫입니다.</p>
 */
public final class UiOperationAcceptanceService {

    private static final Logger log = LoggerFactory.getLogger(UiOperationAcceptanceService.class);

    private final UiOperationLedgerPort ledgerPort;
    private final UiResourceHeadPort resourceHeadPort;
    private final UiOperationManifestFactory manifestFactory;

    /**
     * 접수 서비스를 만듭니다.
     *
     * @param ledgerPort operation ledger 포트
     * @param resourceHeadPort 리소스 장벽 포트
     * @param manifestFactory manifest 팩토리
     */
    public UiOperationAcceptanceService(
            final UiOperationLedgerPort ledgerPort,
            final UiResourceHeadPort resourceHeadPort,
            final UiOperationManifestFactory manifestFactory
    ) {
        this.ledgerPort = Objects.requireNonNull(ledgerPort, "ledgerPort is null");
        this.resourceHeadPort = Objects.requireNonNull(resourceHeadPort, "resourceHeadPort is null");
        this.manifestFactory = Objects.requireNonNull(manifestFactory, "manifestFactory is null");
    }

    /**
     * 요청을 접수합니다.
     *
     * @param request mutation 요청
     * @param nowEpochMs 현재 시각
     * @return 접수 결과
     * @throws UiOperationConflictException idempotency/리소스/revision 충돌(409)
     * @throws UiCoordinationUnavailableException 조정 원장 접근 불가(503)
     */
    public UiOperationAcceptance accept(final UiOperationRequest request, final long nowEpochMs) {
        Objects.requireNonNull(request, "request is null");

        final String operationId = manifestFactory.operationIdOf(request.idempotencyKey());
        final UiOperationManifestFactory.Manifest manifest = manifestFactory.build(request);

        final UiOperationLedgerPort.CreateResult created = ledgerPort.createOperation(
                operationId, manifest.sha256(), manifest.canonicalJson(), extraFields(request), nowEpochMs);

        switch (created.code()) {
            case UiOperationLedgerPort.CreateResult.CONFLICT_DIGEST -> throw new UiOperationConflictException(
                    UiOperationConflictException.IDEMPOTENCY_CONFLICT,
                    "같은 Idempotency-Key 에 다른 요청 내용이 도착했습니다. operationId=" + operationId,
                    operationId);
            case UiOperationLedgerPort.CreateResult.EXISTING -> {
                log.info("UI_OPERATION_REPLAY. operationId={}, state={}, phase={}",
                        operationId, created.state(), created.phase());
                return new UiOperationAcceptance(
                        operationId, created.state(), created.phase(), false, created.errorCode(), created.errorMsg());
            }
            default -> {
                // 신규 생성 — 아래에서 candidate 를 발급한다.
            }
        }

        issueCandidateOrAbort(request, operationId, nowEpochMs);
        log.info("UI_OPERATION_ACCEPTED. operationId={}, eventType={}, eqpId={}, resourceKeys={}",
                operationId, request.eventType(), request.eqpId(), request.resourceKeys());
        return UiOperationAcceptance.accepted(operationId);
    }

    private void issueCandidateOrAbort(
            final UiOperationRequest request,
            final String operationId,
            final long nowEpochMs
    ) {
        final List<UiResourceHeadPort.Candidate> candidates = new ArrayList<>();
        for (final String resourceKey : request.resourceKeys()) {
            candidates.add(new UiResourceHeadPort.Candidate(
                    UiResourceKeyPlanner.RESOURCE_TYPE_EQUIPMENT,
                    UiResourceKeyPlanner.eqpIdOf(resourceKey),
                    request.expectedBaseRevisions().get(resourceKey),
                    manifestFactory.descriptorJson(request, resourceKey)));
        }

        final UiResourceHeadPort.IssueResult issued =
                resourceHeadPort.issueCandidate(operationId, candidates, nowEpochMs);
        if (issued.code() == UiResourceHeadPort.IssueCode.OK
                || issued.code() == UiResourceHeadPort.IssueCode.ALREADY_ISSUED) {
            return;
        }

        abortQuietly(operationId, request, issued, nowEpochMs);
        throw switch (issued.code()) {
            case RESOURCE_BUSY -> new UiOperationConflictException(
                    UiOperationConflictException.RESOURCE_BUSY,
                    "같은 설비에 대해 다른 작업이 진행 중입니다. conflictingOperationId=" + issued.conflictingOperationId(),
                    issued.conflictingOperationId());
            case EQUIPMENT_EXECUTING -> new UiOperationConflictException(
                    UiOperationConflictException.EQUIPMENT_EXECUTING,
                    "이 설비에 대한 배포가 진행 중입니다. 완료 후 다시 시도하세요. ownerToken="
                            + issued.conflictingOperationId(),
                    issued.conflictingOperationId());
            case RESOURCE_BLOCKED -> new UiOperationConflictException(
                    UiOperationConflictException.RESOURCE_BLOCKED,
                    "미종결 작업이 이 설비의 처리를 차단하고 있습니다. blockedByOperationId="
                            + issued.conflictingOperationId(),
                    issued.conflictingOperationId());
            default -> new UiOperationConflictException(
                    UiOperationConflictException.REVISION_CONFLICT,
                    "요청이 기준으로 삼은 상태가 이미 바뀌었습니다. operationId=" + operationId,
                    operationId);
        };
    }

    /**
     * candidate 발급 실패 후 방금 만든 operation 을 정리합니다.
     *
     * <p>정리 실패는 삼키되 로그로 드러냅니다 — 정리 실패 때문에 원래 충돌(409)을 500 으로 바꾸면
     * 호출자가 원인을 알 수 없습니다. 남은 operation 은 reconciler 가 다시 정리합니다.</p>
     */
    private void abortQuietly(
            final String operationId,
            final UiOperationRequest request,
            final UiResourceHeadPort.IssueResult issued,
            final long nowEpochMs
    ) {
        try {
            ledgerPort.abort(
                    operationId,
                    headKeys(request),
                    issued.code().name(),
                    "candidate 발급 실패로 접수를 취소했습니다.",
                    nowEpochMs);
        } catch (RuntimeException ex) {
            log.error("UI_OPERATION_ABORT_AFTER_ISSUE_FAILED. operationId={}, issueCode={}",
                    operationId, issued.code(), ex);
        }
    }

    private List<String> headKeys(final UiOperationRequest request) {
        return request.resourceKeys().stream().map(resourceHeadPort::headKeyOf).toList();
    }

    private static Map<String, String> extraFields(final UiOperationRequest request) {
        final Map<String, String> fields = new LinkedHashMap<>();
        fields.put("idempotencyKey", request.idempotencyKey());
        fields.put("eventType", request.eventType());
        fields.put("eqpId", request.eqpId());
        fields.put("resourceKeys", String.join(",", request.resourceKeys()));
        if (request.executionId() != null && !request.executionId().isBlank()) {
            fields.put("executionId", request.executionId());
        }
        final Long baseRevision = request.expectedBaseRevisions().get(request.resourceKeys().get(0));
        if (baseRevision != null) {
            fields.put("expectedBaseRevision", String.valueOf(baseRevision));
        }
        return fields;
    }
}

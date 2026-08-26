package com.nori.tc.ui.core.coordination;

import com.nori.tc.ui.core.coordination.model.UiOperationManifestView;
import com.nori.tc.ui.core.coordination.model.UiOperationView;
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
 * 커밋 후 실패를 되돌리는 보상 operation 을 발급합니다(§13.8).
 *
 * <p>보상은 원 operation 의 <b>이전 상태 descriptor</b> 를 목표로 삼는 새 operation 이며,
 * {@code compensatesRevision} 으로 무엇을 되돌리는지 선언합니다. 원 operation 의 고정 participant 를
 * 그대로 요구하지 않습니다 — 그 집합의 일부가 죽은 상황이 보상의 주된 발생 조건이기 때문입니다.
 * 새 operation 이므로 §9.2 규칙으로 당시 활성 session 을 다시 고정합니다.</p>
 *
 * <p><b>발급하지 못하면 조용히 실패합니다.</b> 호출자는 이미 {@code REPAIR_REQUIRED} 를 확정한
 * 뒤이므로, 발급 실패는 "자동 복구는 못 하고 수동 복구로 남는다" 는 뜻입니다. 여기서 예외를 던지면
 * 이미 확정된 수동 복구 신호까지 함께 잃습니다.</p>
 */
public final class UiCompensationIssuer {

    private static final Logger log = LoggerFactory.getLogger(UiCompensationIssuer.class);

    /** 보상 operation 의 idempotency key 접두사입니다 — 같은 실패는 언제 재시도해도 같은 보상을 가리킵니다. */
    private static final String COMPENSATION_KEY_PREFIX = "compensate:";

    private final UiOperationLedgerPort ledgerPort;
    private final UiResourceHeadPort resourceHeadPort;
    private final UiOperationManifestFactory manifestFactory;
    private final UiOperationManifestReader manifestReader;

    /**
     * 발급기를 만듭니다.
     *
     * @param ledgerPort operation 원장 포트
     * @param resourceHeadPort 리소스 head 포트
     * @param manifestFactory manifest 팩토리
     * @param manifestReader manifest 해석기
     */
    public UiCompensationIssuer(
            final UiOperationLedgerPort ledgerPort,
            final UiResourceHeadPort resourceHeadPort,
            final UiOperationManifestFactory manifestFactory,
            final UiOperationManifestReader manifestReader
    ) {
        this.ledgerPort = Objects.requireNonNull(ledgerPort, "ledgerPort is null");
        this.resourceHeadPort = Objects.requireNonNull(resourceHeadPort, "resourceHeadPort is null");
        this.manifestFactory = Objects.requireNonNull(manifestFactory, "manifestFactory is null");
        this.manifestReader = Objects.requireNonNull(manifestReader, "manifestReader is null");
    }

    /**
     * 실패한 operation 에 대한 보상 operation 을 발급합니다.
     *
     * <p>호출 전에 대상 operation 이 {@code REPAIR_REQUIRED} 로 확정돼 있어야 합니다 — 리소스 claim
     * 인계 조건을 공통 원장이 그 상태로 검증합니다.</p>
     *
     * @param failed 되돌릴 operation
     * @param nowEpochMs 현재 시각
     * @return 발급된 보상 operationId(발급하지 않았으면 null)
     */
    public String issueFor(final UiOperationView failed, final long nowEpochMs) {
        Objects.requireNonNull(failed, "failed is null");

        if (failed.compensatesRevision() != null) {
            /*
             * 보상의 보상은 발급하지 않는다. 되돌리기가 또 실패했다면 두 상태 중 어느 쪽이 실제로
             * 올라가 있는지 확정할 수 없고, 자동으로 한 번 더 시도하면 그 불확실을 키운다.
             */
            log.error("UI_COMPENSATION_SKIPPED_NESTED. operationId={}, compensatesRevision={}",
                    failed.operationId(), failed.compensatesRevision());
            return null;
        }
        if (failed.desiredRevision() == null) {
            // candidate 를 발급하기 전에 실패했다면 커밋된 것이 없어 되돌릴 대상도 없다.
            return null;
        }

        final String compensationId =
                manifestFactory.operationIdOf(COMPENSATION_KEY_PREFIX + failed.operationId());
        try {
            return issue(failed, compensationId, nowEpochMs);
        } catch (RuntimeException ex) {
            log.error("UI_COMPENSATION_ISSUE_FAILED. operationId={}, compensationId={}",
                    failed.operationId(), compensationId, ex);
            return null;
        }
    }

    private String issue(final UiOperationView failed, final String compensationId, final long nowEpochMs) {
        final String originalManifestJson = ledgerPort.readManifest(failed.operationId())
                .orElseThrow(() -> new IllegalStateException(
                        "manifest 가 없어 보상을 만들 수 없습니다. operationId=" + failed.operationId()));

        final Map<String, Long> expectedBaseRevisions = new LinkedHashMap<>();
        for (final String resourceKey : failed.resourceKeys()) {
            expectedBaseRevisions.put(resourceKey, resourceHeadPort.readHead(
                    UiResourceKeyPlanner.RESOURCE_TYPE_EQUIPMENT,
                    UiResourceKeyPlanner.eqpIdOf(resourceKey)).committedRevision());
        }

        final UiOperationManifestFactory.Manifest manifest = manifestFactory.buildCompensation(
                originalManifestJson, failed.desiredRevision(), expectedBaseRevisions);

        final UiOperationLedgerPort.CreateResult created = ledgerPort.createOperation(
                compensationId,
                manifest.sha256(),
                manifest.canonicalJson(),
                extraFields(failed, compensationId, expectedBaseRevisions),
                nowEpochMs);
        if (UiOperationLedgerPort.CreateResult.CONFLICT_DIGEST.equals(created.code())) {
            log.error("UI_COMPENSATION_DIGEST_CONFLICT. operationId={}, compensationId={}",
                    failed.operationId(), compensationId);
            return null;
        }

        final List<UiResourceHeadPort.Candidate> candidates =
                candidates(manifest.canonicalJson(), failed.resourceKeys(), expectedBaseRevisions);
        final UiResourceHeadPort.IssueResult issued = resourceHeadPort.issueCompensationCandidate(
                compensationId, failed.operationId(), candidates, nowEpochMs);
        if (issued.code() != UiResourceHeadPort.IssueCode.OK
                && issued.code() != UiResourceHeadPort.IssueCode.ALREADY_ISSUED) {
            log.error("UI_COMPENSATION_CANDIDATE_REJECTED. operationId={}, compensationId={}, code={}, conflict={}",
                    failed.operationId(), compensationId, issued.code(), issued.conflictingOperationId());
            ledgerPort.abort(
                    compensationId,
                    failed.resourceKeys().stream().map(resourceHeadPort::headKeyOf).toList(),
                    issued.code().name(),
                    "보상 candidate 발급에 실패했습니다.",
                    nowEpochMs);
            return null;
        }

        log.warn("UI_COMPENSATION_ISSUED. operationId={}, compensationId={}, compensatesRevision={}",
                failed.operationId(), compensationId, failed.desiredRevision());
        return compensationId;
    }

    /**
     * 보상 manifest 에서 리소스별 candidate descriptor 를 꺼냅니다.
     *
     * <p>manifest 에 실린 것과 <b>같은 문자열</b>이어야 participant 가 검증한 descriptor 와 digest 로
     * 고정된 manifest 가 일치합니다.</p>
     */
    private List<UiResourceHeadPort.Candidate> candidates(
            final String compensationManifestJson,
            final List<String> resourceKeys,
            final Map<String, Long> expectedBaseRevisions
    ) {
        final UiOperationManifestView view = manifestReader.read(compensationManifestJson);
        final List<UiResourceHeadPort.Candidate> candidates = new ArrayList<>(resourceKeys.size());
        for (final String resourceKey : resourceKeys) {
            candidates.add(new UiResourceHeadPort.Candidate(
                    UiResourceKeyPlanner.RESOURCE_TYPE_EQUIPMENT,
                    UiResourceKeyPlanner.eqpIdOf(resourceKey),
                    expectedBaseRevisions.get(resourceKey),
                    view.descriptorJsonByResourceKey().get(resourceKey)));
        }
        return candidates;
    }

    private static Map<String, String> extraFields(
            final UiOperationView failed,
            final String compensationId,
            final Map<String, Long> expectedBaseRevisions
    ) {
        final Map<String, String> fields = new LinkedHashMap<>();
        fields.put("idempotencyKey", COMPENSATION_KEY_PREFIX + failed.operationId());
        fields.put("eventType", failed.eventType());
        fields.put("eqpId", failed.eqpId());
        fields.put("resourceKeys", String.join(",", failed.resourceKeys()));
        fields.put("compensatesRevision", String.valueOf(failed.desiredRevision()));
        fields.put("compensatesOperationId", failed.operationId());
        final Long baseRevision = expectedBaseRevisions.get(failed.resourceKeys().get(0));
        if (baseRevision != null) {
            fields.put("expectedBaseRevision", String.valueOf(baseRevision));
        }
        log.debug("UI_COMPENSATION_FIELDS. compensationId={}, fields={}", compensationId, fields.keySet());
        return fields;
    }
}

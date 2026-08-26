package com.nori.tc.ui.core.coordination;

import com.nori.tc.ui.core.coordination.model.UiOperationAdminView;
import com.nori.tc.ui.core.coordination.model.UiOperationView;
import com.nori.tc.ui.core.coordination.model.UiResourceHeadView;
import com.nori.tc.ui.core.coordination.port.UiOperationLedgerPort;
import com.nori.tc.ui.core.coordination.port.UiResourceHeadPort;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 운영자 관리 조회를 조립합니다(§20.3).
 *
 * <p>operation 원장 + 영향 resource head + participant proof 를 한 번에 읽어 barrier 가 열리지 않은
 * 이유를 한 화면에서 읽게 합니다. 세 값이 서로 다른 key 에 있어, 개별 조회로는 운영자가 손으로
 * 이어 붙여야 했습니다.</p>
 *
 * <p><b>읽기 전용입니다.</b> §20.3 은 강제 PASS / revision 감소 / proof 위조 / barrier 무조건 open 을
 * 금지하므로 이 서비스에는 쓰기 경로가 없습니다.</p>
 *
 * <p>스냅샷 일관성은 보장하지 않습니다 — 조회 사이에 원장이 움직이면 서로 다른 시점의 값이 섞일 수
 * 있습니다. 진행 중 operation 을 관측하는 용도이므로 원자 스냅샷을 위해 리소스를 잠그지 않습니다.
 * 그 잠금은 실제 처리 경로를 막습니다.</p>
 */
public final class UiOperationAdminQueryService {

    private final UiOperationLedgerPort operationLedger;
    private final UiResourceHeadPort resourceHeads;

    /**
     * 조회 서비스를 만듭니다.
     *
     * @param operationLedger operation 원장 포트
     * @param resourceHeads 리소스 head 포트
     */
    public UiOperationAdminQueryService(
            final UiOperationLedgerPort operationLedger,
            final UiResourceHeadPort resourceHeads
    ) {
        this.operationLedger = Objects.requireNonNull(operationLedger, "operationLedger is null");
        this.resourceHeads = Objects.requireNonNull(resourceHeads, "resourceHeads is null");
    }

    /**
     * operation 의 관리 조회 스냅샷을 조립합니다.
     *
     * @param operationId 대상 operation
     * @return 스냅샷(operation 이 없으면 empty)
     * @throws UiCoordinationUnavailableException 조정 원장 조회 불가(503 — 404 로 환원하지 않습니다)
     */
    public Optional<UiOperationAdminView> find(final String operationId) {
        Objects.requireNonNull(operationId, "operationId is null");

        final Optional<UiOperationView> operation = operationLedger.readOperation(operationId);
        if (operation.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(new UiOperationAdminView(
                operation.get(),
                readHeads(operation.get().resourceKeys()),
                operationLedger.readParticipantProofs(operationId)));
    }

    /**
     * resourceKey 목록을 현재 head 로 바꿉니다.
     *
     * <p>{@code resourceType:resourceId} 형식이 아닌 항목은 건너뜁니다 — 관리 조회가 형식 하나 때문에
     * 전체 실패하면 정작 그 이상 상태를 볼 수 없습니다.</p>
     */
    private List<UiResourceHeadView> readHeads(final List<String> resourceKeys) {
        final List<UiResourceHeadView> heads = new ArrayList<>(resourceKeys.size());
        for (final String resourceKey : resourceKeys) {
            final int separator = resourceKey.indexOf(':');
            if (separator <= 0 || separator == resourceKey.length() - 1) {
                continue;
            }
            heads.add(resourceHeads.readHead(
                    resourceKey.substring(0, separator), resourceKey.substring(separator + 1)));
        }
        return heads;
    }
}

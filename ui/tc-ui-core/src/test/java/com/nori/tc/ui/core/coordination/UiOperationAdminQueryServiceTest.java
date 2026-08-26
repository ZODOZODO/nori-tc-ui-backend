package com.nori.tc.ui.core.coordination;

import com.nori.tc.ui.core.coordination.model.UiOperationAdminView;
import com.nori.tc.ui.core.coordination.model.UiOperationPhase;
import com.nori.tc.ui.core.coordination.model.UiOperationState;
import com.nori.tc.ui.core.coordination.model.UiOperationView;
import com.nori.tc.ui.core.coordination.model.UiParticipantProofView;
import com.nori.tc.ui.core.coordination.model.UiResourceHeadView;
import com.nori.tc.ui.core.coordination.port.UiOperationLedgerPort;
import com.nori.tc.ui.core.coordination.port.UiResourceHeadPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * §20.3 관리 조회 조립 계약.
 *
 * <p>operation / resource head / participant proof 는 서로 다른 key 에 있다. 운영자가 barrier 가
 * 열리지 않은 이유를 읽으려면 셋이 한 응답에 있어야 하고, 특히 <b>resourceKey 하나가 형식에서
 * 어긋나도 나머지를 못 보게 되면 안 된다</b> — 그런 이상 상태야말로 이 API 를 여는 이유다.</p>
 */
class UiOperationAdminQueryServiceTest {

    private static final String OPERATION_ID = "op-1";

    @Test
    @DisplayName("operation / resource head / participant proof 를 한 스냅샷으로 합친다")
    void joinsOperationResourcesAndProofs() {
        final RecordingResourceHeads heads = new RecordingResourceHeads();
        final UiOperationAdminQueryService service = new UiOperationAdminQueryService(
                stubLedger(operationWith(List.of("equipment:CLN_001", "equipment:CLN_002"))), heads);

        final UiOperationAdminView view = service.find(OPERATION_ID).orElseThrow();

        assertEquals(OPERATION_ID, view.operation().operationId());
        assertEquals(List.of("equipment/CLN_001", "equipment/CLN_002"), heads.requested);
        assertEquals(2, view.resources().size());
        assertEquals(1, view.participantProofs().size());
        assertEquals(3L, view.participantProofs().getFirst().appliedRevision());
    }

    @Test
    @DisplayName("형식이 어긋난 resourceKey 는 건너뛰고 나머지는 그대로 조회한다")
    void malformedResourceKeyDoesNotFailTheWholeQuery() {
        final RecordingResourceHeads heads = new RecordingResourceHeads();
        final UiOperationAdminQueryService service = new UiOperationAdminQueryService(
                stubLedger(operationWith(List.of("no-separator", "equipment:CLN_003", "trailing:"))), heads);

        final UiOperationAdminView view = service.find(OPERATION_ID).orElseThrow();

        assertEquals(List.of("equipment/CLN_003"), heads.requested);
        assertEquals(1, view.resources().size());
    }

    @Test
    @DisplayName("없는 operation 은 empty 다 — 예외로 만들지 않는다")
    void missingOperationIsEmpty() {
        final UiOperationAdminQueryService service =
                new UiOperationAdminQueryService(stubLedger(null), new RecordingResourceHeads());

        assertTrue(service.find("op-none").isEmpty());
    }

    private static UiOperationView operationWith(final List<String> resourceKeys) {
        return new UiOperationView(
                OPERATION_ID, "exec-1", "CLN_001", "EQP_UPDATE",
                UiOperationState.PASS, UiOperationPhase.BARRIER_OPEN,
                resourceKeys, List.of("tc-business:pod-1:sess-1"),
                "digest", 0L, 3L, true, null, null, null, null, 1L, 2L, 3L);
    }

    /**
     * 읽기 두 메서드만 stub 한 원장 포트를 만듭니다.
     *
     * <p>관리 조회는 원장을 쓰지 않으므로 나머지 연산은 stub 하지 않습니다 — 호출되면 Mockito 기본값
     * 대신 테스트가 의미 없는 통과를 하게 되므로, 쓰기가 섞이면 다른 단언에서 드러납니다.</p>
     */
    private static UiOperationLedgerPort stubLedger(final UiOperationView operation) {
        final UiOperationLedgerPort ledger = mock(UiOperationLedgerPort.class);
        when(ledger.readOperation(anyString())).thenReturn(Optional.ofNullable(operation));
        when(ledger.readParticipantProofs(anyString())).thenReturn(List.of(
                new UiParticipantProofView("tc-business:pod-1:sess-1", 3L, "digest", 3L, "digest", null)));
        return ledger;
    }

    /** 어떤 (type, id) 로 조회됐는지 기록한다 — key 분해가 어긋나면 여기서 드러난다. */
    private static final class RecordingResourceHeads implements UiResourceHeadPort {

        private final List<String> requested = new ArrayList<>();

        @Override

        public IssueResult issueCompensationCandidate(

                final String operationId, final String compensatesOperationId,

                final List<Candidate> candidates, final long nowEpochMs) {

            throw new UnsupportedOperationException("관리 조회는 보상 candidate 도 발급하지 않는다");

        }


        @Override
        public IssueResult issueCandidate(
                final String operationId, final List<Candidate> candidates, final long nowEpochMs) {
            throw new UnsupportedOperationException("관리 조회는 candidate 를 발급하지 않는다");
        }

        @Override
        public UiResourceHeadView readHead(final String resourceType, final String resourceId) {
            requested.add(resourceType + "/" + resourceId);
            return new UiResourceHeadView(resourceType + ":" + resourceId, 3L, 3L, null, null);
        }

        @Override
        public Optional<String> readDescriptor(
                final String resourceType, final String resourceId, final long revision) {
            return Optional.empty();
        }

        @Override
        public String headKeyOf(final String resourceKey) {
            return resourceKey;
        }
    }
}

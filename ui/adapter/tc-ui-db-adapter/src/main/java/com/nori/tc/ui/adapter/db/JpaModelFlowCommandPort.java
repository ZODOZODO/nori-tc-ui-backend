package com.nori.tc.ui.adapter.db;

import com.nori.tc.db.core.exception.DbDuplicateKeyException;
import com.nori.tc.db.core.model.store.TcModelMessageFlowDcopItemStore;
import com.nori.tc.db.core.model.store.TcModelWorkflowStore;
import com.nori.tc.db.core.model.upsert.UpsertTcModelMessageFlowDcopItem;
import com.nori.tc.db.core.model.upsert.UpsertTcModelWorkflow;
import com.nori.tc.db.domain.model.TcModelMessageFlowDcopItem;
import com.nori.tc.db.domain.model.TcModelWorkflow;
import com.nori.tc.ui.core.exception.UiBadRequestException;
import com.nori.tc.ui.core.exception.UiConflictException;
import com.nori.tc.ui.core.exception.UiNotFoundException;
import com.nori.tc.ui.core.port.db.ModelFlowCommandPort;
import com.nori.tc.ui.domain.model.UiMessageFlow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * {@link ModelFlowCommandPort} 의 DB Store 기반 구현체입니다.
 *
 * <p>workflow row 1 개 = Flow 1 개. INSERT / UPDATE 는 단일 row 트랜잭션이며, UPDATE 시점에
 * Collect 섹션 (DCOP item 바인딩) 도 {@code tc_model_message_flow_dcop_item} join table 에
 * id-aware diff (toAdd / toRemove / toKeep) 로 함께 갱신합니다.</p>
 */
@Repository
public class JpaModelFlowCommandPort implements ModelFlowCommandPort {

    private static final Logger log = LoggerFactory.getLogger(JpaModelFlowCommandPort.class);

    /**
     * 신규 Flow INSERT 시 사용하는 action_name placeholder.
     *
     * <p>{@code tc_model_workflow.action_name} 은 NOT NULL 이므로 빈 문자열을 허용하지 않는다.
     * SPEC 의 "신규 Flow 는 4 섹션 모두 빈 상태" 요건은 UI 상태 의미이며 DB 컬럼 비어있음은 별개다.
     * 사용자가 후속 PUT 으로 실제 actionName 을 채울 때까지의 임시 값으로 {@code "TODO"} 를 사용한다.
     * {@code action_name} VARCHAR(200) 안에서 안전하다.</p>
     */
    private static final String NEW_FLOW_ACTION_NAME_PLACEHOLDER = "TODO";

    private final TcModelWorkflowStore modelWorkflowStore;
    private final TcModelMessageFlowDcopItemStore modelMessageFlowDcopItemStore;

    /**
     * 필수 의존성을 초기화합니다.
     */
    public JpaModelFlowCommandPort(
            final TcModelWorkflowStore modelWorkflowStore,
            final TcModelMessageFlowDcopItemStore modelMessageFlowDcopItemStore
    ) {
        this.modelWorkflowStore = Objects.requireNonNull(modelWorkflowStore, "modelWorkflowStore is null");
        this.modelMessageFlowDcopItemStore = Objects.requireNonNull(
                modelMessageFlowDcopItemStore, "modelMessageFlowDcopItemStore is null");
        log.info("JpaModelFlowCommandPort initialized.");
    }

    @Override
    @Transactional
    public long insertFlow(final long modelVersionKey, final String workflowName, final String messageName) {
        validateModelVersionKey(modelVersionKey);

        final UpsertTcModelWorkflow command = new UpsertTcModelWorkflow(
                null,
                modelVersionKey,
                workflowName,
                messageName,
                null,
                null,
                null,
                NEW_FLOW_ACTION_NAME_PLACEHOLDER,
                null
        );

        final TcModelWorkflow saved;
        try {
            saved = modelWorkflowStore.upsert(command);
        } catch (DbDuplicateKeyException e) {
            throw new UiConflictException("이미 동일한 (workflowName, messageName) Flow가 존재합니다.", e);
        } catch (RuntimeException e) {
            if (UiDbAdapterExceptionSupport.isConflict(e)) {
                throw new UiConflictException("이미 동일한 (workflowName, messageName) Flow가 존재합니다.", e);
            }
            throw e;
        }

        log.info("Flow INSERT 완료. modelVersionKey={}, flowId={}, workflowName={}, messageName={}",
                modelVersionKey, saved.workflowKey(), saved.workflowName(), saved.messageName());
        return saved.workflowKey();
    }

    @Override
    @Transactional
    public void updateFlow(final long modelVersionKey, final long flowId, final UiMessageFlow request) {
        validateModelVersionKey(modelVersionKey);
        if (flowId <= 0L) {
            throw new UiBadRequestException("flowId는 1 이상이어야 합니다.");
        }
        Objects.requireNonNull(request, "request is null");

        final Optional<TcModelWorkflow> existing = modelWorkflowStore.findByWorkflowKey(flowId);
        if (existing.isEmpty() || existing.get().modelVersionKey() != modelVersionKey) {
            throw new UiNotFoundException("Flow를 찾을 수 없습니다. modelVersionKey=" + modelVersionKey
                    + ", flowId=" + flowId);
        }

        final UpsertTcModelWorkflow command = new UpsertTcModelWorkflow(
                flowId,
                modelVersionKey,
                request.workflowName(),
                request.messageName(),
                request.eventId(),
                request.transactionId(),
                request.workflowFilter(),
                request.actionName(),
                request.actionDataIndex()
        );

        try {
            modelWorkflowStore.upsert(command);
        } catch (DbDuplicateKeyException e) {
            throw new UiConflictException("이미 동일한 (workflowName, messageName) Flow가 존재합니다.", e);
        } catch (RuntimeException e) {
            if (UiDbAdapterExceptionSupport.isConflict(e)) {
                throw new UiConflictException("이미 동일한 (workflowName, messageName) Flow가 존재합니다.", e);
            }
            throw e;
        }

        if (request.dcopItemKeys() != null) {
            replaceFlowDcopBindings(flowId, modelVersionKey, request.dcopItemKeys());
        }

        log.info("Flow UPDATE 완료. modelVersionKey={}, flowId={}", modelVersionKey, flowId);
    }

    /**
     * Flow 의 Collect 섹션 binding 을 요청 배열로 교체합니다.
     *
     * <p>id-aware diff:</p>
     * <ul>
     *   <li>{@code toAdd = next - current} → {@code upsert} 로 신규 join row INSERT</li>
     *   <li>{@code toRemove = current - next} → {@code deleteByWorkflowKeyAndDcopItemKey} 로 정리</li>
     *   <li>{@code toKeep = next ∩ current} → no-op (audit churn 방지)</li>
     * </ul>
     */
    private void replaceFlowDcopBindings(
            final long workflowKey,
            final long modelVersionKey,
            final List<Long> nextDcopItemKeys
    ) {
        final Set<Long> next = new LinkedHashSet<>();
        for (final Long key : nextDcopItemKeys) {
            if (key == null || key <= 0L) {
                throw new UiBadRequestException("dcopItemKey 는 1 이상이어야 합니다.");
            }
            next.add(key);
        }

        final Set<Long> current = modelMessageFlowDcopItemStore
                .findAllByWorkflowKey(workflowKey)
                .stream()
                .map(TcModelMessageFlowDcopItem::dcopItemKey)
                .collect(java.util.stream.Collectors.toCollection(HashSet::new));

        final Set<Long> toAdd = new LinkedHashSet<>(next);
        toAdd.removeAll(current);

        final Set<Long> toRemove = new HashSet<>(current);
        toRemove.removeAll(next);

        for (final Long dcopItemKey : toAdd) {
            modelMessageFlowDcopItemStore.upsert(new UpsertTcModelMessageFlowDcopItem(
                    null,
                    workflowKey,
                    dcopItemKey,
                    modelVersionKey
            ));
        }
        for (final Long dcopItemKey : toRemove) {
            modelMessageFlowDcopItemStore.deleteByWorkflowKeyAndDcopItemKey(workflowKey, dcopItemKey);
        }
    }

    /**
     * modelVersionKey 유효성을 검증합니다.
     */
    private static void validateModelVersionKey(final long modelVersionKey) {
        if (modelVersionKey <= 0L) {
            throw new UiBadRequestException("modelVersionKey는 1 이상이어야 합니다.");
        }
    }
}

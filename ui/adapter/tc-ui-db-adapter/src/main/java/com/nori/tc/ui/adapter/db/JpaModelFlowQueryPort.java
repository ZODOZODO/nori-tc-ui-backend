package com.nori.tc.ui.adapter.db;

import com.nori.tc.db.core.common.PageRequest;
import com.nori.tc.db.core.model.store.TcModelDcopItemStore;
import com.nori.tc.db.core.model.store.TcModelWorkflowStore;
import com.nori.tc.db.domain.model.TcModelDcopItem;
import com.nori.tc.db.domain.model.TcModelWorkflow;
import com.nori.tc.ui.adapter.db.mapper.UiModelDbMapper;
import com.nori.tc.ui.core.exception.UiBadRequestException;
import com.nori.tc.ui.core.port.db.ModelFlowQueryPort;
import com.nori.tc.ui.domain.model.UiMessageFlow;
import com.nori.tc.ui.domain.model.UiMessageFlowSummary;
import com.nori.tc.ui.domain.model.UiModelDcopItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * {@link ModelFlowQueryPort} 의 DB Store 기반 구현체입니다.
 *
 * <p>workflow row 1 개 = Flow 1 개. Flow 의 Collect 섹션 (DCOP item) 은
 * {@code tc_model_dcop_item.workflow_name == workflow.workflow_name} 매칭으로 client-side
 * 필터링합니다 (store 의 batch IN-by-workflow-name primitive 미존재).</p>
 */
@Repository
public class JpaModelFlowQueryPort implements ModelFlowQueryPort {

    private static final Logger log = LoggerFactory.getLogger(JpaModelFlowQueryPort.class);
    private static final int DETAIL_PAGE_LIMIT = 500;

    private final TcModelWorkflowStore modelWorkflowStore;
    private final TcModelDcopItemStore modelDcopItemStore;

    /**
     * 필수 의존성을 초기화합니다.
     */
    public JpaModelFlowQueryPort(
            final TcModelWorkflowStore modelWorkflowStore,
            final TcModelDcopItemStore modelDcopItemStore
    ) {
        this.modelWorkflowStore = Objects.requireNonNull(modelWorkflowStore, "modelWorkflowStore is null");
        this.modelDcopItemStore = Objects.requireNonNull(modelDcopItemStore, "modelDcopItemStore is null");
        log.info("JpaModelFlowQueryPort initialized.");
    }

    @Override
    public List<UiMessageFlowSummary> findFlowSummaries(final long modelVersionKey) {
        validateModelVersionKey(modelVersionKey);
        return loadAllByPage(page -> modelWorkflowStore.findAllByModelVersionKey(modelVersionKey, page))
                .stream()
                .sorted(Comparator.comparingLong(TcModelWorkflow::workflowKey))
                .map(UiModelDbMapper::toFlowSummary)
                .toList();
    }

    @Override
    public Optional<UiMessageFlow> findFlowByWorkflowKey(final long modelVersionKey, final long flowId) {
        validateModelVersionKey(modelVersionKey);
        if (flowId <= 0L) {
            throw new UiBadRequestException("flowId는 1 이상이어야 합니다.");
        }

        final Optional<TcModelWorkflow> workflowOpt = modelWorkflowStore.findByWorkflowKey(flowId);
        if (workflowOpt.isEmpty()) {
            return Optional.empty();
        }

        final TcModelWorkflow workflow = workflowOpt.get();
        if (workflow.modelVersionKey() != modelVersionKey) {
            // 다른 모델 버전의 workflow row 노출을 차단한다 — 호출자에게는 "해당 modelVersionKey 의
            // flowId 는 존재하지 않음" 으로 보이게 한다.
            return Optional.empty();
        }

        final List<UiModelDcopItem> dcopItems = loadAllByPage(
                page -> modelDcopItemStore.findAllByModelVersionKey(modelVersionKey, page)
        )
                .stream()
                .filter(item -> workflow.workflowName().equals(item.workflowName()))
                .sorted(Comparator.comparing(
                        TcModelDcopItem::dcopItemKey,
                        Comparator.nullsLast(Long::compareTo)
                ))
                .map(UiModelDbMapper::toDomain)
                .toList();

        return Optional.of(UiModelDbMapper.toFlowDetail(workflow, dcopItems));
    }

    /**
     * 공통 페이지 로더로 전체 목록을 수집합니다.
     */
    private static <T> List<T> loadAllByPage(final Function<PageRequest, List<T>> pageLoader) {
        final List<T> results = new ArrayList<>();
        int offset = 0;

        while (true) {
            final List<T> page = pageLoader.apply(PageRequest.of(offset, DETAIL_PAGE_LIMIT));
            if (page == null || page.isEmpty()) {
                break;
            }

            results.addAll(page);

            if (page.size() < DETAIL_PAGE_LIMIT) {
                break;
            }

            offset += DETAIL_PAGE_LIMIT;
        }

        return results;
    }

    /**
     * modelVersionKey 입력값 유효성을 검증합니다.
     */
    private static void validateModelVersionKey(final long modelVersionKey) {
        if (modelVersionKey <= 0L) {
            throw new UiBadRequestException("modelVersionKey는 1 이상이어야 합니다.");
        }
    }
}

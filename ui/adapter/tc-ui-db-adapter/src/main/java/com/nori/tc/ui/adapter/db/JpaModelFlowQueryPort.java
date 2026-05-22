package com.nori.tc.ui.adapter.db;

import com.nori.tc.db.core.common.PageRequest;
import com.nori.tc.db.core.model.store.TcModelDcopItemStore;
import com.nori.tc.db.core.model.store.TcModelMessageFlowDcopItemStore;
import com.nori.tc.db.core.model.store.TcModelMessageFlowStore;
import com.nori.tc.db.domain.model.TcModelDcopItem;
import com.nori.tc.db.domain.model.TcModelMessageFlow;
import com.nori.tc.db.domain.model.TcModelMessageFlowDcopItem;
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
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * {@link ModelFlowQueryPort} 의 DB Store 기반 구현체입니다.
 *
 * <p>message flow row 1 개 = Flow 1 개. Flow 의 Collect 섹션 (DCOP item) 은
 * {@code tc_model_message_flow_dcop_item} N:M join table 에서 {@code message_flow_key} 로
 * 매칭된 {@code dcop_item_key} 만 추려 dcop_item 본체와 결합합니다 (한 dcop_item 이 다수
 * Flow 에 매핑된 N:M 상태에서도 각 Flow 의 Collect 응답은 자기 Flow 의 매핑만 포함).</p>
 */
@Repository
public class JpaModelFlowQueryPort implements ModelFlowQueryPort {

    private static final Logger log = LoggerFactory.getLogger(JpaModelFlowQueryPort.class);
    private static final int DETAIL_PAGE_LIMIT = 500;

    private final TcModelMessageFlowStore modelMessageFlowStore;
    private final TcModelDcopItemStore modelDcopItemStore;
    private final TcModelMessageFlowDcopItemStore modelMessageFlowDcopItemStore;

    /**
     * 필수 의존성을 초기화합니다.
     */
    public JpaModelFlowQueryPort(
            final TcModelMessageFlowStore modelMessageFlowStore,
            final TcModelDcopItemStore modelDcopItemStore,
            final TcModelMessageFlowDcopItemStore modelMessageFlowDcopItemStore
    ) {
        this.modelMessageFlowStore = Objects.requireNonNull(modelMessageFlowStore, "modelMessageFlowStore is null");
        this.modelDcopItemStore = Objects.requireNonNull(modelDcopItemStore, "modelDcopItemStore is null");
        this.modelMessageFlowDcopItemStore = Objects.requireNonNull(
                modelMessageFlowDcopItemStore, "modelMessageFlowDcopItemStore is null");
        log.info("JpaModelFlowQueryPort initialized.");
    }

    @Override
    public List<UiMessageFlowSummary> findFlowSummaries(final long modelVersionKey) {
        validateModelVersionKey(modelVersionKey);
        return loadAllByPage(page -> modelMessageFlowStore.findAllByModelVersionKey(modelVersionKey, page))
                .stream()
                .sorted(Comparator.comparingLong(TcModelMessageFlow::messageFlowKey))
                .map(UiModelDbMapper::toFlowSummary)
                .toList();
    }

    @Override
    public Optional<UiMessageFlow> findFlowByMessageFlowKey(final long modelVersionKey, final long flowId) {
        validateModelVersionKey(modelVersionKey);
        if (flowId <= 0L) {
            throw new UiBadRequestException("flowId는 1 이상이어야 합니다.");
        }

        final Optional<TcModelMessageFlow> messageFlowOpt = modelMessageFlowStore.findByMessageFlowKey(flowId);
        if (messageFlowOpt.isEmpty()) {
            return Optional.empty();
        }

        final TcModelMessageFlow messageFlow = messageFlowOpt.get();
        if (messageFlow.modelVersionKey() != modelVersionKey) {
            // 다른 모델 버전의 message flow row 노출을 차단한다 — 호출자에게는 "해당 modelVersionKey 의
            // flowId 는 존재하지 않음" 으로 보이게 한다.
            return Optional.empty();
        }

        final Set<Long> boundDcopItemKeys = modelMessageFlowDcopItemStore
                .findAllByMessageFlowKey(messageFlow.messageFlowKey())
                .stream()
                .map(TcModelMessageFlowDcopItem::dcopItemKey)
                .collect(java.util.stream.Collectors.toCollection(HashSet::new));

        final List<UiModelDcopItem> dcopItems;
        if (boundDcopItemKeys.isEmpty()) {
            dcopItems = List.of();
        } else {
            // dcop_item 본체는 model_version 단위 pool 에서 In-memory Set 조회 — N:M binding 의
            // dcop_item_key 부분집합만 매칭하므로 modelVersion pool 전체를 페이지 로드 후 필터링한다.
            dcopItems = loadAllByPage(
                    page -> modelDcopItemStore.findAllByModelVersionKey(modelVersionKey, page)
            )
                    .stream()
                    .filter(item -> item.dcopItemKey() != null
                            && boundDcopItemKeys.contains(item.dcopItemKey()))
                    .sorted(Comparator.comparing(
                            TcModelDcopItem::dcopItemKey,
                            Comparator.nullsLast(Long::compareTo)
                    ))
                    .map(UiModelDbMapper::toDomain)
                    .toList();
        }

        return Optional.of(UiModelDbMapper.toFlowDetail(messageFlow, dcopItems));
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

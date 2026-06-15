package com.nori.tc.ui.adapter.db;

import com.nori.tc.db.core.common.PageRequest;
import com.nori.tc.db.core.model.store.TcModelDcopItemStore;
import com.nori.tc.db.core.model.store.TcModelEventIdStore;
import com.nori.tc.db.core.model.store.TcModelMdfStore;
import com.nori.tc.db.core.model.store.TcModelParamStore;
import com.nori.tc.db.core.model.store.TcModelReportIdStore;
import com.nori.tc.db.core.model.store.TcModelMesMessageStore;
import com.nori.tc.db.core.model.store.TcModelSecsMessageStore;
import com.nori.tc.db.core.model.store.TcModelSocketMessageStore;
import com.nori.tc.db.core.model.store.TcModelMessageFlowStore;
import com.nori.tc.db.core.model.store.TcModelPlcGroupStore;
import com.nori.tc.db.core.model.store.TcModelPlcRegisterStore;
import com.nori.tc.db.core.model.store.TcModelVariableIdStore;
import com.nori.tc.db.domain.model.TcModelDcopItem;
import com.nori.tc.db.domain.model.TcModelEventId;
import com.nori.tc.db.domain.model.TcModelParam;
import com.nori.tc.db.domain.model.TcModelReportId;
import com.nori.tc.db.domain.model.TcModelMesMessage;
import com.nori.tc.db.domain.model.TcModelSecsMessage;
import com.nori.tc.db.domain.model.TcModelSocketMessage;
import com.nori.tc.db.domain.model.TcModelMessageFlow;
import com.nori.tc.db.domain.model.TcModelPlcGroup;
import com.nori.tc.db.domain.model.TcModelVariableId;
import com.nori.tc.ui.adapter.db.mapper.UiModelDbMapper;
import com.nori.tc.ui.core.exception.UiBadRequestException;
import com.nori.tc.ui.core.port.db.ModelDetailQueryPort;
import com.nori.tc.ui.domain.model.UiModelDcopItem;
import com.nori.tc.ui.domain.model.UiModelEventId;
import com.nori.tc.ui.domain.model.UiModelMdf;
import com.nori.tc.ui.domain.model.UiModelMesMessage;
import com.nori.tc.ui.domain.model.UiModelParam;
import com.nori.tc.ui.domain.model.UiModelPlcGroup;
import com.nori.tc.ui.domain.model.UiModelPlcRegister;
import com.nori.tc.ui.domain.model.UiModelReportId;
import com.nori.tc.ui.domain.model.UiModelSecsMessage;
import com.nori.tc.ui.domain.model.UiModelSocketMessage;
import com.nori.tc.ui.domain.model.UiModelMessageFlow;
import com.nori.tc.ui.domain.model.UiModelVariableId;
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
 * {@link ModelDetailQueryPort}의 DB Store 기반 구현체입니다.
 *
 * <p>Model 상세 화면에서 선택한 노드에 따라 model_version_key 기준으로
 * 하위 테이블 데이터를 조회합니다.</p>
 */
@Repository
public class JpaModelDetailQueryPort implements ModelDetailQueryPort {

    private static final Logger log = LoggerFactory.getLogger(JpaModelDetailQueryPort.class);
    private static final int DETAIL_PAGE_LIMIT = 500;

    private final TcModelParamStore modelParamStore;
    private final TcModelSecsMessageStore modelSecsMessageStore;
    private final TcModelSocketMessageStore modelSocketMessageStore;
    private final TcModelMesMessageStore modelMesMessageStore;
    private final TcModelVariableIdStore modelVariableIdStore;
    private final TcModelReportIdStore modelReportIdStore;
    private final TcModelEventIdStore modelEventIdStore;
    private final TcModelMessageFlowStore modelMessageFlowStore;
    private final TcModelMdfStore modelMdfStore;
    private final TcModelDcopItemStore modelDcopItemStore;
    private final TcModelPlcGroupStore modelPlcGroupStore;
    private final TcModelPlcRegisterStore modelPlcRegisterStore;

    /**
     * 필수 의존성을 초기화합니다.
     */
    public JpaModelDetailQueryPort(
            final TcModelParamStore modelParamStore,
            final TcModelSecsMessageStore modelSecsMessageStore,
            final TcModelSocketMessageStore modelSocketMessageStore,
            final TcModelMesMessageStore modelMesMessageStore,
            final TcModelVariableIdStore modelVariableIdStore,
            final TcModelReportIdStore modelReportIdStore,
            final TcModelEventIdStore modelEventIdStore,
            final TcModelMessageFlowStore modelMessageFlowStore,
            final TcModelMdfStore modelMdfStore,
            final TcModelDcopItemStore modelDcopItemStore,
            final TcModelPlcGroupStore modelPlcGroupStore,
            final TcModelPlcRegisterStore modelPlcRegisterStore
    ) {
        this.modelParamStore = Objects.requireNonNull(modelParamStore, "modelParamStore is null");
        this.modelSecsMessageStore = Objects.requireNonNull(modelSecsMessageStore, "modelSecsMessageStore is null");
        this.modelSocketMessageStore = Objects.requireNonNull(modelSocketMessageStore, "modelSocketMessageStore is null");
        this.modelMesMessageStore = Objects.requireNonNull(modelMesMessageStore, "modelMesMessageStore is null");
        this.modelVariableIdStore = Objects.requireNonNull(modelVariableIdStore, "modelVariableIdStore is null");
        this.modelReportIdStore = Objects.requireNonNull(modelReportIdStore, "modelReportIdStore is null");
        this.modelEventIdStore = Objects.requireNonNull(modelEventIdStore, "modelEventIdStore is null");
        this.modelMessageFlowStore = Objects.requireNonNull(modelMessageFlowStore, "modelMessageFlowStore is null");
        this.modelMdfStore = Objects.requireNonNull(modelMdfStore, "modelMdfStore is null");
        this.modelDcopItemStore = Objects.requireNonNull(modelDcopItemStore, "modelDcopItemStore is null");
        this.modelPlcGroupStore = Objects.requireNonNull(modelPlcGroupStore, "modelPlcGroupStore is null");
        this.modelPlcRegisterStore = Objects.requireNonNull(modelPlcRegisterStore, "modelPlcRegisterStore is null");
        log.info("JpaModelDetailQueryPort initialized.");
    }

    @Override
    public List<UiModelParam> findParamsByModelVersionKey(final long modelVersionKey) {
        validateModelVersionKey(modelVersionKey);
        return loadAllByPage(page -> modelParamStore.findAllByModelVersionKey(modelVersionKey, page))
                .stream()
                .sorted(Comparator.comparingLong(TcModelParam::modelParamKey))
                .map(UiModelDbMapper::toDomain)
                .toList();
    }

    @Override
    public List<UiModelSecsMessage> findSecsMessagesByModelVersionKey(final long modelVersionKey) {
        validateModelVersionKey(modelVersionKey);
        return loadAllByPage(page -> modelSecsMessageStore.findAllByModelVersionKey(modelVersionKey, page))
                .stream()
                .sorted(Comparator.comparingLong(TcModelSecsMessage::secsMsgKey))
                .map(UiModelDbMapper::toDomain)
                .toList();
    }

    @Override
    public List<UiModelSocketMessage> findSocketMessagesByModelVersionKey(final long modelVersionKey) {
        validateModelVersionKey(modelVersionKey);
        return loadAllByPage(page -> modelSocketMessageStore.findAllByModelVersionKey(modelVersionKey, page))
                .stream()
                .sorted(Comparator.comparingLong(TcModelSocketMessage::socketMsgKey))
                .map(UiModelDbMapper::toDomain)
                .toList();
    }

    @Override
    public List<UiModelMesMessage> findMesMessagesByModelVersionKey(final long modelVersionKey) {
        validateModelVersionKey(modelVersionKey);
        return loadAllByPage(page -> modelMesMessageStore.findAllByModelVersionKey(modelVersionKey, page))
                .stream()
                .sorted(Comparator.comparingLong(TcModelMesMessage::mesMsgKey))
                .map(UiModelDbMapper::toDomain)
                .toList();
    }

    @Override
    public List<UiModelVariableId> findVariableIdsByModelVersionKey(final long modelVersionKey) {
        validateModelVersionKey(modelVersionKey);
        return loadAllByPage(page -> modelVariableIdStore.findAllByModelVersionKey(modelVersionKey, page))
                .stream()
                .sorted(Comparator.comparingLong(TcModelVariableId::variableKey))
                .map(UiModelDbMapper::toDomain)
                .toList();
    }

    @Override
    public List<UiModelReportId> findReportIdsByModelVersionKey(final long modelVersionKey) {
        validateModelVersionKey(modelVersionKey);
        return loadAllByPage(page -> modelReportIdStore.findAllByModelVersionKey(modelVersionKey, page))
                .stream()
                .sorted(Comparator.comparingLong(TcModelReportId::reportKey))
                .map(UiModelDbMapper::toDomain)
                .toList();
    }

    @Override
    public List<UiModelEventId> findEventIdsByModelVersionKey(final long modelVersionKey) {
        validateModelVersionKey(modelVersionKey);
        return loadAllByPage(page -> modelEventIdStore.findAllByModelVersionKey(modelVersionKey, page))
                .stream()
                .sorted(Comparator.comparingLong(TcModelEventId::eventKey))
                .map(UiModelDbMapper::toDomain)
                .toList();
    }

    @Override
    public List<UiModelMessageFlow> findMessageFlowsByModelVersionKey(final long modelVersionKey) {
        validateModelVersionKey(modelVersionKey);
        return loadAllByPage(page -> modelMessageFlowStore.findAllByModelVersionKey(modelVersionKey, page))
                .stream()
                .sorted(Comparator.comparingLong(TcModelMessageFlow::messageFlowKey))
                .map(UiModelDbMapper::toDomain)
                .toList();
    }

    @Override
    public Optional<UiModelMdf> findMdfByModelVersionKey(final long modelVersionKey) {
        validateModelVersionKey(modelVersionKey);
        return modelMdfStore.findByModelVersionKey(modelVersionKey)
                .map(UiModelDbMapper::toDomain);
    }

    @Override
    public List<UiModelDcopItem> findDcopItemsByModelVersionKey(final long modelVersionKey) {
        validateModelVersionKey(modelVersionKey);
        return loadAllByPage(page -> modelDcopItemStore.findAllByModelVersionKey(modelVersionKey, page))
                .stream()
                .sorted(Comparator.comparing(TcModelDcopItem::dcopItemKey, Comparator.nullsLast(Long::compareTo)))
                .map(UiModelDbMapper::toDomain)
                .toList();
    }

    @Override
    public List<UiModelPlcGroup> findPlcGroupsByModelVersionKey(final long modelVersionKey) {
        validateModelVersionKey(modelVersionKey);
        return modelPlcGroupStore.findAllByModelVersionKey(modelVersionKey).stream()
                .sorted(Comparator.comparingLong(TcModelPlcGroup::groupKey))
                .map(UiModelDbMapper::toDomain)
                .toList();
    }

    @Override
    public List<UiModelPlcRegister> findPlcRegistersByModelVersionKey(final long modelVersionKey) {
        validateModelVersionKey(modelVersionKey);
        // 모델의 모든 그룹을 그룹키 순으로 순회하며 각 그룹의 레지스터를 평탄화. 소속 그룹 message_name 을 함께 운반.
        return modelPlcGroupStore.findAllByModelVersionKey(modelVersionKey).stream()
                .sorted(Comparator.comparingLong(TcModelPlcGroup::groupKey))
                .flatMap(group -> modelPlcRegisterStore.findAllByGroupKey(group.groupKey()).stream()
                        .sorted(Comparator.comparingLong(register -> register.registerKey()))
                        .map(register -> UiModelDbMapper.toDomain(register, group.messageName())))
                .toList();
    }

    /**
     * 공통 페이지 로더를 사용해 전체 목록을 수집합니다.
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

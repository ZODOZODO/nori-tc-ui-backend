package com.nori.tc.ui.adapter.db.mapper;

import com.nori.tc.db.domain.model.TcModel;
import com.nori.tc.db.domain.model.TcModelDcopItem;
import com.nori.tc.db.domain.model.TcModelEventId;
import com.nori.tc.db.domain.model.TcModelMdf;
import com.nori.tc.db.domain.model.TcModelMesMessage;
import com.nori.tc.db.domain.model.TcModelParam;
import com.nori.tc.db.domain.model.TcModelReportId;
import com.nori.tc.db.domain.model.TcModelSecsMessage;
import com.nori.tc.db.domain.model.TcModelSocketMessage;
import com.nori.tc.db.domain.model.TcModelVariableId;
import com.nori.tc.db.domain.model.TcModelWorkflow;
import com.nori.tc.ui.domain.model.UiMessageFlow;
import com.nori.tc.ui.domain.model.UiMessageFlowSummary;
import com.nori.tc.ui.domain.model.UiModel;
import com.nori.tc.ui.domain.model.UiModelDcopItem;
import com.nori.tc.ui.domain.model.UiModelEventId;
import com.nori.tc.ui.domain.model.UiModelMdf;
import com.nori.tc.ui.domain.model.UiModelMesMessage;
import com.nori.tc.ui.domain.model.UiModelParam;
import com.nori.tc.ui.domain.model.UiModelReportId;
import com.nori.tc.ui.domain.model.UiModelSecsMessage;
import com.nori.tc.ui.domain.model.UiModelSocketMessage;
import com.nori.tc.ui.domain.model.UiModelVariableId;
import com.nori.tc.ui.domain.model.UiModelWorkflow;

import java.util.List;

/**
 * DB 모델 레코드와 UI 내부 모델 간 ACL 매퍼입니다.
 */
public final class UiModelDbMapper {

    private UiModelDbMapper() {
    }

    public static UiModel toDomain(final TcModel source) {
        if (source == null) {
            return null;
        }
        return new UiModel(
                source.modelVersionKey(),
                source.modelKey(),
                source.modelName(),
                source.parentModel(),
                source.modelVersion(),
                UiDomainEnumMapper.toUiProtocolType(source.commInterface()),
                UiDomainEnumMapper.toUiModelStatus(source.status()),
                source.description(),
                source.maker(),
                source.createdAt(),
                source.updatedAt(),
                source.createdBy(),
                source.updatedBy()
        );
    }

    public static UiModelParam toDomain(final TcModelParam source) {
        if (source == null) {
            return null;
        }
        return new UiModelParam(
                source.modelParamKey(),
                source.modelVersionKey(),
                source.paramName(),
                source.paramValue(),
                source.description(),
                source.updatedAt()
        );
    }

    public static UiModelSecsMessage toDomain(final TcModelSecsMessage source) {
        if (source == null) {
            return null;
        }
        return new UiModelSecsMessage(
                source.secsMsgKey(),
                source.modelVersionKey(),
                source.secsMsgName(),
                source.description(),
                source.updatedAt()
        );
    }

    public static UiModelSocketMessage toDomain(final TcModelSocketMessage source) {
        if (source == null) {
            return null;
        }
        return new UiModelSocketMessage(
                source.socketMsgKey(),
                source.modelVersionKey(),
                source.socketMsgName(),
                source.description(),
                source.updatedAt()
        );
    }

    public static UiModelMesMessage toDomain(final TcModelMesMessage source) {
        if (source == null) {
            return null;
        }
        return new UiModelMesMessage(
                source.mesMsgKey(),
                source.modelVersionKey(),
                source.mesMsgName(),
                source.description(),
                source.updatedAt()
        );
    }

    public static UiModelVariableId toDomain(final TcModelVariableId source) {
        if (source == null) {
            return null;
        }
        return new UiModelVariableId(
                source.variableKey(),
                source.modelVersionKey(),
                source.variableId(),
                UiDomainEnumMapper.toUiVariableIdType(source.variableIdType()),
                source.description(),
                source.updatedAt()
        );
    }

    public static UiModelReportId toDomain(final TcModelReportId source) {
        if (source == null) {
            return null;
        }
        return new UiModelReportId(
                source.reportKey(),
                source.modelVersionKey(),
                source.reportId(),
                source.variableId(),
                source.enabled(),
                source.description(),
                source.updatedAt()
        );
    }

    public static UiModelEventId toDomain(final TcModelEventId source) {
        if (source == null) {
            return null;
        }
        return new UiModelEventId(
                source.eventKey(),
                source.modelVersionKey(),
                source.eventId(),
                source.reportId(),
                source.enabled(),
                source.description(),
                source.updatedAt()
        );
    }

    public static UiModelWorkflow toDomain(final TcModelWorkflow source) {
        if (source == null) {
            return null;
        }
        return new UiModelWorkflow(
                source.workflowKey(),
                source.modelVersionKey(),
                source.workflowName(),
                source.messageName(),
                source.eventId(),
                source.transactionId(),
                source.workflowFilter(),
                source.actionName(),
                source.actionDataIndex(),
                source.updatedAt()
        );
    }

    public static UiModelMdf toDomain(final TcModelMdf source) {
        if (source == null) {
            return null;
        }
        return new UiModelMdf(
                source.mdfKey(),
                source.modelVersionKey(),
                source.mdfName(),
                source.mdfFile(),
                source.updatedAt()
        );
    }

    public static UiModelDcopItem toDomain(final TcModelDcopItem source) {
        if (source == null) {
            return null;
        }
        return new UiModelDcopItem(
                source.dcopItemKey(),
                source.modelVersionKey(),
                source.dcopItemName(),
                source.eventId(),
                source.variableId(),
                UiDomainEnumMapper.toUiDcopCollectionRule(source.collectionRule()),
                source.calculationRule(),
                source.orderRule(),
                source.updatedAt()
        );
    }

    /**
     * tc_model_workflow row 1 개를 Message Flow 목록 요약으로 변환합니다.
     */
    public static UiMessageFlowSummary toFlowSummary(final TcModelWorkflow source) {
        if (source == null) {
            return null;
        }
        return new UiMessageFlowSummary(
                source.workflowKey(),
                source.workflowName(),
                source.messageName(),
                source.actionName()
        );
    }

    /**
     * tc_model_workflow row 1 개 + 매칭 DCOP 목록을 Flow 상세 aggregate 로 결합합니다.
     *
     * <p>Trigger 3 필드 (eventId / transactionId / workflowFilter) 가 모두 null 이면 Socket 프로토콜
     * 모델의 Flow 입니다 — 분기는 호출 측 (FE) 에서 null 체크로 수행합니다.</p>
     *
     * <p>{@code dcopItemKeys} 는 {@code dcopItems} 의 키 집합으로 자동 도출 — 응답 시 동일 정보를
     * 두 표현으로 제공해 FE 가 round-trip PUT 시 별도 변환 없이 그대로 송신할 수 있도록 합니다.</p>
     */
    public static UiMessageFlow toFlowDetail(
            final TcModelWorkflow workflow,
            final List<UiModelDcopItem> dcopItems
    ) {
        if (workflow == null) {
            return null;
        }
        final List<UiModelDcopItem> safeDcopItems = dcopItems == null ? List.of() : List.copyOf(dcopItems);
        final List<Long> dcopItemKeys = safeDcopItems.stream()
                .map(UiModelDcopItem::dcopItemKey)
                .filter(java.util.Objects::nonNull)
                .toList();
        return new UiMessageFlow(
                workflow.workflowKey(),
                workflow.workflowName(),
                workflow.messageName(),
                workflow.eventId(),
                workflow.transactionId(),
                workflow.workflowFilter(),
                workflow.actionName(),
                workflow.actionDataIndex(),
                safeDcopItems,
                dcopItemKeys
        );
    }
}

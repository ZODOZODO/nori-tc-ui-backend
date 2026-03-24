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
                source.dataIndex(),
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
                source.dataIndex(),
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
                source.dataIndex(),
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
                source.workflowName(),
                source.eventId(),
                source.variableId(),
                UiDomainEnumMapper.toUiDcopCollectionRule(source.collectionRule()),
                source.calculationRule(),
                source.orderRule(),
                source.updatedAt()
        );
    }
}

package com.nori.tc.ui.adapter.db;

import com.nori.tc.db.core.common.PageRequest;
import com.nori.tc.db.core.model.store.TcModelDcopItemStore;
import com.nori.tc.db.core.model.store.TcModelEventIdStore;
import com.nori.tc.db.core.model.store.TcModelMdfStore;
import com.nori.tc.db.core.model.store.TcModelMesMessageStore;
import com.nori.tc.db.core.model.store.TcModelParamStore;
import com.nori.tc.db.core.model.store.TcModelReportIdStore;
import com.nori.tc.db.core.model.store.TcModelSecsMessageStore;
import com.nori.tc.db.core.model.store.TcModelSocketMessageStore;
import com.nori.tc.db.core.model.store.TcModelVariableIdStore;
import com.nori.tc.db.core.model.store.TcModelMessageFlowStore;
import com.nori.tc.db.core.model.store.TcModelPlcGroupStore;
import com.nori.tc.db.core.model.store.TcModelPlcRegisterStore;
import com.nori.tc.db.core.model.upsert.UpsertTcModelDcopItem;
import com.nori.tc.db.core.model.upsert.UpsertTcModelEventId;
import com.nori.tc.db.core.model.upsert.UpsertTcModelMdf;
import com.nori.tc.db.core.model.upsert.UpsertTcModelMesMessage;
import com.nori.tc.db.core.model.upsert.UpsertTcModelParam;
import com.nori.tc.db.core.model.upsert.UpsertTcModelReportId;
import com.nori.tc.db.core.model.upsert.UpsertTcModelSecsMessage;
import com.nori.tc.db.core.model.upsert.UpsertTcModelSocketMessage;
import com.nori.tc.db.core.model.upsert.UpsertTcModelVariableId;
import com.nori.tc.db.core.model.upsert.UpsertTcModelMessageFlow;
import com.nori.tc.db.core.model.upsert.UpsertTcModelPlcGroup;
import com.nori.tc.db.core.model.upsert.UpsertTcModelPlcRegister;
import com.nori.tc.db.domain.model.TcModelDcopItem;
import com.nori.tc.db.domain.model.TcModelEventId;
import com.nori.tc.db.domain.model.TcModelMdf;
import com.nori.tc.db.domain.model.TcModelMesMessage;
import com.nori.tc.db.domain.model.TcModelParam;
import com.nori.tc.db.domain.model.TcModelReportId;
import com.nori.tc.db.domain.model.TcModelSecsMessage;
import com.nori.tc.db.domain.model.TcModelSocketMessage;
import com.nori.tc.db.domain.model.TcModelVariableId;
import com.nori.tc.db.domain.model.TcModelMessageFlow;
import com.nori.tc.db.domain.model.TcModelPlcGroup;
import com.nori.tc.db.domain.model.TcModelPlcRegister;
import com.nori.tc.ui.core.port.db.ModelParentCommitPort;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * JpaModelManagementPort의 상세 복제/비교 책임을 분리합니다.
 */
final class JpaModelManagementDetailSupport {

    private static final int SCAN_PAGE_SIZE = 500;

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

    JpaModelManagementDetailSupport(
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
    }

    void cloneModelDetails(final long sourceModelVersionKey, final long targetModelVersionKey) {
        cloneSnapshotToVersion(loadSnapshot(sourceModelVersionKey), targetModelVersionKey);
    }

    void cloneSnapshotToVersion(
            final JpaModelManagementSupport.ModelDetailSnapshot snapshot,
            final long targetModelVersionKey
    ) {
        snapshot.params().forEach(param -> modelParamStore.upsert(new UpsertTcModelParam(
                targetModelVersionKey,
                param.paramName(),
                param.paramValue(),
                param.description()
        )));
        snapshot.secsMessages().forEach(message -> modelSecsMessageStore.upsert(new UpsertTcModelSecsMessage(
                null,
                targetModelVersionKey,
                message.secsMsgName(),
                message.description()
        )));
        snapshot.socketMessages().forEach(message -> modelSocketMessageStore.upsert(new UpsertTcModelSocketMessage(
                targetModelVersionKey,
                message.socketMsgName(),
                message.description(),
                null
        )));
        snapshot.mesMessages().forEach(message -> modelMesMessageStore.upsert(new UpsertTcModelMesMessage(
                null,
                targetModelVersionKey,
                message.mesMsgName(),
                message.description()
        )));
        snapshot.variableIds().forEach(variableId -> modelVariableIdStore.upsert(new UpsertTcModelVariableId(
                targetModelVersionKey,
                variableId.variableIdType(),
                variableId.variableId(),
                variableId.description()
        )));
        snapshot.reportIds().forEach(reportId -> modelReportIdStore.upsert(new UpsertTcModelReportId(
                targetModelVersionKey,
                reportId.reportId(),
                reportId.variableId(),
                reportId.enabled(),
                reportId.description()
        )));
        snapshot.eventIds().forEach(eventId -> modelEventIdStore.upsert(new UpsertTcModelEventId(
                targetModelVersionKey,
                eventId.eventId(),
                eventId.reportId(),
                eventId.description(),
                eventId.enabled()
        )));
        snapshot.messageFlows().forEach(messageFlow -> modelMessageFlowStore.upsert(new UpsertTcModelMessageFlow(
                null,
                targetModelVersionKey,
                messageFlow.workflowName(),
                messageFlow.messageName(),
                messageFlow.eventId(),
                messageFlow.transactionId(),
                messageFlow.workflowFilter(),
                messageFlow.actionName(),
                messageFlow.actionDataIndex()
        )));
        snapshot.mdfs().forEach(mdf -> modelMdfStore.upsert(new UpsertTcModelMdf(
                null,
                targetModelVersionKey,
                mdf.mdfName(),
                mdf.mdfFile()
        )));
        snapshot.dcopItems().forEach(dcopItem -> modelDcopItemStore.upsert(new UpsertTcModelDcopItem(
                null,
                targetModelVersionKey,
                dcopItem.dcopItemName(),
                dcopItem.eventId(),
                dcopItem.variableId(),
                dcopItem.collectionRule(),
                dcopItem.calculationRule(),
                dcopItem.orderRule()
        )));
        clonePlcDetails(snapshot, targetModelVersionKey);
    }

    /**
     * plc-group / plc-register 를 타깃 버전으로 복제합니다.
     *
     * <p>register 는 group_key FK 라 단순 row 복제가 불가하다. 그룹을 타깃 버전에 새로 생성하면 새 group_key 가
     * 부여되므로, sourceGroupKey → newGroupKey 매핑을 만든 뒤 각 register 를 새 group_key 로 재삽입한다.
     * register 는 loadSnapshot 에서 그룹 평탄화로 적재되어 모든 register 의 group_key 는 snapshot.plcGroups() 안에 존재한다.</p>
     */
    private void clonePlcDetails(
            final JpaModelManagementSupport.ModelDetailSnapshot snapshot,
            final long targetModelVersionKey
    ) {
        final Map<Long, Long> groupKeyMapping = new HashMap<>();
        snapshot.plcGroups().forEach(group -> {
            final TcModelPlcGroup created = modelPlcGroupStore.upsert(new UpsertTcModelPlcGroup(
                    null,
                    targetModelVersionKey,
                    group.messageName(),
                    group.area(),
                    group.startAddress(),
                    group.readCount(),
                    group.addressQualifier(),
                    group.pollPeriodMs(),
                    group.description()
            ));
            groupKeyMapping.put(group.groupKey(), created.groupKey());
        });

        snapshot.plcRegisters().forEach(register -> {
            final long targetGroupKey = groupKeyMapping.get(register.groupKey());
            modelPlcRegisterStore.upsert(new UpsertTcModelPlcRegister(
                    null,
                    targetGroupKey,
                    register.wordOffset(),
                    register.variableName(),
                    register.dataType(),
                    register.length(),
                    register.bitIndex(),
                    register.byteIndex(),
                    register.byteOrder(),
                    register.wordOrder(),
                    register.scale(),
                    register.bcdSigned(),
                    register.charEncoding(),
                    register.description()
            ));
        });
    }

    List<ModelParentCommitPort.DiffSection> buildDiffSections(
            final JpaModelManagementSupport.ModelDetailSnapshot branchSnapshot,
            final JpaModelManagementSupport.ModelDetailSnapshot parentSnapshot
    ) {
        return List.of(
                buildDiffSection(
                        "model-parameter",
                        JpaModelManagementSupport.MODEL_PARAMETER_COLUMNS,
                        branchSnapshot.params().stream().map(this::toParamRow).toList(),
                        parentSnapshot.params().stream().map(this::toParamRow).toList()
                ),
                buildDiffSection(
                        "secs-message",
                        JpaModelManagementSupport.SECS_MESSAGE_COLUMNS,
                        branchSnapshot.secsMessages().stream().map(this::toSecsMessageRow).toList(),
                        parentSnapshot.secsMessages().stream().map(this::toSecsMessageRow).toList()
                ),
                buildDiffSection(
                        "socket-message",
                        JpaModelManagementSupport.SOCKET_MESSAGE_COLUMNS,
                        branchSnapshot.socketMessages().stream().map(this::toSocketMessageRow).toList(),
                        parentSnapshot.socketMessages().stream().map(this::toSocketMessageRow).toList()
                ),
                buildDiffSection(
                        "mes-message",
                        JpaModelManagementSupport.SOCKET_MESSAGE_COLUMNS,
                        branchSnapshot.mesMessages().stream().map(this::toMesMessageRow).toList(),
                        parentSnapshot.mesMessages().stream().map(this::toMesMessageRow).toList()
                ),
                buildDiffSection(
                        "variableides",
                        JpaModelManagementSupport.VARIABLE_ID_COLUMNS,
                        branchSnapshot.variableIds().stream().map(this::toVariableIdRow).toList(),
                        parentSnapshot.variableIds().stream().map(this::toVariableIdRow).toList()
                ),
                buildDiffSection(
                        "reportides",
                        JpaModelManagementSupport.REPORT_ID_COLUMNS,
                        branchSnapshot.reportIds().stream().map(this::toReportIdRow).toList(),
                        parentSnapshot.reportIds().stream().map(this::toReportIdRow).toList()
                ),
                buildDiffSection(
                        "eventides",
                        JpaModelManagementSupport.EVENT_ID_COLUMNS,
                        branchSnapshot.eventIds().stream().map(this::toEventIdRow).toList(),
                        parentSnapshot.eventIds().stream().map(this::toEventIdRow).toList()
                ),
                buildDiffSection(
                        "workflow",
                        JpaModelManagementSupport.WORKFLOW_COLUMNS,
                        branchSnapshot.messageFlows().stream().map(this::toMessageFlowRow).toList(),
                        parentSnapshot.messageFlows().stream().map(this::toMessageFlowRow).toList()
                ),
                buildDiffSection(
                        "mdf",
                        JpaModelManagementSupport.MDF_COLUMNS,
                        branchSnapshot.mdfs().stream().map(this::toMdfRow).toList(),
                        parentSnapshot.mdfs().stream().map(this::toMdfRow).toList()
                ),
                buildDiffSection(
                        "dcop-itemes",
                        JpaModelManagementSupport.DCOP_ITEM_COLUMNS,
                        branchSnapshot.dcopItems().stream().map(this::toDcopItemRow).toList(),
                        parentSnapshot.dcopItems().stream().map(this::toDcopItemRow).toList()
                ),
                buildDiffSection(
                        "plc-group",
                        JpaModelManagementSupport.PLC_GROUP_COLUMNS,
                        branchSnapshot.plcGroups().stream().map(this::toPlcGroupRow).toList(),
                        parentSnapshot.plcGroups().stream().map(this::toPlcGroupRow).toList()
                ),
                buildDiffSection(
                        "plc-register",
                        JpaModelManagementSupport.PLC_REGISTER_COLUMNS,
                        toPlcRegisterRows(branchSnapshot.plcGroups(), branchSnapshot.plcRegisters()),
                        toPlcRegisterRows(parentSnapshot.plcGroups(), parentSnapshot.plcRegisters())
                )
        );
    }

    JpaModelManagementSupport.ModelDetailSnapshot loadSnapshot(final long modelVersionKey) {
        final List<TcModelPlcGroup> plcGroups = modelPlcGroupStore.findAllByModelVersionKey(modelVersionKey);
        final List<TcModelPlcRegister> plcRegisters = new ArrayList<>();
        for (TcModelPlcGroup group : plcGroups) {
            plcRegisters.addAll(modelPlcRegisterStore.findAllByGroupKey(group.groupKey()));
        }
        return new JpaModelManagementSupport.ModelDetailSnapshot(
                loadAllByModelVersionKey(modelVersionKey, modelParamStore::findAllByModelVersionKey),
                loadAllByModelVersionKey(modelVersionKey, modelSecsMessageStore::findAllByModelVersionKey),
                loadAllByModelVersionKey(modelVersionKey, modelSocketMessageStore::findAllByModelVersionKey),
                loadAllByModelVersionKey(modelVersionKey, modelMesMessageStore::findAllByModelVersionKey),
                loadAllByModelVersionKey(modelVersionKey, modelVariableIdStore::findAllByModelVersionKey),
                loadAllByModelVersionKey(modelVersionKey, modelReportIdStore::findAllByModelVersionKey),
                loadAllByModelVersionKey(modelVersionKey, modelEventIdStore::findAllByModelVersionKey),
                loadAllByModelVersionKey(modelVersionKey, modelMessageFlowStore::findAllByModelVersionKey),
                loadAllByModelVersionKey(modelVersionKey, modelMdfStore::findAllByModelVersionKey),
                loadAllByModelVersionKey(modelVersionKey, modelDcopItemStore::findAllByModelVersionKey),
                plcGroups,
                plcRegisters
        );
    }

    private ModelParentCommitPort.DiffSection buildDiffSection(
            final String detailNode,
            final List<String> columns,
            final List<JpaModelManagementSupport.DiffRow> branchRows,
            final List<JpaModelManagementSupport.DiffRow> parentRows
    ) {
        final Map<String, JpaModelManagementSupport.DiffRow> branchMap = toDiffRowMap(branchRows);
        final Map<String, JpaModelManagementSupport.DiffRow> parentMap = toDiffRowMap(parentRows);
        final Set<String> orderedKeys = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        orderedKeys.addAll(branchMap.keySet());
        orderedKeys.addAll(parentMap.keySet());

        final List<ModelParentCommitPort.DiffItem> added = new ArrayList<>();
        final List<ModelParentCommitPort.DiffItem> changed = new ArrayList<>();
        final List<ModelParentCommitPort.DiffItem> deleted = new ArrayList<>();

        for (String key : orderedKeys) {
            final JpaModelManagementSupport.DiffRow branchRow = branchMap.get(key);
            final JpaModelManagementSupport.DiffRow parentRow = parentMap.get(key);

            if (branchRow != null && parentRow == null) {
                added.add(new ModelParentCommitPort.DiffItem(key, branchRow.values(), List.of()));
                continue;
            }
            if (branchRow == null && parentRow != null) {
                deleted.add(new ModelParentCommitPort.DiffItem(key, List.of(), parentRow.values()));
                continue;
            }
            if (branchRow != null && parentRow != null && !branchRow.values().equals(parentRow.values())) {
                changed.add(new ModelParentCommitPort.DiffItem(key, branchRow.values(), parentRow.values()));
            }
        }

        return new ModelParentCommitPort.DiffSection(detailNode, columns, added, changed, deleted);
    }

    private <T> List<T> loadAllByModelVersionKey(
            final long modelVersionKey,
            final ModelVersionPageFetcher<T> fetcher
    ) {
        final List<T> collected = new ArrayList<>();
        int offset = 0;

        while (true) {
            final List<T> page = fetcher.fetch(modelVersionKey, PageRequest.of(offset, SCAN_PAGE_SIZE));
            if (page.isEmpty()) {
                break;
            }

            collected.addAll(page);
            if (page.size() < SCAN_PAGE_SIZE) {
                break;
            }
            offset += SCAN_PAGE_SIZE;
        }

        return collected;
    }

    private Map<String, JpaModelManagementSupport.DiffRow> toDiffRowMap(
            final List<JpaModelManagementSupport.DiffRow> rows
    ) {
        final Map<String, JpaModelManagementSupport.DiffRow> mapped = new LinkedHashMap<>();
        for (JpaModelManagementSupport.DiffRow row : rows) {
            mapped.put(row.identity(), row);
        }
        return mapped;
    }

    private JpaModelManagementSupport.DiffRow toParamRow(final TcModelParam param) {
        return new JpaModelManagementSupport.DiffRow(
                normalizeOptionalText(param.paramName()),
                List.of(
                        safe(param.paramName()),
                        safe(param.paramValue()),
                        safe(param.description())
                )
        );
    }

    private JpaModelManagementSupport.DiffRow toSecsMessageRow(final TcModelSecsMessage message) {
        return new JpaModelManagementSupport.DiffRow(
                normalizeOptionalText(message.secsMsgName()),
                List.of(
                        safe(message.secsMsgName()),
                        safe(message.description())
                )
        );
    }

    private JpaModelManagementSupport.DiffRow toSocketMessageRow(final TcModelSocketMessage message) {
        return new JpaModelManagementSupport.DiffRow(
                normalizeOptionalText(message.socketMsgName()),
                List.of(
                        safe(message.socketMsgName()),
                        safe(message.description())
                )
        );
    }

    private JpaModelManagementSupport.DiffRow toMesMessageRow(final TcModelMesMessage message) {
        return new JpaModelManagementSupport.DiffRow(
                normalizeOptionalText(message.mesMsgName()),
                List.of(
                        safe(message.mesMsgName()),
                        safe(message.description())
                )
        );
    }

    private JpaModelManagementSupport.DiffRow toVariableIdRow(final TcModelVariableId variableId) {
        final String identity = safe(variableId.variableIdType() == null ? null : variableId.variableIdType().name())
                + ":" + safe(variableId.variableId());
        return new JpaModelManagementSupport.DiffRow(
                identity,
                List.of(
                        safe(variableId.variableId()),
                        safe(variableId.variableIdType() == null ? null : variableId.variableIdType().name()),
                        safe(variableId.description())
                )
        );
    }

    private JpaModelManagementSupport.DiffRow toReportIdRow(final TcModelReportId reportId) {
        return new JpaModelManagementSupport.DiffRow(
                normalizeOptionalText(reportId.reportId()),
                List.of(
                        safe(reportId.reportId()),
                        safe(reportId.variableId()),
                        String.valueOf(reportId.enabled()),
                        safe(reportId.description())
                )
        );
    }

    private JpaModelManagementSupport.DiffRow toEventIdRow(final TcModelEventId eventId) {
        return new JpaModelManagementSupport.DiffRow(
                normalizeOptionalText(eventId.eventId()),
                List.of(
                        safe(eventId.eventId()),
                        safe(eventId.reportId()),
                        String.valueOf(eventId.enabled()),
                        safe(eventId.description())
                )
        );
    }

    private JpaModelManagementSupport.DiffRow toMessageFlowRow(final TcModelMessageFlow messageFlow) {
        final String identity = safe(messageFlow.workflowName()) + "|" + safe(messageFlow.messageName());
        return new JpaModelManagementSupport.DiffRow(
                identity,
                List.of(
                        safe(messageFlow.workflowName()),
                        safe(messageFlow.messageName()),
                        safe(messageFlow.eventId()),
                        safe(messageFlow.transactionId()),
                        safe(messageFlow.workflowFilter()),
                        safe(messageFlow.actionName()),
                        safe(messageFlow.actionDataIndex())
                )
        );
    }

    private JpaModelManagementSupport.DiffRow toMdfRow(final TcModelMdf mdf) {
        return new JpaModelManagementSupport.DiffRow(
                "MDF",
                List.of(
                        safe(mdf.mdfName()),
                        safe(new String(mdf.mdfFile() == null ? new byte[0] : mdf.mdfFile(), StandardCharsets.UTF_8))
                )
        );
    }

    private JpaModelManagementSupport.DiffRow toDcopItemRow(final TcModelDcopItem dcopItem) {
        return new JpaModelManagementSupport.DiffRow(
                normalizeOptionalText(dcopItem.dcopItemName()),
                List.of(
                        safe(dcopItem.dcopItemName()),
                        safe(dcopItem.eventId()),
                        safe(dcopItem.variableId()),
                        safe(dcopItem.collectionRule() == null ? null : dcopItem.collectionRule().name()),
                        safe(dcopItem.calculationRule()),
                        safe(dcopItem.orderRule() == null ? null : String.valueOf(dcopItem.orderRule()))
                )
        );
    }

    private JpaModelManagementSupport.DiffRow toPlcGroupRow(final TcModelPlcGroup group) {
        return new JpaModelManagementSupport.DiffRow(
                normalizeOptionalText(group.messageName()),
                List.of(
                        safe(group.messageName()),
                        safe(group.area() == null ? null : group.area().name()),
                        String.valueOf(group.startAddress()),
                        String.valueOf(group.readCount()),
                        String.valueOf(group.pollPeriodMs()),
                        safe(group.addressQualifier()),
                        safe(group.description())
                )
        );
    }

    private List<JpaModelManagementSupport.DiffRow> toPlcRegisterRows(
            final List<TcModelPlcGroup> groups,
            final List<TcModelPlcRegister> registers
    ) {
        final Map<Long, String> groupNameByKey = new HashMap<>();
        for (TcModelPlcGroup group : groups) {
            groupNameByKey.put(group.groupKey(), group.messageName());
        }
        return registers.stream()
                .map(register -> toPlcRegisterRow(register, groupNameByKey.getOrDefault(register.groupKey(), "")))
                .toList();
    }

    private JpaModelManagementSupport.DiffRow toPlcRegisterRow(
            final TcModelPlcRegister register,
            final String groupMessageName
    ) {
        // register 의 col0(Group)은 비고유 — identity 는 (group message_name, variable_name) 자연키로 잡아야 그룹별 충돌이 없다.
        return new JpaModelManagementSupport.DiffRow(
                safe(groupMessageName) + "|" + safe(register.variableName()),
                List.of(
                        safe(groupMessageName),
                        String.valueOf(register.wordOffset()),
                        safe(register.variableName()),
                        safe(register.dataType() == null ? null : register.dataType().name()),
                        numberToText(register.length()),
                        numberToText(register.bitIndex()),
                        numberToText(register.byteIndex()),
                        safe(register.byteOrder()),
                        safe(register.wordOrder()),
                        numberToText(register.scale()),
                        booleanToText(register.bcdSigned()),
                        safe(register.charEncoding()),
                        safe(register.description())
                )
        );
    }

    private String normalizeOptionalText(final String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private String safe(final String value) {
        return value == null ? "" : value;
    }

    private String numberToText(final Number value) {
        return value == null ? "" : value.toString();
    }

    private String booleanToText(final Boolean value) {
        return value == null ? "" : value.toString();
    }

    @FunctionalInterface
    private interface ModelVersionPageFetcher<T> {
        List<T> fetch(long modelVersionKey, PageRequest pageRequest);
    }
}

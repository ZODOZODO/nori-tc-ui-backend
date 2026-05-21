package com.nori.tc.ui.adapter.db;

import com.nori.tc.db.core.common.PageRequest;
import com.nori.tc.db.core.eqp.store.TcEqpStore;
import com.nori.tc.db.core.model.store.TcModelDcopItemStore;
import com.nori.tc.db.core.model.store.TcModelEventIdStore;
import com.nori.tc.db.core.model.store.TcModelMdfStore;
import com.nori.tc.db.core.model.store.TcModelMesMessageStore;
import com.nori.tc.db.core.model.store.TcModelParamStore;
import com.nori.tc.db.core.model.store.TcModelReportIdStore;
import com.nori.tc.db.core.model.store.TcModelSecsMessageStore;
import com.nori.tc.db.core.model.store.TcModelSocketMessageStore;
import com.nori.tc.db.core.model.store.TcModelStore;
import com.nori.tc.db.core.model.store.TcModelVariableIdStore;
import com.nori.tc.db.core.model.store.TcModelWorkflowStore;
import com.nori.tc.db.core.model.upsert.UpsertTcModel;
import com.nori.tc.db.domain.common.model.ModelStatus;
import com.nori.tc.db.domain.eqp.TcEqp;
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
import com.nori.tc.ui.core.exception.UiBadRequestException;
import com.nori.tc.ui.core.exception.UiConflictException;
import com.nori.tc.ui.core.exception.UiNotFoundException;
import com.nori.tc.ui.core.port.db.ModelParentCommitPort;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * JpaModelManagementPort의 상세 복제/비교/검증 보조 책임을 분리합니다.
 */
final class JpaModelManagementSupport {

    private static final int SCAN_PAGE_SIZE = 500;
    private static final int MODEL_NAME_MAX_LENGTH = 1000;
    private static final int MODEL_VERSION_MAX_LENGTH = 100;
    private static final String SYSTEM_USER = "SYSTEM";
    private static final DateTimeFormatter MODEL_VERSION_DATE_FORMATTER = DateTimeFormatter.ofPattern("yy.MM.dd");
    private static final int MODEL_VERSION_SEQUENCE_DIGITS = 4;
    static final List<String> MODEL_PARAMETER_COLUMNS = List.of(
            "Parameter Name",
            "Parameter Value",
            "Description"
    );
    static final List<String> SECS_MESSAGE_COLUMNS = List.of(
            "SECS Message Name",
            "Description"
    );
    static final List<String> SOCKET_MESSAGE_COLUMNS = List.of(
            "Socket Message Name",
            "Description"
    );
    static final List<String> VARIABLE_ID_COLUMNS = List.of(
            "VariableId",
            "VID Type",
            "Description"
    );
    static final List<String> REPORT_ID_COLUMNS = List.of(
            "ReportId",
            "VariableIdes",
            "Enabled",
            "Description"
    );
    static final List<String> EVENT_ID_COLUMNS = List.of(
            "EventId",
            "ReportIdes",
            "Enabled",
            "Description"
    );
    static final List<String> WORKFLOW_COLUMNS = List.of(
            "Workflow Name",
            "Message Name",
            "EventId",
            "TransactionId",
            "Filter",
            "Action Name",
            "Data Index"
    );
    static final List<String> MDF_COLUMNS = List.of(
            "MDF Name",
            "XML"
    );
    static final List<String> DCOP_ITEM_COLUMNS = List.of(
            "Dcop Item Name",
            "EventId",
            "VariableId",
            "Collection Rule",
            "Calculation Rule",
            "Order Rule"
    );

    private final TcModelStore modelStore;
    private final TcEqpStore eqpStore;
    private final JpaModelManagementDetailSupport detailSupport;

    JpaModelManagementSupport(
            final TcModelStore modelStore,
            final TcEqpStore eqpStore,
            final TcModelParamStore modelParamStore,
            final TcModelSecsMessageStore modelSecsMessageStore,
            final TcModelSocketMessageStore modelSocketMessageStore,
            final TcModelMesMessageStore modelMesMessageStore,
            final TcModelVariableIdStore modelVariableIdStore,
            final TcModelReportIdStore modelReportIdStore,
            final TcModelEventIdStore modelEventIdStore,
            final TcModelWorkflowStore modelWorkflowStore,
            final TcModelMdfStore modelMdfStore,
            final TcModelDcopItemStore modelDcopItemStore
    ) {
        this.modelStore = Objects.requireNonNull(modelStore, "modelStore is null");
        this.eqpStore = Objects.requireNonNull(eqpStore, "eqpStore is null");
        this.detailSupport = new JpaModelManagementDetailSupport(
                Objects.requireNonNull(modelParamStore, "modelParamStore is null"),
                Objects.requireNonNull(modelSecsMessageStore, "modelSecsMessageStore is null"),
                Objects.requireNonNull(modelSocketMessageStore, "modelSocketMessageStore is null"),
                Objects.requireNonNull(modelMesMessageStore, "modelMesMessageStore is null"),
                Objects.requireNonNull(modelVariableIdStore, "modelVariableIdStore is null"),
                Objects.requireNonNull(modelReportIdStore, "modelReportIdStore is null"),
                Objects.requireNonNull(modelEventIdStore, "modelEventIdStore is null"),
                Objects.requireNonNull(modelWorkflowStore, "modelWorkflowStore is null"),
                Objects.requireNonNull(modelMdfStore, "modelMdfStore is null"),
                Objects.requireNonNull(modelDcopItemStore, "modelDcopItemStore is null")
        );
    }

    String buildBranchModelName(final String parentModelName, final String suffix, final String currentUser) {
        final String branchModelName = parentModelName + "_" + suffix + "_" + currentUser;
        validateModelName(branchModelName);
        return branchModelName;
    }

    void ensureNoEqpReference(final List<TcModel> allModels, final Set<Long> targetModelKeys) {
        final Set<Long> targetVersionKeys = allModels.stream()
                .filter(model -> targetModelKeys.contains(model.modelKey()))
                .map(TcModel::modelVersionKey)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        if (targetVersionKeys.isEmpty()) {
            return;
        }

        final List<String> referencingEqpIds = loadAllEqps().stream()
                .filter(eqp -> targetVersionKeys.contains(eqp.modelVersionKey()))
                .map(TcEqp::eqpId)
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();

        if (!referencingEqpIds.isEmpty()) {
            throw new UiConflictException("해당 모델을 참조 중인 EQP가 있어 삭제할 수 없습니다.");
        }
    }

    void markAllVersionsDeprecated(final long branchModelKey, final List<TcModel> allModels, final String currentUser) {
        allModels.stream()
                .filter(model -> model.modelKey() == branchModelKey)
                .forEach(model -> modelStore.upsert(new UpsertTcModel(
                        model.modelVersionKey(),
                        model.modelName(),
                        model.parentModel(),
                        model.modelVersion(),
                        model.commInterface(),
                        ModelStatus.DEPRECATED,
                        model.description(),
                        model.maker(),
                        model.createdBy(),
                        currentUser
                )));
    }

    void cloneModelDetails(final long sourceModelVersionKey, final long targetModelVersionKey) {
        detailSupport.cloneModelDetails(sourceModelVersionKey, targetModelVersionKey);
    }

    void cloneSnapshotToVersion(final ModelDetailSnapshot snapshot, final long targetModelVersionKey) {
        detailSupport.cloneSnapshotToVersion(snapshot, targetModelVersionKey);
    }

    List<ModelParentCommitPort.DiffSection> buildDiffSections(
            final ModelDetailSnapshot branchSnapshot,
            final ModelDetailSnapshot parentSnapshot
    ) {
        return detailSupport.buildDiffSections(branchSnapshot, parentSnapshot);
    }

    TcModel requireLatestModel(final long modelKey) {
        return requireLatestModel(modelKey, loadAllModels());
    }

    TcModel requireLatestModel(final long modelKey, final List<TcModel> allModels) {
        validatePositiveModelKey(modelKey);
        return allModels.stream()
                .filter(model -> model.modelKey() == modelKey)
                .max(Comparator.comparingLong(TcModel::modelVersionKey))
                .orElseThrow(() -> new UiNotFoundException("모델을 찾을 수 없습니다."));
    }

    TcModel requireLatestModelByName(final String modelName, final List<TcModel> allModels) {
        final String normalizedModelName = normalizeRequiredText(modelName, "parentModel");
        return allModels.stream()
                .filter(model -> normalizedModelName.equals(model.modelName()))
                .max(Comparator.comparingLong(TcModel::modelVersionKey))
                .orElseThrow(() -> new UiNotFoundException("부모 모델을 찾을 수 없습니다."));
    }

    TcModel requireModelVersion(final long modelVersionKey, final List<TcModel> allModels) {
        if (modelVersionKey <= 0L) {
            throw new UiBadRequestException("modelVersionKey는 1 이상이어야 합니다.");
        }
        return allModels.stream()
                .filter(model -> model.modelVersionKey() == modelVersionKey)
                .findFirst()
                .orElseThrow(() -> new UiNotFoundException("모델을 찾을 수 없습니다."));
    }

    TcModel resolveBranchSourceModel(
            final JpaModelManagementPort.CreateBranchModelCommand command,
            final TcModel parentLatest,
            final List<TcModel> allModels
    ) {
        if (command.sourceModelVersionKey() == null) {
            return parentLatest;
        }

        final long sourceModelVersionKey = command.sourceModelVersionKey();
        final TcModel sourceModel = allModels.stream()
                .filter(model -> model.modelVersionKey() == sourceModelVersionKey)
                .findFirst()
                .orElseThrow(() -> new UiNotFoundException("복제할 모델 버전을 찾을 수 없습니다."));

        if (sourceModel.modelKey() != parentLatest.modelKey()) {
            throw new UiBadRequestException("선택한 복제 기준 버전이 현재 root model에 속하지 않습니다.");
        }

        ensureRootModel(sourceModel);
        return sourceModel;
    }

    String generateNextBranchBaselineVersion(final String branchModelName, final List<TcModel> allModels, final LocalDate currentDate) {
        return generateNextBaselineVersion(branchModelName, allModels, currentDate);
    }

    String generateNextBaselineVersion(final String modelName, final List<TcModel> allModels, final LocalDate currentDate) {
        final String versionPrefix = currentDate.format(MODEL_VERSION_DATE_FORMATTER) + ".";
        int maxSequence = -1;

        for (TcModel model : allModels) {
            if (!modelName.equals(model.modelName())) {
                continue;
            }

            final int sequence = extractDailySequence(model.modelVersion(), versionPrefix);
            if (sequence > maxSequence) {
                maxSequence = sequence;
            }
        }

        final int nextSequence = maxSequence + 1;
        return versionPrefix + String.format(Locale.ROOT, "%0" + MODEL_VERSION_SEQUENCE_DIGITS + "d", nextSequence);
    }

    void ensureRootModel(final TcModel model) {
        if (model.parentModel() != null && !model.parentModel().isBlank()) {
            throw new UiBadRequestException("root model만 처리할 수 있습니다.");
        }
    }

    void ensureBranchModel(final TcModel model) {
        if (model.parentModel() == null || model.parentModel().isBlank()) {
            throw new UiBadRequestException("branch model만 처리할 수 있습니다.");
        }
    }

    void ensureEditableByCurrentUser(final TcModel editModel, final String currentUser) {
        final String lockOwner = resolveLockOwner(editModel);
        if (lockOwner == null || lockOwner.equals(currentUser)) {
            return;
        }
        throw new UiConflictException(lockOwner + "님이 체크아웃한 모델이라 수정할 수 없습니다.");
    }

    Set<Long> resolveCascadeModelKeys(final TcModel targetModel, final Collection<TcModel> latestModels) {
        final Map<String, List<TcModel>> childrenByParentModel = latestModels.stream()
                .filter(model -> model.parentModel() != null && !model.parentModel().isBlank())
                .collect(Collectors.groupingBy(TcModel::parentModel));

        final Set<Long> resolvedModelKeys = new LinkedHashSet<>();
        collectCascadeModelKeys(targetModel.modelName(), targetModel.modelKey(), childrenByParentModel, resolvedModelKeys);
        return resolvedModelKeys;
    }

    private void collectCascadeModelKeys(
            final String modelName,
            final long modelKey,
            final Map<String, List<TcModel>> childrenByParentModel,
            final Set<Long> collector
    ) {
        if (!collector.add(modelKey)) {
            return;
        }

        final List<TcModel> children = childrenByParentModel.getOrDefault(modelName, List.of());
        for (TcModel child : children) {
            collectCascadeModelKeys(child.modelName(), child.modelKey(), childrenByParentModel, collector);
        }
    }

    List<TcModel> loadAllModels() {
        return scanAllPages(modelStore::findAll);
    }

    boolean existsModelName(final String modelName) {
        return loadAllModels().stream().anyMatch(model -> modelName.equals(model.modelName()));
    }

    ModelParentCommitPort.CommitParentResult buildCommitResult(
            final boolean committed,
            final TcModel branchLatest,
            final TcModel parentLatest,
            final String newParentVersion,
            final Long committedParentModelVersionKey,
            final List<ModelParentCommitPort.DiffSection> sections
    ) {
        return new ModelParentCommitPort.CommitParentResult(
                committed,
                branchLatest.modelKey(),
                parentLatest.modelKey(),
                branchLatest.modelName(),
                parentLatest.modelName(),
                branchLatest.modelVersion(),
                parentLatest.modelVersion(),
                newParentVersion,
                committedParentModelVersionKey,
                sections
        );
    }

    RuntimeException translateException(
            final RuntimeException exception,
            final String conflictMessage,
            final String badRequestMessage
    ) {
        if (exception instanceof UiBadRequestException
                || exception instanceof UiConflictException
                || exception instanceof UiNotFoundException) {
            return exception;
        }
        if (UiDbAdapterExceptionSupport.isBadRequest(exception)) {
            return new UiBadRequestException(badRequestMessage, exception);
        }
        if (UiDbAdapterExceptionSupport.isConflict(exception)) {
            return new UiConflictException(conflictMessage, exception);
        }
        return exception;
    }

    void validateCreateRootCommand(final JpaModelManagementPort.CreateRootModelCommand command) {
        if (command == null) {
            throw new UiBadRequestException("root model 생성 요청이 비어 있습니다.");
        }
        validateModelName(command.modelName());
        if (command.commInterface() == null) {
            throw new UiBadRequestException("commInterface는 필수입니다.");
        }
    }

    void validateUpdateRootModelInfoCommand(final JpaModelManagementPort.UpdateRootModelInfoCommand command) {
        if (command == null) {
            throw new UiBadRequestException("root model 수정 요청이 비어 있습니다.");
        }
        validatePositiveModelKey(command.modelKey());
    }

    void validateCreateBranchCommand(final JpaModelManagementPort.CreateBranchModelCommand command) {
        if (command == null) {
            throw new UiBadRequestException("branch model 생성 요청이 비어 있습니다.");
        }
        validatePositiveModelKey(command.parentModelKey());
        normalizeRequiredText(command.suffix(), "suffix");
        if (command.sourceModelVersionKey() != null && command.sourceModelVersionKey() <= 0) {
            throw new UiBadRequestException("sourceModelVersionKey는 1 이상이어야 합니다.");
        }
    }

    void validateCheckoutBranchVersionCommand(final JpaModelManagementPort.CheckoutBranchVersionCommand command) {
        if (command == null) {
            throw new UiBadRequestException("branch checkout 요청이 비어 있습니다.");
        }
        if (command.sourceModelVersionKey() <= 0L) {
            throw new UiBadRequestException("sourceModelVersionKey는 1 이상이어야 합니다.");
        }
    }

    void validateCheckinBranchEditVersionCommand(final JpaModelManagementPort.CheckinBranchEditVersionCommand command) {
        if (command == null) {
            throw new UiBadRequestException("branch checkin 요청이 비어 있습니다.");
        }
        if (command.editModelVersionKey() <= 0L) {
            throw new UiBadRequestException("editModelVersionKey는 1 이상이어야 합니다.");
        }
    }

    void validateCommitParentCommand(final JpaModelManagementPort.CommitParentCommand command) {
        if (command == null) {
            throw new UiBadRequestException("parent commit 요청이 비어 있습니다.");
        }
        validatePositiveModelKey(command.branchModelKey());
        if (command.applyCommit() && (command.newParentVersion() == null || command.newParentVersion().isBlank())) {
            throw new UiBadRequestException("newParentVersion은 필수입니다.");
        }
    }

    void validatePositiveModelKey(final long modelKey) {
        if (modelKey <= 0) {
            throw new UiBadRequestException("modelKey는 1 이상이어야 합니다.");
        }
    }

    void validateModelName(final String modelName) {
        final String normalizedModelName = normalizeRequiredText(modelName, "modelName");
        if (normalizedModelName.length() > MODEL_NAME_MAX_LENGTH) {
            throw new UiBadRequestException("modelName 길이는 1000자를 초과할 수 없습니다.");
        }
    }

    void validateModelVersion(final String modelVersion) {
        final String normalizedModelVersion = normalizeRequiredText(modelVersion, "modelVersion");
        if (normalizedModelVersion.length() > MODEL_VERSION_MAX_LENGTH) {
            throw new UiBadRequestException("modelVersion 길이는 100자를 초과할 수 없습니다.");
        }
    }

    String normalizeRequiredText(final String value, final String fieldName) {
        final String normalized = normalizeOptionalText(value);
        if (normalized == null) {
            throw new UiBadRequestException(fieldName + "은(는) 필수입니다.");
        }
        return normalized;
    }

    String normalizeOptionalText(final String value) {
        if (value == null) {
            return null;
        }
        final String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    String normalizeCurrentUser(final String currentUser) {
        final String normalized = normalizeOptionalText(currentUser);
        return normalized == null ? SYSTEM_USER : normalized;
    }

    private String resolveLockOwner(final TcModel model) {
        final String updatedBy = normalizeOptionalText(model.updatedBy());
        if (updatedBy != null) {
            return updatedBy;
        }
        return normalizeOptionalText(model.createdBy());
    }

    private List<TcEqp> loadAllEqps() {
        return scanAllPages(eqpStore::findAll);
    }

    ModelDetailSnapshot loadSnapshot(final long modelVersionKey) {
        return detailSupport.loadSnapshot(modelVersionKey);
    }

    private <T> List<T> scanAllPages(final Function<PageRequest, List<T>> fetcher) {
        final List<T> collected = new ArrayList<>();
        int offset = 0;

        while (true) {
            final List<T> page = fetcher.apply(PageRequest.of(offset, SCAN_PAGE_SIZE));
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

    Map<Long, TcModel> toLatestModelByKey(final List<TcModel> allModels) {
        final Map<Long, TcModel> latestByKey = new LinkedHashMap<>();
        for (TcModel model : allModels) {
            latestByKey.compute(model.modelKey(), (ignored, existing) -> {
                if (existing == null || existing.modelVersionKey() < model.modelVersionKey()) {
                    return model;
                }
                return existing;
            });
        }
        return latestByKey;
    }

    private int extractDailySequence(final String modelVersion, final String versionPrefix) {
        if (modelVersion == null || !modelVersion.startsWith(versionPrefix)) {
            return -1;
        }

        final String sequenceText = modelVersion.substring(versionPrefix.length());
        if (sequenceText.length() != MODEL_VERSION_SEQUENCE_DIGITS || !sequenceText.chars().allMatch(Character::isDigit)) {
            return -1;
        }

        return Integer.parseInt(sequenceText);
    }
    record DiffRow(
            String identity,
            List<String> values
    ) {
    }

    record ModelDetailSnapshot(
            List<TcModelParam> params,
            List<TcModelSecsMessage> secsMessages,
            List<TcModelSocketMessage> socketMessages,
            List<TcModelMesMessage> mesMessages,
            List<TcModelVariableId> variableIds,
            List<TcModelReportId> reportIds,
            List<TcModelEventId> eventIds,
            List<TcModelWorkflow> workflows,
            List<TcModelMdf> mdfs,
            List<TcModelDcopItem> dcopItems
    ) {
    }
}

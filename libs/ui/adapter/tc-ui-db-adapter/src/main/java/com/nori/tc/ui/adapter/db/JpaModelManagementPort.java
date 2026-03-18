package com.nori.tc.ui.adapter.db;

import com.nori.tc.db.core.common.PageRequest;
import com.nori.tc.db.core.eqp.store.TcEqpStore;
import com.nori.tc.db.core.model.store.TcModelDcopItemStore;
import com.nori.tc.db.core.model.store.TcModelEventIdStore;
import com.nori.tc.db.core.model.store.TcModelMdfStore;
import com.nori.tc.db.core.model.store.TcModelParamStore;
import com.nori.tc.db.core.model.store.TcModelReportIdStore;
import com.nori.tc.db.core.model.store.TcModelSecsMessageStore;
import com.nori.tc.db.core.model.store.TcModelSocketMessageStore;
import com.nori.tc.db.core.model.store.TcModelStore;
import com.nori.tc.db.core.model.store.TcModelVariableIdStore;
import com.nori.tc.db.core.model.store.TcModelWorkflowStore;
import com.nori.tc.db.core.model.upsert.UpsertTcModel;
import com.nori.tc.db.core.model.upsert.UpsertTcModelDcopItem;
import com.nori.tc.db.core.model.upsert.UpsertTcModelEventId;
import com.nori.tc.db.core.model.upsert.UpsertTcModelMdf;
import com.nori.tc.db.core.model.upsert.UpsertTcModelParam;
import com.nori.tc.db.core.model.upsert.UpsertTcModelReportId;
import com.nori.tc.db.core.model.upsert.UpsertTcModelSecsMessage;
import com.nori.tc.db.core.model.upsert.UpsertTcModelSocketMessage;
import com.nori.tc.db.core.model.upsert.UpsertTcModelVariableId;
import com.nori.tc.db.core.model.upsert.UpsertTcModelWorkflow;
import com.nori.tc.db.domain.common.model.ModelStatus;
import com.nori.tc.db.domain.eqp.TcEqp;
import com.nori.tc.db.domain.model.TcModel;
import com.nori.tc.db.domain.model.TcModelDcopItem;
import com.nori.tc.db.domain.model.TcModelEventId;
import com.nori.tc.db.domain.model.TcModelMdf;
import com.nori.tc.db.domain.model.TcModelParam;
import com.nori.tc.db.domain.model.TcModelReportId;
import com.nori.tc.db.domain.model.TcModelSecsMessage;
import com.nori.tc.db.domain.model.TcModelSocketMessage;
import com.nori.tc.db.domain.model.TcModelVariableId;
import com.nori.tc.db.domain.model.TcModelWorkflow;
import com.nori.tc.ui.core.exception.UiBadRequestException;
import com.nori.tc.ui.core.exception.UiConflictException;
import com.nori.tc.ui.core.exception.UiNotFoundException;
import com.nori.tc.ui.core.port.db.ModelBranchCommandPort;
import com.nori.tc.ui.core.port.db.ModelParentCommitPort;
import com.nori.tc.ui.core.port.db.ModelRootCommandPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
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
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * T3 model root/branch/parent commit 기능의 DB Store 기반 구현체입니다.
 *
 * <p>중요 정책:</p>
 * <ul>
 *   <li>root create는 항상 {@code parent_model=NULL, model_version=YY.MM.DD.0000, status=OPERATE}</li>
 *   <li>branch create는 일자형 임시 baseline version을 만들고, 실제 편집은 checkout 시 {@code EDIT}로 분리합니다.</li>
 *   <li>parent commit은 branch 최신 버전 전체를 parent 새 버전으로 복제합니다.</li>
 *   <li>root delete는 self FK cascade로 branch까지 함께 삭제되므로, 삭제 전 EQP 참조를 선검증합니다.</li>
 * </ul>
 */
@Repository
public class JpaModelManagementPort implements ModelRootCommandPort, ModelBranchCommandPort, ModelParentCommitPort {

    private static final Logger log = LoggerFactory.getLogger(JpaModelManagementPort.class);

    private static final int SCAN_PAGE_SIZE = 500;
    private static final int MODEL_NAME_MAX_LENGTH = 1000;
    private static final int MODEL_VERSION_MAX_LENGTH = 100;
    private static final String EDIT_VERSION = "EDIT";
    private static final String SYSTEM_USER = "SYSTEM";
    private static final ZoneId MODEL_VERSION_ZONE_ID = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter MODEL_VERSION_DATE_FORMATTER = DateTimeFormatter.ofPattern("yy.MM.dd");
    private static final int MODEL_VERSION_SEQUENCE_DIGITS = 4;
    private static final List<String> MODEL_PARAMETER_COLUMNS = List.of(
            "Parameter Name",
            "Parameter Value",
            "Description"
    );
    private static final List<String> SECS_MESSAGE_COLUMNS = List.of(
            "SECS Message Name",
            "Description",
            "Data Indexes"
    );
    private static final List<String> SOCKET_MESSAGE_COLUMNS = List.of(
            "Socket Message Name",
            "Description",
            "Data Indexes"
    );
    private static final List<String> VARIABLE_ID_COLUMNS = List.of(
            "VariableId",
            "VID Type",
            "Description"
    );
    private static final List<String> REPORT_ID_COLUMNS = List.of(
            "ReportId",
            "VariableIdes",
            "Enabled",
            "Description"
    );
    private static final List<String> EVENT_ID_COLUMNS = List.of(
            "EventId",
            "ReportIdes",
            "Enabled",
            "Description"
    );
    private static final List<String> WORKFLOW_COLUMNS = List.of(
            "Workflow Name",
            "Message Name",
            "EventId",
            "TransactionId",
            "Filter",
            "Action Name",
            "Data Index"
    );
    private static final List<String> MDF_COLUMNS = List.of(
            "MDF Name",
            "XML"
    );
    private static final List<String> DCOP_ITEM_COLUMNS = List.of(
            "Dcop Item Name",
            "Workflow Name",
            "EventId",
            "VariableId",
            "Collection Rule",
            "Calculation Rule",
            "Order Rule"
    );

    private final TcModelStore modelStore;
    private final TcEqpStore eqpStore;
    private final TcModelParamStore modelParamStore;
    private final TcModelSecsMessageStore modelSecsMessageStore;
    private final TcModelSocketMessageStore modelSocketMessageStore;
    private final TcModelVariableIdStore modelVariableIdStore;
    private final TcModelReportIdStore modelReportIdStore;
    private final TcModelEventIdStore modelEventIdStore;
    private final TcModelWorkflowStore modelWorkflowStore;
    private final TcModelMdfStore modelMdfStore;
    private final TcModelDcopItemStore modelDcopItemStore;

    /**
     * 필수 DB Store 의존성을 초기화합니다.
     */
    public JpaModelManagementPort(
            final TcModelStore modelStore,
            final TcEqpStore eqpStore,
            final TcModelParamStore modelParamStore,
            final TcModelSecsMessageStore modelSecsMessageStore,
            final TcModelSocketMessageStore modelSocketMessageStore,
            final TcModelVariableIdStore modelVariableIdStore,
            final TcModelReportIdStore modelReportIdStore,
            final TcModelEventIdStore modelEventIdStore,
            final TcModelWorkflowStore modelWorkflowStore,
            final TcModelMdfStore modelMdfStore,
            final TcModelDcopItemStore modelDcopItemStore
    ) {
        this.modelStore = Objects.requireNonNull(modelStore, "modelStore is null");
        this.eqpStore = Objects.requireNonNull(eqpStore, "eqpStore is null");
        this.modelParamStore = Objects.requireNonNull(modelParamStore, "modelParamStore is null");
        this.modelSecsMessageStore = Objects.requireNonNull(modelSecsMessageStore, "modelSecsMessageStore is null");
        this.modelSocketMessageStore = Objects.requireNonNull(modelSocketMessageStore, "modelSocketMessageStore is null");
        this.modelVariableIdStore = Objects.requireNonNull(modelVariableIdStore, "modelVariableIdStore is null");
        this.modelReportIdStore = Objects.requireNonNull(modelReportIdStore, "modelReportIdStore is null");
        this.modelEventIdStore = Objects.requireNonNull(modelEventIdStore, "modelEventIdStore is null");
        this.modelWorkflowStore = Objects.requireNonNull(modelWorkflowStore, "modelWorkflowStore is null");
        this.modelMdfStore = Objects.requireNonNull(modelMdfStore, "modelMdfStore is null");
        this.modelDcopItemStore = Objects.requireNonNull(modelDcopItemStore, "modelDcopItemStore is null");
    }

    /**
     * root model을 생성합니다.
     */
    @Override
    @Transactional
    public TcModel createRootModel(final CreateRootModelCommand command) {
        validateCreateRootCommand(command);
        final String modelName = normalizeRequiredText(command.modelName(), "modelName");
        final String currentUser = normalizeCurrentUser(command.currentUser());

        try {
            final List<TcModel> allModels = loadAllModels();
            if (allModels.stream().anyMatch(model -> modelName.equals(model.modelName()))) {
                throw new UiConflictException("동일한 모델 이름이 이미 존재합니다.");
            }
            final String rootBaselineVersion = generateNextBaselineVersion(modelName, allModels);

            final TcModel created = modelStore.upsert(new UpsertTcModel(
                    null,
                    modelName,
                    null,
                    rootBaselineVersion,
                    command.commInterface(),
                    ModelStatus.OPERATE,
                    null,
                    normalizeOptionalText(command.maker()),
                    currentUser,
                    currentUser
            ));

            log.info("root model 생성 완료. modelKey={}, modelName={}, commInterface={}",
                    created.modelKey(), created.modelName(), created.commInterface());
            return created;
        } catch (RuntimeException e) {
            throw translateException(e, "root model 생성 중 충돌이 발생했습니다.", "root model 생성 입력이 올바르지 않습니다.");
        }
    }

    /**
     * root model의 공통 정보를 수정합니다.
     */
    @Override
    @Transactional
    public TcModel updateRootModelInfo(final UpdateRootModelInfoCommand command) {
        validateUpdateRootModelInfoCommand(command);
        final String currentUser = normalizeCurrentUser(command.currentUser());

        try {
            final TcModel latestModel = requireLatestModel(command.modelKey());
            ensureRootModel(latestModel);

            final TcModel updated = modelStore.upsert(new UpsertTcModel(
                    latestModel.modelVersionKey(),
                    latestModel.modelName(),
                    null,
                    latestModel.modelVersion(),
                    latestModel.commInterface(),
                    latestModel.status(),
                    latestModel.description(),
                    normalizeOptionalText(command.maker()),
                    latestModel.createdBy(),
                    currentUser
            ));

            log.info("root model 정보 수정 완료. modelKey={}, modelName={}", updated.modelKey(), updated.modelName());
            return updated;
        } catch (RuntimeException e) {
            throw translateException(e, "root model 수정 중 충돌이 발생했습니다.", "root model 수정 입력이 올바르지 않습니다.");
        }
    }

    /**
     * model_key 기준으로 root 또는 branch 모델을 삭제합니다.
     */
    @Override
    @Transactional
    public void deleteModel(final long modelKey) {
        validatePositiveModelKey(modelKey);

        try {
            final List<TcModel> allModels = loadAllModels();
            final TcModel latestModel = requireLatestModel(modelKey, allModels);
            final Map<Long, TcModel> latestModelsByKey = toLatestModelByKey(allModels);
            final Set<Long> cascadeModelKeys = resolveCascadeModelKeys(latestModel, latestModelsByKey.values());
            ensureNoEqpReference(allModels, cascadeModelKeys);

            modelStore.deleteByModelKey(modelKey);

            log.info("model 삭제 완료. modelKey={}, modelName={}, cascadeModelKeys={}",
                    latestModel.modelKey(), latestModel.modelName(), cascadeModelKeys);
        } catch (RuntimeException e) {
            throw translateException(e, "해당 모델을 참조 중인 데이터가 있어 삭제할 수 없습니다.", "model 삭제 입력이 올바르지 않습니다.");
        }
    }

    /**
     * branch model을 생성합니다.
     */
    @Override
    @Transactional
    public TcModel createBranchModel(final CreateBranchModelCommand command) {
        validateCreateBranchCommand(command);
        final String currentUser = normalizeCurrentUser(command.currentUser());
        final String suffix = normalizeRequiredText(command.suffix(), "suffix");

        try {
            final List<TcModel> allModels = loadAllModels();
            final TcModel parentLatest = requireLatestModel(command.parentModelKey(), allModels);
            ensureRootModel(parentLatest);
            final TcModel sourceModel = resolveBranchSourceModel(command, parentLatest, allModels);

            final String branchModelName = buildBranchModelName(parentLatest.modelName(), suffix, currentUser);
            if (existsModelName(branchModelName)) {
                throw new UiConflictException("동일한 branch 모델 이름이 이미 존재합니다.");
            }
            final String branchBaselineVersion = generateNextBranchBaselineVersion(branchModelName, allModels);

            final TcModel branchCreated = modelStore.upsert(new UpsertTcModel(
                    null,
                    branchModelName,
                    parentLatest.modelName(),
                    branchBaselineVersion,
                    sourceModel.commInterface(),
                    ModelStatus.DEVELOP,
                    sourceModel.description(),
                    sourceModel.maker(),
                    currentUser,
                    currentUser
            ));

            cloneModelDetails(sourceModel.modelVersionKey(), branchCreated.modelVersionKey());

            log.info("branch model 생성 완료. parentModelKey={}, sourceModelVersionKey={}, branchModelKey={}, branchModelName={}",
                    parentLatest.modelKey(), sourceModel.modelVersionKey(), branchCreated.modelKey(), branchCreated.modelName());
            return branchCreated;
        } catch (RuntimeException e) {
            throw translateException(e, "branch model 생성 중 충돌이 발생했습니다.", "branch model 생성 입력이 올바르지 않습니다.");
        }
    }

    /**
     * branch version을 EDIT version으로 checkout합니다.
     */
    @Override
    @Transactional
    public TcModel checkoutBranchVersion(final CheckoutBranchVersionCommand command) {
        validateCheckoutBranchVersionCommand(command);
        final String currentUser = normalizeCurrentUser(command.currentUser());

        try {
            final List<TcModel> allModels = loadAllModels();
            final TcModel sourceModel = requireModelVersion(command.sourceModelVersionKey(), allModels);
            ensureBranchModel(sourceModel);

            if (sourceModel.status() == ModelStatus.DEPRECATED) {
                throw new UiConflictException("DEPRECATED branch model은 checkout할 수 없습니다.");
            }

            final Optional<TcModel> existingEdit = allModels.stream()
                    .filter(model -> model.modelKey() == sourceModel.modelKey())
                    .filter(model -> EDIT_VERSION.equalsIgnoreCase(model.modelVersion()))
                    .max(Comparator.comparingLong(TcModel::modelVersionKey));

            if (existingEdit.isPresent()) {
                ensureEditableByCurrentUser(existingEdit.get(), currentUser);
                return existingEdit.get();
            }

            final TcModel checkedOut = modelStore.upsert(new UpsertTcModel(
                    null,
                    sourceModel.modelName(),
                    sourceModel.parentModel(),
                    EDIT_VERSION,
                    sourceModel.commInterface(),
                    ModelStatus.DEVELOP,
                    sourceModel.description(),
                    sourceModel.maker(),
                    currentUser,
                    currentUser
            ));

            cloneModelDetails(sourceModel.modelVersionKey(), checkedOut.modelVersionKey());
            log.info("branch checkout 완료. sourceModelVersionKey={}, checkedOutModelVersionKey={}, modelKey={}",
                    sourceModel.modelVersionKey(), checkedOut.modelVersionKey(), checkedOut.modelKey());
            return checkedOut;
        } catch (RuntimeException e) {
            throw translateException(e, "branch checkout 중 충돌이 발생했습니다.", "branch checkout 입력이 올바르지 않습니다.");
        }
    }

    /**
     * EDIT version을 새 branch version으로 checkin합니다.
     */
    @Override
    @Transactional
    public TcModel checkinBranchEditVersion(final CheckinBranchEditVersionCommand command) {
        validateCheckinBranchEditVersionCommand(command);
        final String currentUser = normalizeCurrentUser(command.currentUser());
        final String newVersion = normalizeRequiredText(command.newVersion(), "newVersion");

        try {
            final List<TcModel> allModels = loadAllModels();
            final TcModel editModel = requireModelVersion(command.editModelVersionKey(), allModels);
            ensureBranchModel(editModel);

            if (!EDIT_VERSION.equalsIgnoreCase(editModel.modelVersion())) {
                throw new UiBadRequestException("EDIT version만 checkin할 수 있습니다.");
            }

            ensureEditableByCurrentUser(editModel, currentUser);
            validateModelVersion(newVersion);

            if (modelStore.findByNameVersion(editModel.modelName(), newVersion).isPresent()) {
                throw new UiConflictException("동일한 branch version이 이미 존재합니다.");
            }

            final TcModel checkedIn = modelStore.upsert(new UpsertTcModel(
                    null,
                    editModel.modelName(),
                    editModel.parentModel(),
                    newVersion,
                    editModel.commInterface(),
                    editModel.status(),
                    normalizeOptionalText(command.description()),
                    editModel.maker(),
                    currentUser,
                    currentUser
            ));

            cloneModelDetails(editModel.modelVersionKey(), checkedIn.modelVersionKey());
            modelStore.deleteByModelVersionKey(editModel.modelVersionKey());

            log.info("branch checkin 완료. editModelVersionKey={}, checkedInModelVersionKey={}, newVersion={}",
                    editModel.modelVersionKey(), checkedIn.modelVersionKey(), newVersion);
            return checkedIn;
        } catch (RuntimeException e) {
            throw translateException(e, "branch checkin 중 충돌이 발생했습니다.", "branch checkin 입력이 올바르지 않습니다.");
        }
    }

    /**
     * deprecated branch를 일괄 삭제합니다.
     */
    @Override
    @Transactional
    public DeleteDeprecatedBranchesResult deleteDeprecatedBranches(final long parentModelKey) {
        validatePositiveModelKey(parentModelKey);

        try {
            final List<TcModel> allModels = loadAllModels();
            final TcModel parentLatest = requireLatestModel(parentModelKey, allModels);
            ensureRootModel(parentLatest);

            final Map<Long, TcModel> latestModelsByKey = toLatestModelByKey(allModels);
            final List<TcModel> deprecatedBranches = latestModelsByKey.values().stream()
                    .filter(model -> parentLatest.modelName().equals(model.parentModel()))
                    .filter(model -> model.status() == ModelStatus.DEPRECATED)
                    .sorted(Comparator.comparing(TcModel::modelName, String.CASE_INSENSITIVE_ORDER))
                    .toList();

            if (deprecatedBranches.isEmpty()) {
                return new DeleteDeprecatedBranchesResult(0, List.of(), List.of());
            }

            ensureNoEqpReference(allModels, deprecatedBranches.stream().map(TcModel::modelKey).collect(Collectors.toSet()));

            for (TcModel deprecatedBranch : deprecatedBranches) {
                modelStore.deleteByModelKey(deprecatedBranch.modelKey());
            }

            final List<Long> deletedModelKeys = deprecatedBranches.stream().map(TcModel::modelKey).toList();
            final List<String> deletedModelNames = deprecatedBranches.stream().map(TcModel::modelName).toList();
            log.info("deprecated branch 일괄 삭제 완료. parentModelKey={}, deletedCount={}", parentModelKey, deletedModelKeys.size());

            return new DeleteDeprecatedBranchesResult(
                    deletedModelKeys.size(),
                    deletedModelKeys,
                    deletedModelNames
            );
        } catch (RuntimeException e) {
            throw translateException(e, "deprecated branch 삭제 중 충돌이 발생했습니다.", "deprecated branch 삭제 입력이 올바르지 않습니다.");
        }
    }

    /**
     * branch 최신 버전과 parent 최신 버전의 diff를 계산하고 필요 시 commit까지 수행합니다.
     */
    @Override
    @Transactional
    public CommitParentResult previewOrCommit(final CommitParentCommand command) {
        validateCommitParentCommand(command);
        final String currentUser = normalizeCurrentUser(command.currentUser());

        try {
            final List<TcModel> allModels = loadAllModels();
            final TcModel branchLatest = requireLatestModel(command.branchModelKey(), allModels);
            ensureBranchModel(branchLatest);

            final TcModel parentLatest = requireLatestModelByName(branchLatest.parentModel(), allModels);
            final ModelDetailSnapshot branchSnapshot = loadSnapshot(branchLatest.modelVersionKey());
            final ModelDetailSnapshot parentSnapshot = loadSnapshot(parentLatest.modelVersionKey());
            final List<DiffSection> sections = buildDiffSections(branchSnapshot, parentSnapshot);

            if (!command.applyCommit()) {
                return buildCommitResult(false, branchLatest, parentLatest, null, null, sections);
            }

            final String newParentVersion = normalizeRequiredText(command.newParentVersion(), "newParentVersion");
            validateModelVersion(newParentVersion);

            if (modelStore.findByNameVersion(parentLatest.modelName(), newParentVersion).isPresent()) {
                throw new UiConflictException("동일한 parent version이 이미 존재합니다.");
            }

            final TcModel committedParentVersion = modelStore.upsert(new UpsertTcModel(
                    null,
                    parentLatest.modelName(),
                    parentLatest.parentModel(),
                    newParentVersion,
                    parentLatest.commInterface(),
                    ModelStatus.OPERATE,
                    branchLatest.description(),
                    parentLatest.maker(),
                    currentUser,
                    currentUser
            ));

            cloneSnapshotToVersion(branchSnapshot, committedParentVersion.modelVersionKey());
            markAllVersionsDeprecated(branchLatest.modelKey(), allModels, currentUser);

            log.info("branch parent commit 완료. branchModelKey={}, parentModelKey={}, newParentVersion={}, committedParentModelVersionKey={}",
                    branchLatest.modelKey(), parentLatest.modelKey(), newParentVersion, committedParentVersion.modelVersionKey());

            return buildCommitResult(
                    true,
                    branchLatest,
                    parentLatest,
                    newParentVersion,
                    committedParentVersion.modelVersionKey(),
                    sections
            );
        } catch (RuntimeException e) {
            throw translateException(e, "parent commit 처리 중 충돌이 발생했습니다.", "parent commit 입력이 올바르지 않습니다.");
        }
    }

    /**
     * branch 이름 규칙을 생성합니다.
     *
     * <p>최종 길이는 DB 컬럼 제약(model_name 1000)을 초과하지 않도록 선검증합니다.</p>
     */
    private String buildBranchModelName(final String parentModelName, final String suffix, final String currentUser) {
        final String branchModelName = parentModelName + "_" + suffix + "_" + currentUser;
        validateModelName(branchModelName);
        return branchModelName;
    }

    /**
     * root/branch delete 전 EQP 참조를 선검증합니다.
     *
     * <p>root delete는 branch cascade가 함께 발생하므로 삭제 대상 model_key 집합 전체를 기준으로 검사합니다.</p>
     */
    private void ensureNoEqpReference(final List<TcModel> allModels, final Set<Long> targetModelKeys) {
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

    /**
     * branch의 모든 버전을 DEPRECATED로 변경합니다.
     *
     * <p>기존 상세 편집 흐름을 깨뜨리지 않기 위해 version row를 물리 삭제하지 않고 상태만 내립니다.</p>
     */
    private void markAllVersionsDeprecated(final long branchModelKey, final List<TcModel> allModels, final String currentUser) {
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

    /**
     * source modelVersionKey의 상세 데이터를 target modelVersionKey로 복제합니다.
     */
    private void cloneModelDetails(final long sourceModelVersionKey, final long targetModelVersionKey) {
        cloneSnapshotToVersion(loadSnapshot(sourceModelVersionKey), targetModelVersionKey);
    }

    /**
     * 미리 적재한 상세 스냅샷을 target version으로 복제합니다.
     *
     * <p>parent commit은 "parent 최신 + diff 적용"과 결과가 동일하므로,
     * branch 최신 전체를 새 parent version으로 그대로 복제하는 방식으로 단순화합니다.</p>
     */
    private void cloneSnapshotToVersion(final ModelDetailSnapshot snapshot, final long targetModelVersionKey) {
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
                message.description(),
                message.dataIndex()
        )));
        snapshot.socketMessages().forEach(message -> modelSocketMessageStore.upsert(new UpsertTcModelSocketMessage(
                targetModelVersionKey,
                message.socketMsgName(),
                message.description(),
                message.dataIndex(),
                null
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
        snapshot.workflows().forEach(workflow -> modelWorkflowStore.upsert(new UpsertTcModelWorkflow(
                null,
                targetModelVersionKey,
                workflow.workflowName(),
                workflow.messageName(),
                workflow.eventId(),
                workflow.transactionId(),
                workflow.workflowFilter(),
                workflow.actionName(),
                workflow.actionDataIndex()
        )));
        snapshot.mdfs().forEach(mdf -> modelMdfStore.upsert(new UpsertTcModelMdf(
                null,
                targetModelVersionKey,
                mdf.mdfName(),
                mdf.mdfFile()
        )));
        snapshot.dcopItems().forEach(dcopItem -> modelDcopItemStore.upsert(new UpsertTcModelDcopItem(
                targetModelVersionKey,
                dcopItem.dcopItemName(),
                dcopItem.workflowName(),
                dcopItem.eventId(),
                dcopItem.variableId(),
                dcopItem.collectionRule(),
                dcopItem.calculationRule(),
                dcopItem.orderRule()
        )));
    }

    /**
     * branch와 parent의 상세 스냅샷을 노드별 diff 섹션으로 변환합니다.
     */
    private List<DiffSection> buildDiffSections(final ModelDetailSnapshot branchSnapshot, final ModelDetailSnapshot parentSnapshot) {
        return List.of(
                buildDiffSection(
                        "model-parameter",
                        MODEL_PARAMETER_COLUMNS,
                        branchSnapshot.params().stream().map(this::toParamRow).toList(),
                        parentSnapshot.params().stream().map(this::toParamRow).toList()
                ),
                buildDiffSection(
                        "secs-message",
                        SECS_MESSAGE_COLUMNS,
                        branchSnapshot.secsMessages().stream().map(this::toSecsMessageRow).toList(),
                        parentSnapshot.secsMessages().stream().map(this::toSecsMessageRow).toList()
                ),
                buildDiffSection(
                        "socket-message",
                        SOCKET_MESSAGE_COLUMNS,
                        branchSnapshot.socketMessages().stream().map(this::toSocketMessageRow).toList(),
                        parentSnapshot.socketMessages().stream().map(this::toSocketMessageRow).toList()
                ),
                buildDiffSection(
                        "variableides",
                        VARIABLE_ID_COLUMNS,
                        branchSnapshot.variableIds().stream().map(this::toVariableIdRow).toList(),
                        parentSnapshot.variableIds().stream().map(this::toVariableIdRow).toList()
                ),
                buildDiffSection(
                        "reportides",
                        REPORT_ID_COLUMNS,
                        branchSnapshot.reportIds().stream().map(this::toReportIdRow).toList(),
                        parentSnapshot.reportIds().stream().map(this::toReportIdRow).toList()
                ),
                buildDiffSection(
                        "eventides",
                        EVENT_ID_COLUMNS,
                        branchSnapshot.eventIds().stream().map(this::toEventIdRow).toList(),
                        parentSnapshot.eventIds().stream().map(this::toEventIdRow).toList()
                ),
                buildDiffSection(
                        "workflow",
                        WORKFLOW_COLUMNS,
                        branchSnapshot.workflows().stream().map(this::toWorkflowRow).toList(),
                        parentSnapshot.workflows().stream().map(this::toWorkflowRow).toList()
                ),
                buildDiffSection(
                        "mdf",
                        MDF_COLUMNS,
                        branchSnapshot.mdfs().stream().map(this::toMdfRow).toList(),
                        parentSnapshot.mdfs().stream().map(this::toMdfRow).toList()
                ),
                buildDiffSection(
                        "dcop-itemes",
                        DCOP_ITEM_COLUMNS,
                        branchSnapshot.dcopItems().stream().map(this::toDcopItemRow).toList(),
                        parentSnapshot.dcopItems().stream().map(this::toDcopItemRow).toList()
                )
        );
    }

    /**
     * 단일 상세 노드의 추가/변경/삭제 diff를 계산합니다.
     */
    private DiffSection buildDiffSection(
            final String detailNode,
            final List<String> columns,
            final List<DiffRow> branchRows,
            final List<DiffRow> parentRows
    ) {
        final Map<String, DiffRow> branchMap = toDiffRowMap(branchRows);
        final Map<String, DiffRow> parentMap = toDiffRowMap(parentRows);
        final Set<String> orderedKeys = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        orderedKeys.addAll(branchMap.keySet());
        orderedKeys.addAll(parentMap.keySet());

        final List<DiffItem> added = new ArrayList<>();
        final List<DiffItem> changed = new ArrayList<>();
        final List<DiffItem> deleted = new ArrayList<>();

        for (String key : orderedKeys) {
            final DiffRow branchRow = branchMap.get(key);
            final DiffRow parentRow = parentMap.get(key);

            if (branchRow != null && parentRow == null) {
                added.add(new DiffItem(key, branchRow.values(), List.of()));
                continue;
            }
            if (branchRow == null && parentRow != null) {
                deleted.add(new DiffItem(key, List.of(), parentRow.values()));
                continue;
            }
            if (branchRow != null && parentRow != null && !branchRow.values().equals(parentRow.values())) {
                changed.add(new DiffItem(key, branchRow.values(), parentRow.values()));
            }
        }

        return new DiffSection(detailNode, columns, added, changed, deleted);
    }

    /**
     * model 최신 버전을 조회합니다.
     */
    private TcModel requireLatestModel(final long modelKey) {
        return requireLatestModel(modelKey, loadAllModels());
    }

    /**
     * 이미 적재한 전체 모델 목록 기준으로 최신 버전을 조회합니다.
     */
    private TcModel requireLatestModel(final long modelKey, final List<TcModel> allModels) {
        validatePositiveModelKey(modelKey);
        return allModels.stream()
                .filter(model -> model.modelKey() == modelKey)
                .max(Comparator.comparingLong(TcModel::modelVersionKey))
                .orElseThrow(() -> new UiNotFoundException("모델을 찾을 수 없습니다."));
    }

    /**
     * model_name 기준 최신 버전을 조회합니다.
     */
    private TcModel requireLatestModelByName(final String modelName, final List<TcModel> allModels) {
        final String normalizedModelName = normalizeRequiredText(modelName, "parentModel");
        return allModels.stream()
                .filter(model -> normalizedModelName.equals(model.modelName()))
                .max(Comparator.comparingLong(TcModel::modelVersionKey))
                .orElseThrow(() -> new UiNotFoundException("부모 모델을 찾을 수 없습니다."));
    }

    /**
     * model_version_key 기준 단건을 조회합니다.
     */
    private TcModel requireModelVersion(final long modelVersionKey, final List<TcModel> allModels) {
        if (modelVersionKey <= 0L) {
            throw new UiBadRequestException("modelVersionKey는 1 이상이어야 합니다.");
        }
        return allModels.stream()
                .filter(model -> model.modelVersionKey() == modelVersionKey)
                .findFirst()
                .orElseThrow(() -> new UiNotFoundException("모델을 찾을 수 없습니다."));
    }

    /**
     * branch 생성 시 복제할 root source 버전을 결정합니다.
     *
     * <p>sourceModelVersionKey가 없으면 현재 최신 root 버전을 사용하고,
     * 값이 있으면 같은 root model_key에 속한 지정 버전을 사용합니다.</p>
     */
    private TcModel resolveBranchSourceModel(
            final CreateBranchModelCommand command,
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

    /**
     * branch 생성 직후 사용할 임시 baseline version 문자열을 생성합니다.
     *
     * <p>branch create 단계부터 {@code EDIT}를 사용하면 checkout/undo 의미가 겹치므로,
     * EQP param 버전과 동일한 형식 {@code YY.MM.DD.0000}을 baseline version에 사용합니다.
     * 동일 이름 branch의 기존 버전이 남아 있으면 같은 날짜 prefix 안에서 sequence를 증가시킵니다.</p>
     */
    private String generateNextBranchBaselineVersion(final String branchModelName, final List<TcModel> allModels) {
        return generateNextBaselineVersion(branchModelName, allModels);
    }

    /**
     * model 생성 직후 사용할 일자형 baseline version 문자열을 생성합니다.
     *
     * <p>root/branch 모두 checkout 전에는 읽기 전용 기준선 버전을 가져야 하므로,
     * EQP param 버전과 동일한 형식 {@code YY.MM.DD.0000}을 사용합니다.
     * 같은 model_name 안에서만 sequence를 증가시켜 버전 충돌을 방지합니다.</p>
     */
    private String generateNextBaselineVersion(final String modelName, final List<TcModel> allModels) {
        final String versionPrefix = resolveCurrentVersionDate().format(MODEL_VERSION_DATE_FORMATTER) + ".";
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

    /**
     * 자동 버전 생성 기준 날짜를 반환합니다.
     *
     * <p>운영에서는 Asia/Seoul 현재 날짜를 사용하고,
     * 테스트에서는 동일 패키지 오버라이드로 고정 날짜를 주입할 수 있습니다.</p>
     */
    LocalDate resolveCurrentVersionDate() {
        return LocalDate.now(MODEL_VERSION_ZONE_ID);
    }

    /**
     * 오늘자 일자형 자동 버전과 일치하면 sequence를 반환하고, 아니면 -1을 반환합니다.
     */
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

    /**
     * root model 여부를 검증합니다.
     */
    private void ensureRootModel(final TcModel model) {
        if (model.parentModel() != null && !model.parentModel().isBlank()) {
            throw new UiBadRequestException("root model만 처리할 수 있습니다.");
        }
    }

    /**
     * branch model 여부를 검증합니다.
     */
    private void ensureBranchModel(final TcModel model) {
        if (model.parentModel() == null || model.parentModel().isBlank()) {
            throw new UiBadRequestException("branch model만 처리할 수 있습니다.");
        }
    }

    /**
     * EDIT 잠금 소유권을 현재 사용자 기준으로 검증합니다.
     */
    private void ensureEditableByCurrentUser(final TcModel editModel, final String currentUser) {
        final String lockOwner = resolveLockOwner(editModel);
        if (lockOwner == null || lockOwner.equals(currentUser)) {
            return;
        }
        throw new UiConflictException(lockOwner + "님이 체크아웃한 모델이라 수정할 수 없습니다.");
    }

    /**
     * 삭제 시 함께 제거될 cascade 대상 model_key 집합을 계산합니다.
     */
    private Set<Long> resolveCascadeModelKeys(final TcModel targetModel, final Collection<TcModel> latestModels) {
        final Map<String, List<TcModel>> childrenByParentModel = latestModels.stream()
                .filter(model -> model.parentModel() != null && !model.parentModel().isBlank())
                .collect(Collectors.groupingBy(TcModel::parentModel));

        final Set<Long> resolvedModelKeys = new LinkedHashSet<>();
        collectCascadeModelKeys(targetModel.modelName(), targetModel.modelKey(), childrenByParentModel, resolvedModelKeys);
        return resolvedModelKeys;
    }

    /**
     * parent_model self FK cascade 규칙을 코드에서도 같은 방식으로 추적합니다.
     */
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

    /**
     * 전체 모델 목록을 페이지 스캔으로 적재합니다.
     *
     * <p>현재 Store 계약에 model_key 전용 조회가 없으므로, management 기능에서는 전체 스캔 후 필터링합니다.</p>
     */
    private List<TcModel> loadAllModels() {
        return scanAllPages(modelStore::findAll);
    }

    /**
     * 전체 EQP 목록을 페이지 스캔으로 적재합니다.
     */
    private List<TcEqp> loadAllEqps() {
        return scanAllPages(eqpStore::findAll);
    }

    /**
     * model_version_key의 상세 하위 데이터를 모두 적재합니다.
     */
    private ModelDetailSnapshot loadSnapshot(final long modelVersionKey) {
        return new ModelDetailSnapshot(
                loadAllByModelVersionKey(modelVersionKey, modelParamStore::findAllByModelVersionKey),
                loadAllByModelVersionKey(modelVersionKey, modelSecsMessageStore::findAllByModelVersionKey),
                loadAllByModelVersionKey(modelVersionKey, modelSocketMessageStore::findAllByModelVersionKey),
                loadAllByModelVersionKey(modelVersionKey, modelVariableIdStore::findAllByModelVersionKey),
                loadAllByModelVersionKey(modelVersionKey, modelReportIdStore::findAllByModelVersionKey),
                loadAllByModelVersionKey(modelVersionKey, modelEventIdStore::findAllByModelVersionKey),
                loadAllByModelVersionKey(modelVersionKey, modelWorkflowStore::findAllByModelVersionKey),
                loadAllByModelVersionKey(modelVersionKey, modelMdfStore::findAllByModelVersionKey),
                loadAllByModelVersionKey(modelVersionKey, modelDcopItemStore::findAllByModelVersionKey)
        );
    }

    /**
     * 공통 페이지 스캔 유틸리티입니다.
     */
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

    /**
     * model_version_key + 페이지 계약을 공통 처리하는 유틸리티입니다.
     */
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

    /**
     * model_key 기준 최신 버전 매핑을 생성합니다.
     */
    private Map<Long, TcModel> toLatestModelByKey(final List<TcModel> allModels) {
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

    /**
     * model_name 존재 여부를 확인합니다.
     */
    private boolean existsModelName(final String modelName) {
        return loadAllModels().stream().anyMatch(model -> modelName.equals(model.modelName()));
    }

    /**
     * diff 계산용 맵을 생성합니다.
     */
    private Map<String, DiffRow> toDiffRowMap(final List<DiffRow> rows) {
        final Map<String, DiffRow> mapped = new LinkedHashMap<>();

        for (DiffRow row : rows) {
            mapped.put(row.identity(), row);
        }

        return mapped;
    }

    /**
     * 요청 결과 객체를 생성합니다.
     */
    private CommitParentResult buildCommitResult(
            final boolean committed,
            final TcModel branchLatest,
            final TcModel parentLatest,
            final String newParentVersion,
            final Long committedParentModelVersionKey,
            final List<DiffSection> sections
    ) {
        return new CommitParentResult(
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

    /**
     * 상세 스냅샷을 diff 계산용 행으로 변환합니다.
     */
    private DiffRow toParamRow(final TcModelParam param) {
        return new DiffRow(
                normalizeOptionalText(param.paramName()),
                List.of(
                        safe(param.paramName()),
                        safe(param.paramValue()),
                        safe(param.description())
                )
        );
    }

    private DiffRow toSecsMessageRow(final TcModelSecsMessage message) {
        return new DiffRow(
                normalizeOptionalText(message.secsMsgName()),
                List.of(
                        safe(message.secsMsgName()),
                        safe(message.description()),
                        safe(message.dataIndex())
                )
        );
    }

    private DiffRow toSocketMessageRow(final TcModelSocketMessage message) {
        return new DiffRow(
                normalizeOptionalText(message.socketMsgName()),
                List.of(
                        safe(message.socketMsgName()),
                        safe(message.description()),
                        safe(message.dataIndex())
                )
        );
    }

    private DiffRow toVariableIdRow(final TcModelVariableId variableId) {
        final String identity = safe(variableId.variableIdType() == null ? null : variableId.variableIdType().name())
                + ":" + safe(variableId.variableId());
        return new DiffRow(
                identity,
                List.of(
                        safe(variableId.variableId()),
                        safe(variableId.variableIdType() == null ? null : variableId.variableIdType().name()),
                        safe(variableId.description())
                )
        );
    }

    private DiffRow toReportIdRow(final TcModelReportId reportId) {
        return new DiffRow(
                normalizeOptionalText(reportId.reportId()),
                List.of(
                        safe(reportId.reportId()),
                        safe(reportId.variableId()),
                        String.valueOf(reportId.enabled()),
                        safe(reportId.description())
                )
        );
    }

    private DiffRow toEventIdRow(final TcModelEventId eventId) {
        return new DiffRow(
                normalizeOptionalText(eventId.eventId()),
                List.of(
                        safe(eventId.eventId()),
                        safe(eventId.reportId()),
                        String.valueOf(eventId.enabled()),
                        safe(eventId.description())
                )
        );
    }

    private DiffRow toWorkflowRow(final TcModelWorkflow workflow) {
        final String identity = safe(workflow.workflowName()) + "|" + safe(workflow.messageName());
        return new DiffRow(
                identity,
                List.of(
                        safe(workflow.workflowName()),
                        safe(workflow.messageName()),
                        safe(workflow.eventId()),
                        safe(workflow.transactionId()),
                        safe(workflow.workflowFilter()),
                        safe(workflow.actionName()),
                        safe(workflow.actionDataIndex())
                )
        );
    }

    private DiffRow toMdfRow(final TcModelMdf mdf) {
        return new DiffRow(
                "MDF",
                List.of(
                        safe(mdf.mdfName()),
                        safe(new String(mdf.mdfFile() == null ? new byte[0] : mdf.mdfFile(), StandardCharsets.UTF_8))
                )
        );
    }

    private DiffRow toDcopItemRow(final TcModelDcopItem dcopItem) {
        return new DiffRow(
                normalizeOptionalText(dcopItem.dcopItemName()),
                List.of(
                        safe(dcopItem.dcopItemName()),
                        safe(dcopItem.workflowName()),
                        safe(dcopItem.eventId()),
                        safe(dcopItem.variableId()),
                        safe(dcopItem.collectionRule() == null ? null : dcopItem.collectionRule().name()),
                        safe(dcopItem.calculationRule()),
                        safe(dcopItem.orderRule() == null ? null : String.valueOf(dcopItem.orderRule()))
                )
        );
    }

    /**
     * 예외를 400/404/409 의미에 맞게 변환합니다.
     */
    private RuntimeException translateException(
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

    /**
     * root create 입력을 검증합니다.
     */
    private void validateCreateRootCommand(final CreateRootModelCommand command) {
        if (command == null) {
            throw new UiBadRequestException("root model 생성 요청이 비어 있습니다.");
        }
        validateModelName(command.modelName());
        if (command.commInterface() == null) {
            throw new UiBadRequestException("commInterface는 필수입니다.");
        }
    }

    /**
     * model info update 입력을 검증합니다.
     */
    private void validateUpdateRootModelInfoCommand(final UpdateRootModelInfoCommand command) {
        if (command == null) {
            throw new UiBadRequestException("root model 수정 요청이 비어 있습니다.");
        }
        validatePositiveModelKey(command.modelKey());
    }

    /**
     * branch create 입력을 검증합니다.
     */
    private void validateCreateBranchCommand(final CreateBranchModelCommand command) {
        if (command == null) {
            throw new UiBadRequestException("branch model 생성 요청이 비어 있습니다.");
        }
        validatePositiveModelKey(command.parentModelKey());
        normalizeRequiredText(command.suffix(), "suffix");
        if (command.sourceModelVersionKey() != null && command.sourceModelVersionKey() <= 0) {
            throw new UiBadRequestException("sourceModelVersionKey는 1 이상이어야 합니다.");
        }
    }

    /**
     * branch checkout 입력을 검증합니다.
     */
    private void validateCheckoutBranchVersionCommand(final CheckoutBranchVersionCommand command) {
        if (command == null) {
            throw new UiBadRequestException("branch checkout 요청이 비어 있습니다.");
        }
        if (command.sourceModelVersionKey() <= 0L) {
            throw new UiBadRequestException("sourceModelVersionKey는 1 이상이어야 합니다.");
        }
    }

    /**
     * branch checkin 입력을 검증합니다.
     */
    private void validateCheckinBranchEditVersionCommand(final CheckinBranchEditVersionCommand command) {
        if (command == null) {
            throw new UiBadRequestException("branch checkin 요청이 비어 있습니다.");
        }
        if (command.editModelVersionKey() <= 0L) {
            throw new UiBadRequestException("editModelVersionKey는 1 이상이어야 합니다.");
        }
        normalizeRequiredText(command.newVersion(), "newVersion");
    }

    /**
     * parent commit 입력을 검증합니다.
     */
    private void validateCommitParentCommand(final CommitParentCommand command) {
        if (command == null) {
            throw new UiBadRequestException("parent commit 요청이 비어 있습니다.");
        }
        validatePositiveModelKey(command.branchModelKey());
        if (command.applyCommit() && (command.newParentVersion() == null || command.newParentVersion().isBlank())) {
            throw new UiBadRequestException("newParentVersion은 필수입니다.");
        }
    }

    /**
     * model_key 양수 여부를 검증합니다.
     */
    private void validatePositiveModelKey(final long modelKey) {
        if (modelKey <= 0) {
            throw new UiBadRequestException("modelKey는 1 이상이어야 합니다.");
        }
    }

    /**
     * 모델 이름 규칙과 길이를 검증합니다.
     */
    private void validateModelName(final String modelName) {
        final String normalizedModelName = normalizeRequiredText(modelName, "modelName");
        if (normalizedModelName.length() > MODEL_NAME_MAX_LENGTH) {
            throw new UiBadRequestException("modelName 길이는 1000자를 초과할 수 없습니다.");
        }
    }

    /**
     * 모델 버전 규칙과 길이를 검증합니다.
     */
    private void validateModelVersion(final String modelVersion) {
        final String normalizedModelVersion = normalizeRequiredText(modelVersion, "modelVersion");
        if (normalizedModelVersion.length() > MODEL_VERSION_MAX_LENGTH) {
            throw new UiBadRequestException("modelVersion 길이는 100자를 초과할 수 없습니다.");
        }
    }

    /**
     * 필수 문자열을 trim + blank 검증합니다.
     */
    private String normalizeRequiredText(final String value, final String fieldName) {
        final String normalized = normalizeOptionalText(value);
        if (normalized == null) {
            throw new UiBadRequestException(fieldName + "은(는) 필수입니다.");
        }
        return normalized;
    }

    /**
     * 선택 문자열을 trim 후 blank면 null로 정규화합니다.
     */
    private String normalizeOptionalText(final String value) {
        if (value == null) {
            return null;
        }
        final String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    /**
     * 현재 사용자 문자열을 감사 컬럼 기본값으로 정규화합니다.
     */
    private String normalizeCurrentUser(final String currentUser) {
        final String normalized = normalizeOptionalText(currentUser);
        return normalized == null ? SYSTEM_USER : normalized;
    }

    /**
     * EDIT 잠금 소유자를 updatedBy 우선 규칙으로 계산합니다.
     */
    private String resolveLockOwner(final TcModel model) {
        final String updatedBy = normalizeOptionalText(model.updatedBy());
        if (updatedBy != null) {
            return updatedBy;
        }
        return normalizeOptionalText(model.createdBy());
    }

    /**
     * null-safe 문자열 변환입니다.
     */
    private String safe(final String value) {
        return value == null ? "" : value;
    }

    /**
     * model_version_key 기반 페이징 조회 함수 시그니처입니다.
     */
    @FunctionalInterface
    private interface ModelVersionPageFetcher<T> {
        List<T> fetch(long modelVersionKey, PageRequest pageRequest);
    }

    /**
     * 상세 diff 계산용 행 모델입니다.
     */
    private record DiffRow(
            String identity,
            List<String> values
    ) {
    }

    /**
     * 모델 상세 스냅샷입니다.
     */
    private record ModelDetailSnapshot(
            List<TcModelParam> params,
            List<TcModelSecsMessage> secsMessages,
            List<TcModelSocketMessage> socketMessages,
            List<TcModelVariableId> variableIds,
            List<TcModelReportId> reportIds,
            List<TcModelEventId> eventIds,
            List<TcModelWorkflow> workflows,
            List<TcModelMdf> mdfs,
            List<TcModelDcopItem> dcopItems
    ) {
    }
}

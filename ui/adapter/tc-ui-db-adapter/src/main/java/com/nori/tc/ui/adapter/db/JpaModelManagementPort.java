package com.nori.tc.ui.adapter.db;

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
import com.nori.tc.db.core.model.store.TcModelMessageFlowStore;
import com.nori.tc.db.core.model.store.TcModelVariableIdStore;
import com.nori.tc.db.core.model.upsert.UpsertTcModel;
import com.nori.tc.db.core.model.upsert.UpsertTcModelMdf;
import com.nori.tc.db.domain.common.model.ModelStatus;
import com.nori.tc.db.domain.model.TcModel;
import com.nori.tc.ui.adapter.db.mapper.UiDomainEnumMapper;
import com.nori.tc.ui.adapter.db.mapper.UiModelDbMapper;
import com.nori.tc.ui.core.exception.UiBadRequestException;
import com.nori.tc.ui.core.exception.UiConflictException;
import com.nori.tc.ui.core.port.db.ModelBranchCommandPort;
import com.nori.tc.ui.core.port.db.ModelParentCommitPort;
import com.nori.tc.ui.core.port.db.ModelRootCommandPort;
import com.nori.tc.ui.domain.model.UiModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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

    private static final String EDIT_VERSION = "EDIT";
    private static final String DEFAULT_ROOT_MDF_NAME = "MDF";
    private static final byte[] DEFAULT_ROOT_MDF_XML = """
            <mdf>
              <message name="DEFAULT_MES_MESSAGE" target="MES" output="KAFKA">
                <field name="EQPID" var="EQPID" required="false"/>
              </message>
            </mdf>
            """.getBytes(StandardCharsets.UTF_8);

    private final TcModelStore modelStore;
    private final TcModelMdfStore modelMdfStore;
    private final JpaModelManagementSupport support;

    /**
     * 필수 DB Store 의존성을 초기화합니다.
     */
    public JpaModelManagementPort(
            final TcModelStore modelStore,
            final TcEqpStore eqpStore,
            final TcModelParamStore modelParamStore,
            final TcModelSecsMessageStore modelSecsMessageStore,
            final TcModelSocketMessageStore modelSocketMessageStore,
            final TcModelMesMessageStore modelMesMessageStore,
            final TcModelVariableIdStore modelVariableIdStore,
            final TcModelReportIdStore modelReportIdStore,
            final TcModelEventIdStore modelEventIdStore,
            final TcModelMessageFlowStore modelMessageFlowStore,
            final TcModelMdfStore modelMdfStore,
            final TcModelDcopItemStore modelDcopItemStore
    ) {
        this.modelStore = Objects.requireNonNull(modelStore, "modelStore is null");
        this.modelMdfStore = Objects.requireNonNull(modelMdfStore, "modelMdfStore is null");
        this.support = new JpaModelManagementSupport(
                modelStore,
                eqpStore,
                modelParamStore,
                modelSecsMessageStore,
                modelSocketMessageStore,
                modelMesMessageStore,
                modelVariableIdStore,
                modelReportIdStore,
                modelEventIdStore,
                modelMessageFlowStore,
                modelMdfStore,
                modelDcopItemStore
        );
    }

    /**
     * root model을 생성합니다.
     */
    @Override
    @Transactional
    public UiModel createRootModel(final CreateRootModelCommand command) {
        support.validateCreateRootCommand(command);
        final String modelName = support.normalizeRequiredText(command.modelName(), "modelName");
        final String currentUser = support.normalizeCurrentUser(command.currentUser());

        try {
            final List<TcModel> allModels = loadAllModels();
            if (allModels.stream().anyMatch(model -> modelName.equals(model.modelName()))) {
                throw new UiConflictException("동일한 모델 이름이 이미 존재합니다.");
            }
            final String rootBaselineVersion = support.generateNextBaselineVersion(modelName, allModels, resolveCurrentVersionDate());

            final TcModel created = modelStore.upsert(new UpsertTcModel(
                    null,
                    modelName,
                    null,
                    rootBaselineVersion,
                    UiDomainEnumMapper.toDbProtocolType(command.commInterface()),
                    ModelStatus.OPERATE,
                    null,
                    support.normalizeOptionalText(command.maker()),
                    currentUser,
                    currentUser
            ));
            saveDefaultRootMdf(created.modelVersionKey());

            log.info("root model 생성 완료. modelKey={}, modelName={}, commInterface={}",
                    created.modelKey(), created.modelName(), created.commInterface());
            return UiModelDbMapper.toDomain(created);
        } catch (RuntimeException e) {
            throw support.translateException(e, "root model 생성 중 충돌이 발생했습니다.", "root model 생성 입력이 올바르지 않습니다.");
        }
    }

    /**
     * business 런타임이 최소 1개의 메시지 정의를 기대하므로 root 생성 시 기본 MDF를 함께 저장합니다.
     */
    private void saveDefaultRootMdf(final long modelVersionKey) {
        modelMdfStore.upsert(new UpsertTcModelMdf(
                null,
                modelVersionKey,
                DEFAULT_ROOT_MDF_NAME,
                DEFAULT_ROOT_MDF_XML
        ));
    }

    /**
     * root model의 공통 정보를 수정합니다.
     */
    @Override
    @Transactional
    public UiModel updateRootModelInfo(final UpdateRootModelInfoCommand command) {
        support.validateUpdateRootModelInfoCommand(command);
        final String currentUser = support.normalizeCurrentUser(command.currentUser());

        try {
            final TcModel latestModel = support.requireLatestModel(command.modelKey());
            support.ensureRootModel(latestModel);

            final TcModel updated = modelStore.upsert(new UpsertTcModel(
                    latestModel.modelVersionKey(),
                    latestModel.modelName(),
                    null,
                    latestModel.modelVersion(),
                    latestModel.commInterface(),
                    latestModel.status(),
                    latestModel.description(),
                    support.normalizeOptionalText(command.maker()),
                    latestModel.createdBy(),
                    currentUser
            ));

            log.info("root model 정보 수정 완료. modelKey={}, modelName={}", updated.modelKey(), updated.modelName());
            return UiModelDbMapper.toDomain(updated);
        } catch (RuntimeException e) {
            throw support.translateException(e, "root model 수정 중 충돌이 발생했습니다.", "root model 수정 입력이 올바르지 않습니다.");
        }
    }

    /**
     * model_key 기준으로 root 또는 branch 모델을 삭제합니다.
     */
    @Override
    @Transactional
    public void deleteModel(final long modelKey) {
        support.validatePositiveModelKey(modelKey);

        try {
            final List<TcModel> allModels = loadAllModels();
            final TcModel latestModel = support.requireLatestModel(modelKey, allModels);
            final Map<Long, TcModel> latestModelsByKey = support.toLatestModelByKey(allModels);
            final Set<Long> cascadeModelKeys = support.resolveCascadeModelKeys(latestModel, latestModelsByKey.values());
            support.ensureNoEqpReference(allModels, cascadeModelKeys);

            modelStore.deleteByModelKey(modelKey);

            log.info("model 삭제 완료. modelKey={}, modelName={}, cascadeModelKeys={}",
                    latestModel.modelKey(), latestModel.modelName(), cascadeModelKeys);
        } catch (RuntimeException e) {
            throw support.translateException(e, "해당 모델을 참조 중인 데이터가 있어 삭제할 수 없습니다.", "model 삭제 입력이 올바르지 않습니다.");
        }
    }

    /**
     * branch model을 생성합니다.
     */
    @Override
    @Transactional
    public UiModel createBranchModel(final CreateBranchModelCommand command) {
        support.validateCreateBranchCommand(command);
        final String currentUser = support.normalizeCurrentUser(command.currentUser());
        final String suffix = support.normalizeRequiredText(command.suffix(), "suffix");

        try {
            final List<TcModel> allModels = loadAllModels();
            final TcModel parentLatest = support.requireLatestModel(command.parentModelKey(), allModels);
            support.ensureRootModel(parentLatest);
            final TcModel sourceModel = support.resolveBranchSourceModel(command, parentLatest, allModels);

            final String branchModelName = support.buildBranchModelName(parentLatest.modelName(), suffix, currentUser);
            if (support.existsModelName(branchModelName)) {
                throw new UiConflictException("동일한 branch 모델 이름이 이미 존재합니다.");
            }
            final String branchBaselineVersion = support.generateNextBranchBaselineVersion(
                    branchModelName,
                    allModels,
                    resolveCurrentVersionDate()
            );

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

            support.cloneModelDetails(sourceModel.modelVersionKey(), branchCreated.modelVersionKey());

            log.info("branch model 생성 완료. parentModelKey={}, sourceModelVersionKey={}, branchModelKey={}, branchModelName={}",
                    parentLatest.modelKey(), sourceModel.modelVersionKey(), branchCreated.modelKey(), branchCreated.modelName());
            return UiModelDbMapper.toDomain(branchCreated);
        } catch (RuntimeException e) {
            throw support.translateException(e, "branch model 생성 중 충돌이 발생했습니다.", "branch model 생성 입력이 올바르지 않습니다.");
        }
    }

    /**
     * branch version을 EDIT version으로 checkout합니다.
     */
    @Override
    @Transactional
    public UiModel checkoutBranchVersion(final CheckoutBranchVersionCommand command) {
        support.validateCheckoutBranchVersionCommand(command);
        final String currentUser = support.normalizeCurrentUser(command.currentUser());

        try {
            final List<TcModel> allModels = loadAllModels();
            final TcModel sourceModel = support.requireModelVersion(command.sourceModelVersionKey(), allModels);
            support.ensureBranchModel(sourceModel);

            if (sourceModel.status() == ModelStatus.DEPRECATED) {
                throw new UiConflictException("DEPRECATED branch model은 checkout할 수 없습니다.");
            }

            final Optional<TcModel> existingEdit = allModels.stream()
                    .filter(model -> model.modelKey() == sourceModel.modelKey())
                    .filter(model -> EDIT_VERSION.equalsIgnoreCase(model.modelVersion()))
                    .max(Comparator.comparingLong(TcModel::modelVersionKey));

            if (existingEdit.isPresent()) {
                support.ensureEditableByCurrentUser(existingEdit.get(), currentUser);
                return UiModelDbMapper.toDomain(existingEdit.get());
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

            support.cloneModelDetails(sourceModel.modelVersionKey(), checkedOut.modelVersionKey());
            log.info("branch checkout 완료. sourceModelVersionKey={}, checkedOutModelVersionKey={}, modelKey={}",
                    sourceModel.modelVersionKey(), checkedOut.modelVersionKey(), checkedOut.modelKey());
            return UiModelDbMapper.toDomain(checkedOut);
        } catch (RuntimeException e) {
            throw support.translateException(e, "branch checkout 중 충돌이 발생했습니다.", "branch checkout 입력이 올바르지 않습니다.");
        }
    }

    /**
     * EDIT version을 새 branch version으로 checkin합니다.
     */
    @Override
    @Transactional
    public UiModel checkinBranchEditVersion(final CheckinBranchEditVersionCommand command) {
        support.validateCheckinBranchEditVersionCommand(command);
        final String currentUser = support.normalizeCurrentUser(command.currentUser());

        try {
            final List<TcModel> allModels = loadAllModels();
            final TcModel editModel = support.requireModelVersion(command.editModelVersionKey(), allModels);
            support.ensureBranchModel(editModel);

            if (!EDIT_VERSION.equalsIgnoreCase(editModel.modelVersion())) {
                throw new UiBadRequestException("EDIT version만 checkin할 수 있습니다.");
            }

            support.ensureEditableByCurrentUser(editModel, currentUser);
            final String newVersion = support.generateNextBranchBaselineVersion(
                    editModel.modelName(),
                    allModels,
                    resolveCurrentVersionDate()
            );

            final TcModel checkedIn = modelStore.upsert(new UpsertTcModel(
                    null,
                    editModel.modelName(),
                    editModel.parentModel(),
                    newVersion,
                    editModel.commInterface(),
                    editModel.status(),
                    support.normalizeOptionalText(command.description()),
                    editModel.maker(),
                    currentUser,
                    currentUser
            ));

            support.cloneModelDetails(editModel.modelVersionKey(), checkedIn.modelVersionKey());
            modelStore.deleteByModelVersionKey(editModel.modelVersionKey());

            log.info("branch checkin 완료. editModelVersionKey={}, checkedInModelVersionKey={}, newVersion={}",
                    editModel.modelVersionKey(), checkedIn.modelVersionKey(), newVersion);
            return UiModelDbMapper.toDomain(checkedIn);
        } catch (RuntimeException e) {
            throw support.translateException(e, "branch checkin 중 충돌이 발생했습니다.", "branch checkin 입력이 올바르지 않습니다.");
        }
    }

    /**
     * deprecated branch를 일괄 삭제합니다.
     */
    @Override
    @Transactional
    public DeleteDeprecatedBranchesResult deleteDeprecatedBranches(final long parentModelKey) {
        support.validatePositiveModelKey(parentModelKey);

        try {
            final List<TcModel> allModels = loadAllModels();
            final TcModel parentLatest = support.requireLatestModel(parentModelKey, allModels);
            support.ensureRootModel(parentLatest);

            final Map<Long, TcModel> latestModelsByKey = support.toLatestModelByKey(allModels);
            final List<TcModel> deprecatedBranches = latestModelsByKey.values().stream()
                    .filter(model -> parentLatest.modelName().equals(model.parentModel()))
                    .filter(model -> model.status() == ModelStatus.DEPRECATED)
                    .sorted(Comparator.comparing(TcModel::modelName, String.CASE_INSENSITIVE_ORDER))
                    .toList();

            if (deprecatedBranches.isEmpty()) {
                return new DeleteDeprecatedBranchesResult(0, List.of(), List.of());
            }

            support.ensureNoEqpReference(allModels, deprecatedBranches.stream().map(TcModel::modelKey).collect(Collectors.toSet()));

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
            throw support.translateException(e, "deprecated branch 삭제 중 충돌이 발생했습니다.", "deprecated branch 삭제 입력이 올바르지 않습니다.");
        }
    }

    /**
     * branch 최신 버전과 parent 최신 버전의 diff를 계산하고 필요 시 commit까지 수행합니다.
     */
    @Override
    @Transactional
    public CommitParentResult previewOrCommit(final CommitParentCommand command) {
        support.validateCommitParentCommand(command);
        final String currentUser = support.normalizeCurrentUser(command.currentUser());

        try {
            final List<TcModel> allModels = loadAllModels();
            final TcModel branchLatest = support.requireLatestModel(command.branchModelKey(), allModels);
            support.ensureBranchModel(branchLatest);

            final TcModel parentLatest = support.requireLatestModelByName(branchLatest.parentModel(), allModels);
            final JpaModelManagementSupport.ModelDetailSnapshot branchSnapshot =
                    support.loadSnapshot(branchLatest.modelVersionKey());
            final JpaModelManagementSupport.ModelDetailSnapshot parentSnapshot =
                    support.loadSnapshot(parentLatest.modelVersionKey());
            final List<DiffSection> sections = support.buildDiffSections(branchSnapshot, parentSnapshot);

            if (!command.applyCommit()) {
                return support.buildCommitResult(false, branchLatest, parentLatest, null, null, sections);
            }

            final String newParentVersion = support.normalizeRequiredText(command.newParentVersion(), "newParentVersion");
            support.validateModelVersion(newParentVersion);

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

            support.cloneSnapshotToVersion(branchSnapshot, committedParentVersion.modelVersionKey());
            support.markAllVersionsDeprecated(branchLatest.modelKey(), allModels, currentUser);

            log.info("branch parent commit 완료. branchModelKey={}, parentModelKey={}, newParentVersion={}, committedParentModelVersionKey={}",
                    branchLatest.modelKey(), parentLatest.modelKey(), newParentVersion, committedParentVersion.modelVersionKey());

            return support.buildCommitResult(
                    true,
                    branchLatest,
                    parentLatest,
                    newParentVersion,
                    committedParentVersion.modelVersionKey(),
                    sections
            );
        } catch (RuntimeException e) {
            throw support.translateException(e, "parent commit 처리 중 충돌이 발생했습니다.", "parent commit 입력이 올바르지 않습니다.");
        }
    }

    private List<TcModel> loadAllModels() {
        return support.loadAllModels();
    }

    /**
     * 자동 버전 생성 기준 날짜를 반환합니다.
     *
     * <p>운영에서는 Asia/Seoul 현재 날짜를 사용하고,
     * 테스트에서는 동일 패키지 오버라이드로 고정 날짜를 주입할 수 있습니다.</p>
     */
    LocalDate resolveCurrentVersionDate() {
        return LocalDate.now(java.time.ZoneId.of("Asia/Seoul"));
    }
}

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
import com.nori.tc.db.core.model.upsert.UpsertTcModelParam;
import com.nori.tc.db.domain.common.model.ModelStatus;
import com.nori.tc.db.domain.common.model.ProtocolType;
import com.nori.tc.db.domain.common.model.VariableIdType;
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
import com.nori.tc.ui.core.port.db.ModelBranchCommandPort;
import com.nori.tc.ui.core.port.db.ModelParentCommitPort;
import com.nori.tc.ui.core.port.db.ModelRootCommandPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link JpaModelManagementPort}의 T3 핵심 정책을 검증합니다.
 */
class JpaModelManagementPortTest {

    private static final LocalDate FIXED_VERSION_DATE = LocalDate.of(2026, 3, 13);
    private static final String INITIAL_ROOT_VERSION = dailyVersion(FIXED_VERSION_DATE, 0);
    private static final String INITIAL_BRANCH_VERSION = dailyVersion(FIXED_VERSION_DATE, 0);

    @Test
    @DisplayName("root model 생성은 parent=NULL, 일자형 baseline version/OPERATE 고정값으로 저장합니다")
    void createRootModelUsesFixedDefaults() {
        final Fixture fixture = new Fixture();
        final TcModel created = model(1001L, 501L, "ROOT-SECS", null, INITIAL_ROOT_VERSION, ProtocolType.SECS, ModelStatus.OPERATE);
        when(fixture.modelStore.upsert(any())).thenReturn(created);

        final TcModel result = fixture.port.createRootModel(new ModelRootCommandPort.CreateRootModelCommand(
                "ROOT-SECS",
                ProtocolType.SECS,
                "NORI",
                "tester"
        ));

        final ArgumentCaptor<UpsertTcModel> captor = ArgumentCaptor.forClass(UpsertTcModel.class);
        verify(fixture.modelStore).upsert(captor.capture());

        assertEquals(created, result);
        assertNull(captor.getValue().parentModel());
        assertEquals(INITIAL_ROOT_VERSION, captor.getValue().modelVersion());
        assertEquals(ModelStatus.OPERATE, captor.getValue().status());
        assertEquals("tester", captor.getValue().createdBy());
        assertEquals("tester", captor.getValue().updatedBy());
    }

    @Test
    @DisplayName("root model 수정은 modelName/parent/status를 유지하고 maker만 변경합니다")
    void updateRootModelInfoKeepsImmutableFieldsAndUpdatesMaker() {
        final Fixture fixture = new Fixture();
        final TcModel latestRoot = model(1101L, 511L, "ROOT-SECS", null, "EDIT", ProtocolType.SECS, ModelStatus.OPERATE);
        final TcModel updatedRoot = new TcModel(
                latestRoot.modelVersionKey(),
                latestRoot.modelKey(),
                latestRoot.modelName(),
                latestRoot.parentModel(),
                latestRoot.modelVersion(),
                latestRoot.commInterface(),
                latestRoot.status(),
                latestRoot.description(),
                "NORI-UPDATED",
                latestRoot.createdAt(),
                latestRoot.updatedAt(),
                latestRoot.createdBy(),
                "tester"
        );
        fixture.allModels.add(latestRoot);
        when(fixture.modelStore.upsert(any())).thenReturn(updatedRoot);

        final TcModel result = fixture.port.updateRootModelInfo(new ModelRootCommandPort.UpdateRootModelInfoCommand(
                latestRoot.modelKey(),
                "NORI-UPDATED",
                "tester"
        ));

        final ArgumentCaptor<UpsertTcModel> captor = ArgumentCaptor.forClass(UpsertTcModel.class);
        verify(fixture.modelStore).upsert(captor.capture());

        assertEquals(updatedRoot, result);
        assertEquals(latestRoot.modelVersionKey(), captor.getValue().modelKey());
        assertEquals(latestRoot.modelName(), captor.getValue().modelName());
        assertNull(captor.getValue().parentModel());
        assertEquals(latestRoot.modelVersion(), captor.getValue().modelVersion());
        assertEquals(latestRoot.commInterface(), captor.getValue().commInterface());
        assertEquals(latestRoot.status(), captor.getValue().status());
        assertEquals("NORI-UPDATED", captor.getValue().maker());
        assertEquals(latestRoot.createdBy(), captor.getValue().createdBy());
        assertEquals("tester", captor.getValue().updatedBy());
    }

    @Test
    @DisplayName("branch model 생성은 부모 최신 버전 상세를 baseline/DEVELOP branch로 전체 복제합니다")
    void createBranchModelClonesParentLatestSnapshot() {
        final Fixture fixture = new Fixture();
        final TcModel parentLatest = model(2001L, 601L, "ROOT_MODEL", null, "1.2.0", ProtocolType.SOCKET, ModelStatus.OPERATE);
        final TcModel branchCreated = model(3001L, 701L, "ROOT_MODEL_feature_tester", "ROOT_MODEL", INITIAL_BRANCH_VERSION, ProtocolType.SOCKET, ModelStatus.DEVELOP);

        fixture.allModels.add(parentLatest);
        fixture.paramsByVersion.put(parentLatest.modelVersionKey(), List.of(param(parentLatest.modelVersionKey(), "SITE", "DEV")));
        fixture.secsMessagesByVersion.put(parentLatest.modelVersionKey(), List.of(secs(parentLatest.modelVersionKey(), "S1F1")));
        fixture.socketMessagesByVersion.put(parentLatest.modelVersionKey(), List.of(socket(parentLatest.modelVersionKey(), "CMD=PING")));
        fixture.variableIdsByVersion.put(parentLatest.modelVersionKey(), List.of(variable(parentLatest.modelVersionKey(), "VID_TEMP")));
        fixture.reportIdsByVersion.put(parentLatest.modelVersionKey(), List.of(report(parentLatest.modelVersionKey(), "RPT_01")));
        fixture.eventIdsByVersion.put(parentLatest.modelVersionKey(), List.of(event(parentLatest.modelVersionKey(), "EVT_01")));
        fixture.workflowsByVersion.put(parentLatest.modelVersionKey(), List.of(workflow(parentLatest.modelVersionKey(), "FLOW_01", "S1F1")));
        fixture.mdfsByVersion.put(parentLatest.modelVersionKey(), List.of(mdf(parentLatest.modelVersionKey(), "ROOT_XML")));
        fixture.dcopItemsByVersion.put(parentLatest.modelVersionKey(), List.of(dcop(parentLatest.modelVersionKey(), "TEMP")));

        when(fixture.modelStore.upsert(any())).thenReturn(branchCreated);

        final TcModel result = fixture.port.createBranchModel(new ModelBranchCommandPort.CreateBranchModelCommand(
                parentLatest.modelKey(),
                "feature",
                null,
                "tester"
        ));

        final ArgumentCaptor<UpsertTcModel> captor = ArgumentCaptor.forClass(UpsertTcModel.class);
        verify(fixture.modelStore).upsert(captor.capture());

        assertEquals(branchCreated, result);
        assertEquals("ROOT_MODEL_feature_tester", captor.getValue().modelName());
        assertEquals("ROOT_MODEL", captor.getValue().parentModel());
        assertEquals(INITIAL_BRANCH_VERSION, captor.getValue().modelVersion());
        assertEquals(ModelStatus.DEVELOP, captor.getValue().status());
        verify(fixture.modelParamStore, times(1)).upsert(any());
        verify(fixture.modelSecsMessageStore, times(1)).upsert(any());
        verify(fixture.modelSocketMessageStore, times(1)).upsert(any());
        verify(fixture.modelVariableIdStore, times(1)).upsert(any());
        verify(fixture.modelReportIdStore, times(1)).upsert(any());
        verify(fixture.modelEventIdStore, times(1)).upsert(any());
        verify(fixture.modelWorkflowStore, times(1)).upsert(any());
        verify(fixture.modelMdfStore, times(1)).upsert(any());
        verify(fixture.modelDcopItemStore, times(1)).upsert(any());
    }

    @Test
    @DisplayName("branch model 생성은 선택한 root source 버전 내용을 복제합니다")
    void createBranchModelClonesSelectedSourceVersion() {
        final Fixture fixture = new Fixture();
        final TcModel sourceVersion = model(
                2101L,
                611L,
                "ROOT_MODEL",
                null,
                "1.0.0",
                ProtocolType.SECS,
                ModelStatus.OPERATE,
                "source-desc",
                "SOURCE-MAKER"
        );
        final TcModel parentLatest = model(
                2102L,
                611L,
                "ROOT_MODEL",
                null,
                "2.0.0",
                ProtocolType.SOCKET,
                ModelStatus.OPERATE,
                "latest-desc",
                "LATEST-MAKER"
        );
        final TcModel branchCreated = model(3101L, 711L, "ROOT_MODEL_feature_tester", "ROOT_MODEL", INITIAL_BRANCH_VERSION, ProtocolType.SECS, ModelStatus.DEVELOP);

        fixture.allModels.add(sourceVersion);
        fixture.allModels.add(parentLatest);
        fixture.paramsByVersion.put(sourceVersion.modelVersionKey(), List.of(param(sourceVersion.modelVersionKey(), "SITE", "OLD")));
        fixture.paramsByVersion.put(parentLatest.modelVersionKey(), List.of(param(parentLatest.modelVersionKey(), "SITE", "NEW")));

        when(fixture.modelStore.upsert(any())).thenReturn(branchCreated);

        final TcModel result = fixture.port.createBranchModel(new ModelBranchCommandPort.CreateBranchModelCommand(
                parentLatest.modelKey(),
                "feature",
                sourceVersion.modelVersionKey(),
                "tester"
        ));

        final ArgumentCaptor<UpsertTcModel> modelCaptor = ArgumentCaptor.forClass(UpsertTcModel.class);
        final ArgumentCaptor<UpsertTcModelParam> paramCaptor = ArgumentCaptor.forClass(UpsertTcModelParam.class);
        verify(fixture.modelStore).upsert(modelCaptor.capture());
        verify(fixture.modelParamStore).upsert(paramCaptor.capture());

        assertEquals(branchCreated, result);
        assertEquals(INITIAL_BRANCH_VERSION, modelCaptor.getValue().modelVersion());
        assertEquals(sourceVersion.commInterface(), modelCaptor.getValue().commInterface());
        assertEquals(sourceVersion.description(), modelCaptor.getValue().description());
        assertEquals(sourceVersion.maker(), modelCaptor.getValue().maker());
        assertEquals("OLD", paramCaptor.getValue().paramValue());
    }

    @Test
    @DisplayName("branch model 생성은 source version과 관계없이 일자형 임시 버전을 사용합니다")
    void createBranchModelUsesTemporaryVersionWhenRootSourceIsEdit() {
        final Fixture fixture = new Fixture();
        final TcModel parentLatest = model(2051L, 605L, "ROOT_MODEL", null, "EDIT", ProtocolType.SECS, ModelStatus.OPERATE);
        final TcModel branchCreated = model(3051L, 705L, "ROOT_MODEL_feature_tester", "ROOT_MODEL", INITIAL_BRANCH_VERSION, ProtocolType.SECS, ModelStatus.DEVELOP);
        fixture.allModels.add(parentLatest);
        when(fixture.modelStore.upsert(any())).thenReturn(branchCreated);

        fixture.port.createBranchModel(new ModelBranchCommandPort.CreateBranchModelCommand(
                parentLatest.modelKey(),
                "feature",
                null,
                "tester"
        ));

        final ArgumentCaptor<UpsertTcModel> captor = ArgumentCaptor.forClass(UpsertTcModel.class);
        verify(fixture.modelStore).upsert(captor.capture());

        assertEquals(INITIAL_BRANCH_VERSION, captor.getValue().modelVersion());
    }

    @Test
    @DisplayName("parent commit preview는 추가/변경/삭제 diff를 모두 계산합니다")
    void previewCommitCalculatesAddedChangedDeleted() {
        final Fixture fixture = new Fixture();
        final TcModel parentLatest = model(4001L, 801L, "ROOT", null, "1.0.0", ProtocolType.SECS, ModelStatus.OPERATE);
        final TcModel branchLatest = model(5001L, 901L, "ROOT_feature_tester", "ROOT", "EDIT", ProtocolType.SECS, ModelStatus.DEVELOP);
        fixture.allModels.add(parentLatest);
        fixture.allModels.add(branchLatest);

        fixture.paramsByVersion.put(parentLatest.modelVersionKey(), List.of(
                param(parentLatest.modelVersionKey(), "COMMON", "A"),
                param(parentLatest.modelVersionKey(), "CHANGED", "OLD"),
                param(parentLatest.modelVersionKey(), "DELETE_ONLY", "X")
        ));
        fixture.paramsByVersion.put(branchLatest.modelVersionKey(), List.of(
                param(branchLatest.modelVersionKey(), "COMMON", "A"),
                param(branchLatest.modelVersionKey(), "CHANGED", "NEW"),
                param(branchLatest.modelVersionKey(), "ADDED_ONLY", "Y")
        ));

        final ModelParentCommitPort.CommitParentResult result = fixture.port.previewOrCommit(
                new ModelParentCommitPort.CommitParentCommand(branchLatest.modelKey(), false, null, "tester")
        );

        final ModelParentCommitPort.DiffSection paramSection = result.sections().stream()
                .filter(section -> "model-parameter".equals(section.detailNode()))
                .findFirst()
                .orElseThrow();

        assertFalse(result.committed());
        assertEquals(1, paramSection.added().size());
        assertEquals("ADDED_ONLY", paramSection.added().getFirst().identity());
        assertEquals(1, paramSection.changed().size());
        assertEquals("CHANGED", paramSection.changed().getFirst().identity());
        assertEquals(1, paramSection.deleted().size());
        assertEquals("DELETE_ONLY", paramSection.deleted().getFirst().identity());
    }

    @Test
    @DisplayName("parent commit 실행은 parent 새 버전을 만들고 branch 모든 버전을 DEPRECATED로 변경합니다")
    void commitParentCreatesNewVersionAndDeprecatesBranchVersions() {
        final Fixture fixture = new Fixture();
        final TcModel parentLatest = model(6001L, 1001L, "ROOT", null, "1.0.0", ProtocolType.SECS, ModelStatus.OPERATE);
        final TcModel branchOld = model(7001L, 1101L, "ROOT_feature_tester", "ROOT", "0.9.0", ProtocolType.SECS, ModelStatus.DEVELOP);
        final TcModel branchLatest = model(7002L, 1101L, "ROOT_feature_tester", "ROOT", "EDIT", ProtocolType.SECS, ModelStatus.DEVELOP);
        final TcModel committedParent = model(8001L, 1001L, "ROOT", null, "2.0.0", ProtocolType.SECS, ModelStatus.OPERATE);

        fixture.allModels.add(parentLatest);
        fixture.allModels.add(branchOld);
        fixture.allModels.add(branchLatest);
        fixture.paramsByVersion.put(branchLatest.modelVersionKey(), List.of(param(branchLatest.modelVersionKey(), "SITE", "BRANCH")));

        when(fixture.modelStore.upsert(any())).thenAnswer(invocation -> {
            final UpsertTcModel command = invocation.getArgument(0);
            if (command.modelKey() == null && "ROOT".equals(command.modelName()) && "2.0.0".equals(command.modelVersion())) {
                return committedParent;
            }
            return branchLatest;
        });

        final ModelParentCommitPort.CommitParentResult result = fixture.port.previewOrCommit(
                new ModelParentCommitPort.CommitParentCommand(branchLatest.modelKey(), true, "2.0.0", "tester")
        );

        final ArgumentCaptor<UpsertTcModel> captor = ArgumentCaptor.forClass(UpsertTcModel.class);
        verify(fixture.modelStore, times(3)).upsert(captor.capture());

        assertTrue(result.committed());
        assertEquals(committedParent.modelVersionKey(), result.committedParentModelVersionKey());
        assertTrue(captor.getAllValues().stream().anyMatch(command ->
                command.modelKey() == null
                        && "ROOT".equals(command.modelName())
                        && "2.0.0".equals(command.modelVersion())
                        && command.status() == ModelStatus.OPERATE
        ));
        assertTrue(captor.getAllValues().stream().anyMatch(command ->
                Long.valueOf(branchOld.modelVersionKey()).equals(command.modelKey())
                        && command.status() == ModelStatus.DEPRECATED
        ));
        assertTrue(captor.getAllValues().stream().anyMatch(command ->
                Long.valueOf(branchLatest.modelVersionKey()).equals(command.modelKey())
                        && command.status() == ModelStatus.DEPRECATED
        ));
        verify(fixture.modelParamStore, times(1)).upsert(any());
    }

    @Test
    @DisplayName("deprecated branch bulk delete는 최신 상태가 DEPRECATED인 branch만 삭제합니다")
    void deleteDeprecatedBranchesDeletesOnlyDeprecatedLatestBranches() {
        final Fixture fixture = new Fixture();
        final TcModel rootLatest = model(9001L, 1201L, "ROOT", null, "1.0.0", ProtocolType.SECS, ModelStatus.OPERATE);
        final TcModel deprecatedBranchOld = model(9101L, 1301L, "ROOT_old_tester", "ROOT", "0.9.0", ProtocolType.SECS, ModelStatus.DEVELOP);
        final TcModel deprecatedBranchLatest = model(9102L, 1301L, "ROOT_old_tester", "ROOT", "EDIT", ProtocolType.SECS, ModelStatus.DEPRECATED);
        final TcModel activeBranchLatest = model(9201L, 1401L, "ROOT_live_tester", "ROOT", "EDIT", ProtocolType.SECS, ModelStatus.DEVELOP);

        fixture.allModels.add(rootLatest);
        fixture.allModels.add(deprecatedBranchOld);
        fixture.allModels.add(deprecatedBranchLatest);
        fixture.allModels.add(activeBranchLatest);

        final ModelBranchCommandPort.DeleteDeprecatedBranchesResult result =
                fixture.port.deleteDeprecatedBranches(rootLatest.modelKey());

        assertEquals(1, result.deletedCount());
        assertEquals(List.of(deprecatedBranchLatest.modelKey()), result.deletedModelKeys());
        assertEquals(List.of(deprecatedBranchLatest.modelName()), result.deletedModelNames());
        verify(fixture.modelStore).deleteByModelKey(deprecatedBranchLatest.modelKey());
    }

    @Test
    @DisplayName("branch model 생성은 확장된 1000자 modelName 한도까지 허용합니다")
    void createBranchModelAllowsExtendedModelNameLength() {
        final Fixture fixture = new Fixture();
        final String parentModelName = "P".repeat(988);
        final String suffix = "S".repeat(4);
        final String currentUser = "tester";
        final String branchModelName = parentModelName + "_" + suffix + "_" + currentUser;
        final TcModel parentLatest = model(9401L, 1701L, parentModelName, null, "1.0.0", ProtocolType.SECS, ModelStatus.OPERATE);
        final TcModel branchCreated = model(9402L, 1702L, branchModelName, parentModelName, INITIAL_BRANCH_VERSION, ProtocolType.SECS, ModelStatus.DEVELOP);
        fixture.allModels.add(parentLatest);
        when(fixture.modelStore.upsert(any())).thenReturn(branchCreated);

        final TcModel result = fixture.port.createBranchModel(new ModelBranchCommandPort.CreateBranchModelCommand(
                parentLatest.modelKey(),
                suffix,
                null,
                currentUser
        ));

        assertEquals(branchCreated, result);
        verify(fixture.modelStore).upsert(any());
    }

    @Test
    @DisplayName("parent commit은 확장된 100자 modelVersion 한도까지 허용합니다")
    void commitParentAllowsExtendedModelVersionLength() {
        final Fixture fixture = new Fixture();
        final TcModel parentLatest = model(9501L, 1801L, "ROOT", null, "1.0.0", ProtocolType.SECS, ModelStatus.OPERATE);
        final TcModel branchLatest = model(9502L, 1802L, "ROOT_feature_tester", "ROOT", "EDIT", ProtocolType.SECS, ModelStatus.DEVELOP);
        final String newParentVersion = "V".repeat(100);
        final TcModel committedParent = model(9503L, parentLatest.modelKey(), "ROOT", null, newParentVersion, ProtocolType.SECS, ModelStatus.OPERATE);

        fixture.allModels.add(parentLatest);
        fixture.allModels.add(branchLatest);
        when(fixture.modelStore.upsert(any())).thenAnswer(invocation -> {
            final UpsertTcModel command = invocation.getArgument(0);
            if (command.modelKey() == null && newParentVersion.equals(command.modelVersion())) {
                return committedParent;
            }
            return branchLatest;
        });

        final ModelParentCommitPort.CommitParentResult result = fixture.port.previewOrCommit(
                new ModelParentCommitPort.CommitParentCommand(branchLatest.modelKey(), true, newParentVersion, "tester")
        );

        assertTrue(result.committed());
        assertEquals(committedParent.modelVersionKey(), result.committedParentModelVersionKey());
    }

    @Test
    @DisplayName("model 삭제는 cascade 대상 branch 버전을 EQP가 참조 중이면 409로 차단합니다")
    void deleteModelRejectsWhenEqpReferencesCascadeTarget() {
        final Fixture fixture = new Fixture();
        final TcModel rootLatest = model(9301L, 1501L, "ROOT", null, "1.0.0", ProtocolType.SECS, ModelStatus.OPERATE);
        final TcModel branchLatest = model(9302L, 1601L, "ROOT_feature_tester", "ROOT", "EDIT", ProtocolType.SECS, ModelStatus.DEVELOP);
        fixture.allModels.add(rootLatest);
        fixture.allModels.add(branchLatest);
        fixture.allEqps.add(eqp(1L, "EQP-REF-001", branchLatest.modelVersionKey()));

        assertThrows(
                com.nori.tc.ui.core.exception.UiConflictException.class,
                () -> fixture.port.deleteModel(rootLatest.modelKey())
        );

        verify(fixture.modelStore, never()).deleteByModelKey(anyLong());
    }

    private static TcModel model(
            final long modelVersionKey,
            final long modelKey,
            final String modelName,
            final String parentModel,
            final String modelVersion,
            final ProtocolType protocolType,
            final ModelStatus modelStatus
    ) {
        return model(modelVersionKey, modelKey, modelName, parentModel, modelVersion, protocolType, modelStatus, "desc", "NORI");
    }

    private static TcModel model(
            final long modelVersionKey,
            final long modelKey,
            final String modelName,
            final String parentModel,
            final String modelVersion,
            final ProtocolType protocolType,
            final ModelStatus modelStatus,
            final String description,
            final String maker
    ) {
        final OffsetDateTime now = OffsetDateTime.parse("2026-03-11T10:15:30+09:00");
        return new TcModel(
                modelVersionKey,
                modelKey,
                modelName,
                parentModel,
                modelVersion,
                protocolType,
                modelStatus,
                description,
                maker,
                now,
                now,
                "SYSTEM",
                "SYSTEM"
        );
    }

    private static TcModelParam param(final long modelVersionKey, final String name, final String value) {
        return new TcModelParam(1L, modelVersionKey, name, value, "desc", OffsetDateTime.parse("2026-03-11T10:15:30+09:00"));
    }

    private static TcModelSecsMessage secs(final long modelVersionKey, final String name) {
        return new TcModelSecsMessage(1L, modelVersionKey, name, "desc", "1", OffsetDateTime.parse("2026-03-11T10:15:30+09:00"));
    }

    private static TcModelSocketMessage socket(final long modelVersionKey, final String name) {
        return new TcModelSocketMessage(1L, modelVersionKey, name, "desc", "1", OffsetDateTime.parse("2026-03-11T10:15:30+09:00"));
    }

    private static TcModelVariableId variable(final long modelVersionKey, final String variableId) {
        return new TcModelVariableId(1L, modelVersionKey, variableId, VariableIdType.SVID, "desc", OffsetDateTime.parse("2026-03-11T10:15:30+09:00"));
    }

    private static TcModelReportId report(final long modelVersionKey, final String reportId) {
        return new TcModelReportId(1L, modelVersionKey, reportId, "VID_TEMP", true, "desc", OffsetDateTime.parse("2026-03-11T10:15:30+09:00"));
    }

    private static TcModelEventId event(final long modelVersionKey, final String eventId) {
        return new TcModelEventId(1L, modelVersionKey, eventId, "RPT_01", true, "desc", OffsetDateTime.parse("2026-03-11T10:15:30+09:00"));
    }

    private static TcModelWorkflow workflow(final long modelVersionKey, final String workflowName, final String messageName) {
        return new TcModelWorkflow(1L, modelVersionKey, workflowName, messageName, "EVT_01", "TX_01", "filter", "action", "idx", OffsetDateTime.parse("2026-03-11T10:15:30+09:00"));
    }

    private static TcModelMdf mdf(final long modelVersionKey, final String name) {
        return new TcModelMdf(1L, modelVersionKey, name, "<xml/>".getBytes(), OffsetDateTime.parse("2026-03-11T10:15:30+09:00"));
    }

    private static TcModelDcopItem dcop(final long modelVersionKey, final String name) {
        return new TcModelDcopItem(1L, modelVersionKey, name, "FLOW_01", "EVT_01", "VID_TEMP", null, null, 1, OffsetDateTime.parse("2026-03-11T10:15:30+09:00"));
    }

    private static TcEqp eqp(final long eqpKey, final String eqpId, final long modelVersionKey) {
        final OffsetDateTime now = OffsetDateTime.parse("2026-03-11T10:15:30+09:00");
        return new TcEqp(
                eqpKey,
                eqpId,
                ProtocolType.SECS,
                "ACTIVE",
                false,
                1,
                "127.0.0.1",
                5000,
                modelVersionKey,
                null,
                true,
                now,
                now,
                "SYSTEM",
                "SYSTEM"
        );
    }

    private static String dailyVersion(final LocalDate date, final int sequence) {
        return String.format("%ty.%tm.%td.%04d", date, date, date, sequence);
    }

    /**
     * 다중 Store mock과 페이지 기반 조회 동작을 함께 관리하는 테스트 픽스처입니다.
     */
    private static final class Fixture {

        private final TcModelStore modelStore = mock(TcModelStore.class);
        private final TcEqpStore eqpStore = mock(TcEqpStore.class);
        private final TcModelParamStore modelParamStore = mock(TcModelParamStore.class);
        private final TcModelSecsMessageStore modelSecsMessageStore = mock(TcModelSecsMessageStore.class);
        private final TcModelSocketMessageStore modelSocketMessageStore = mock(TcModelSocketMessageStore.class);
        private final TcModelVariableIdStore modelVariableIdStore = mock(TcModelVariableIdStore.class);
        private final TcModelReportIdStore modelReportIdStore = mock(TcModelReportIdStore.class);
        private final TcModelEventIdStore modelEventIdStore = mock(TcModelEventIdStore.class);
        private final TcModelWorkflowStore modelWorkflowStore = mock(TcModelWorkflowStore.class);
        private final TcModelMdfStore modelMdfStore = mock(TcModelMdfStore.class);
        private final TcModelDcopItemStore modelDcopItemStore = mock(TcModelDcopItemStore.class);

        private final List<TcModel> allModels = new ArrayList<>();
        private final List<TcEqp> allEqps = new ArrayList<>();
        private final Map<Long, List<TcModelParam>> paramsByVersion = new HashMap<>();
        private final Map<Long, List<TcModelSecsMessage>> secsMessagesByVersion = new HashMap<>();
        private final Map<Long, List<TcModelSocketMessage>> socketMessagesByVersion = new HashMap<>();
        private final Map<Long, List<TcModelVariableId>> variableIdsByVersion = new HashMap<>();
        private final Map<Long, List<TcModelReportId>> reportIdsByVersion = new HashMap<>();
        private final Map<Long, List<TcModelEventId>> eventIdsByVersion = new HashMap<>();
        private final Map<Long, List<TcModelWorkflow>> workflowsByVersion = new HashMap<>();
        private final Map<Long, List<TcModelMdf>> mdfsByVersion = new HashMap<>();
        private final Map<Long, List<TcModelDcopItem>> dcopItemsByVersion = new HashMap<>();

        private final JpaModelManagementPort port = new JpaModelManagementPort(
                modelStore,
                eqpStore,
                modelParamStore,
                modelSecsMessageStore,
                modelSocketMessageStore,
                modelVariableIdStore,
                modelReportIdStore,
                modelEventIdStore,
                modelWorkflowStore,
                modelMdfStore,
                modelDcopItemStore
        ) {
            @Override
            LocalDate resolveCurrentVersionDate() {
                return FIXED_VERSION_DATE;
            }
        };

        private Fixture() {
            when(modelStore.findAll(any())).thenAnswer(invocation -> paginate(allModels, invocation.getArgument(0)));
            when(eqpStore.findAll(any())).thenAnswer(invocation -> paginate(allEqps, invocation.getArgument(0)));
            when(modelStore.findByNameVersion(anyString(), anyString())).thenReturn(Optional.empty());

            when(modelParamStore.findAllByModelVersionKey(anyLong(), any())).thenAnswer(invocation ->
                    paginate(paramsByVersion.getOrDefault(invocation.getArgument(0), List.of()), invocation.getArgument(1))
            );
            when(modelSecsMessageStore.findAllByModelVersionKey(anyLong(), any())).thenAnswer(invocation ->
                    paginate(secsMessagesByVersion.getOrDefault(invocation.getArgument(0), List.of()), invocation.getArgument(1))
            );
            when(modelSocketMessageStore.findAllByModelVersionKey(anyLong(), any())).thenAnswer(invocation ->
                    paginate(socketMessagesByVersion.getOrDefault(invocation.getArgument(0), List.of()), invocation.getArgument(1))
            );
            when(modelVariableIdStore.findAllByModelVersionKey(anyLong(), any())).thenAnswer(invocation ->
                    paginate(variableIdsByVersion.getOrDefault(invocation.getArgument(0), List.of()), invocation.getArgument(1))
            );
            when(modelReportIdStore.findAllByModelVersionKey(anyLong(), any())).thenAnswer(invocation ->
                    paginate(reportIdsByVersion.getOrDefault(invocation.getArgument(0), List.of()), invocation.getArgument(1))
            );
            when(modelEventIdStore.findAllByModelVersionKey(anyLong(), any())).thenAnswer(invocation ->
                    paginate(eventIdsByVersion.getOrDefault(invocation.getArgument(0), List.of()), invocation.getArgument(1))
            );
            when(modelWorkflowStore.findAllByModelVersionKey(anyLong(), any())).thenAnswer(invocation ->
                    paginate(workflowsByVersion.getOrDefault(invocation.getArgument(0), List.of()), invocation.getArgument(1))
            );
            when(modelMdfStore.findAllByModelVersionKey(anyLong(), any())).thenAnswer(invocation ->
                    paginate(mdfsByVersion.getOrDefault(invocation.getArgument(0), List.of()), invocation.getArgument(1))
            );
            when(modelDcopItemStore.findAllByModelVersionKey(anyLong(), any())).thenAnswer(invocation ->
                    paginate(dcopItemsByVersion.getOrDefault(invocation.getArgument(0), List.of()), invocation.getArgument(1))
            );
        }

        private static <T> List<T> paginate(final List<T> source, final PageRequest pageRequest) {
            if (source.isEmpty()) {
                return List.of();
            }

            final int fromIndex = Math.min(pageRequest.offset(), source.size());
            final int toIndex = Math.min(fromIndex + pageRequest.limit(), source.size());
            return source.subList(fromIndex, toIndex);
        }
    }
}

package com.nori.tc.ui.adapter.db;

import tools.jackson.databind.ObjectMapper;
import com.nori.tc.ui.core.deploy.DeployActiveEqpConflict;
import com.nori.tc.ui.core.deploy.DeployRegisterTargetView;
import com.nori.tc.ui.core.model.PagedResponse;
import com.nori.tc.ui.core.model.UiPageRequest;
import com.nori.tc.ui.core.port.db.DeployRegisterQueryPort;
import com.nori.tc.ui.domain.eqp.UiEqp;
import com.nori.tc.ui.domain.jar.UiBusinessJar;
import com.nori.tc.ui.domain.model.UiModel;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Deploy Register 대상 설비 조회 포트의 DB 구현체입니다.
 */
@Repository
public class JpaDeployRegisterQueryPort implements DeployRegisterQueryPort {

    private final EqpManagementDbSupport dbSupport;
    private final DeployJdbcStoreSupport deployStoreSupport;

    public JpaDeployRegisterQueryPort(
            final EqpManagementDbSupport dbSupport,
            final NamedParameterJdbcTemplate jdbcTemplate,
            final ObjectMapper objectMapper
    ) {
        this.dbSupport = Objects.requireNonNull(dbSupport, "dbSupport is null");
        this.deployStoreSupport = new DeployJdbcStoreSupport(jdbcTemplate, objectMapper);
    }

    @Override
    public PagedResponse<DeployRegisterTargetView> findRegisterTargets(
            final String modelName,
            final Long modelVersionKey,
            final String eqpId,
            final Long lineKey,
            final UiPageRequest pageRequest
    ) {
        final UiPageRequest effectivePageRequest = pageRequest == null ? UiPageRequest.defaultPage() : pageRequest;

        final Map<Long, UiModel> modelByVersionKey = dbSupport.loadAllModels().stream()
                .collect(Collectors.toMap(
                        UiModel::modelVersionKey,
                        Function.identity(),
                        (left, right) -> left
                ));
        final Map<Long, UiBusinessJar> businessJarByKey = dbSupport.loadAllBusinessJars().stream()
                .collect(Collectors.toMap(
                        UiBusinessJar::businessJarKey,
                        Function.identity(),
                        (left, right) -> left
                ));
        final List<UiEqp> allEqps = dbSupport.loadAllEqps().stream()
                .filter(eqp -> lineKey == null || eqp.lineKey() == lineKey.longValue())
                .toList();
        final Map<String, DeployActiveEqpConflict> activeConflictByEqpId = deployStoreSupport.findActiveEqpConflicts(
                        allEqps.stream()
                                .map(UiEqp::eqpId)
                                .toList(),
                        null
                ).stream()
                .collect(Collectors.toMap(
                        DeployActiveEqpConflict::eqpId,
                        Function.identity(),
                        (left, right) -> left
                ));

        final List<DeployRegisterTargetView> filteredItems = allEqps.stream()
                .map(eqp -> toView(
                        eqp,
                        modelByVersionKey.get(eqp.modelVersionKey()),
                        businessJarByKey.get(eqp.businessJarKey()),
                        activeConflictByEqpId.get(eqp.eqpId())
                ))
                .filter(item -> matchesModelName(item, modelName))
                .filter(item -> matchesModelVersionKey(item, modelVersionKey))
                .filter(item -> matchesEqpId(item, eqpId))
                .sorted(Comparator
                        .comparing(
                                (DeployRegisterTargetView item) -> item.eqpId() == null ? "" : item.eqpId(),
                                String.CASE_INSENSITIVE_ORDER
                        )
                        .thenComparing(item -> item.eqpKey() == null ? Long.MAX_VALUE : item.eqpKey()))
                .toList();

        final int safeOffset = Math.min(effectivePageRequest.offset(), filteredItems.size());
        final int safeEndIndex = Math.min(safeOffset + effectivePageRequest.limit(), filteredItems.size());

        return PagedResponse.of(
                filteredItems.subList(safeOffset, safeEndIndex),
                effectivePageRequest.offset(),
                effectivePageRequest.limit(),
                filteredItems.size()
        );
    }

    private DeployRegisterTargetView toView(
            final UiEqp eqp,
            final UiModel model,
            final UiBusinessJar businessJar,
            final DeployActiveEqpConflict activeConflict
    ) {
        return new DeployRegisterTargetView(
                eqp.eqpKey(),
                eqp.eqpId(),
                eqp.commInterface(),
                eqp.enabled(),
                eqp.modelVersionKey(),
                model == null ? null : model.modelName(),
                model == null ? null : model.modelVersion(),
                model == null ? null : model.status(),
                eqp.businessJarKey(),
                businessJar == null ? null : businessJar.jarFileName(),
                eqp.appliedParamVersion(),
                activeConflict != null,
                activeConflict == null ? null : activeConflict.deployKey(),
                activeConflict == null ? null : activeConflict.deployStatus(),
                activeConflict == null ? null : activeConflict.deployName(),
                activeConflict == null ? null : activeConflict.requestedBy()
        );
    }

    private boolean matchesModelName(
            final DeployRegisterTargetView item,
            final String modelName
    ) {
        if (modelName == null) {
            return true;
        }
        return item.modelName() != null && item.modelName().equalsIgnoreCase(modelName);
    }

    private boolean matchesModelVersionKey(
            final DeployRegisterTargetView item,
            final Long modelVersionKey
    ) {
        if (modelVersionKey == null) {
            return true;
        }
        return Objects.equals(item.modelVersionKey(), modelVersionKey);
    }

    private boolean matchesEqpId(
            final DeployRegisterTargetView item,
            final String eqpId
    ) {
        if (eqpId == null) {
            return true;
        }
        return item.eqpId() != null && item.eqpId().toLowerCase().contains(eqpId.toLowerCase());
    }
}

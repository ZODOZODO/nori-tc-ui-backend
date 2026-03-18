package com.nori.tc.ui.adapter.db;

import com.nori.tc.db.domain.common.model.ModelStatus;
import com.nori.tc.ui.core.eqp.EqpManagementOptions;
import com.nori.tc.ui.core.port.db.EqpOptionsQueryPort;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * EQP 관리 화면 옵션 조회 포트의 DB 구현체입니다.
 */
@Repository
public class JpaEqpOptionsQueryPort implements EqpOptionsQueryPort {

    private final EqpManagementDbSupport dbSupport;

    public JpaEqpOptionsQueryPort(final EqpManagementDbSupport dbSupport) {
        this.dbSupport = Objects.requireNonNull(dbSupport, "dbSupport is null");
    }

    @Override
    public EqpManagementOptions loadOptions() {
        final List<String> socketProtocolTypes = dbSupport.loadAllSocketProtocolTypes().stream()
                .map(item -> item.socketProtocolType() == null ? null : item.socketProtocolType().trim())
                .filter(Objects::nonNull)
                .filter(value -> !value.isBlank())
                .sorted(String::compareToIgnoreCase)
                .toList();

        final List<String> gatewayJarFileNames = new LinkedHashSet<>(dbSupport.loadAllEqps().stream()
                .map(eqp -> eqp.eqpKey() == null ? null : dbSupport.findLatestGatewayJarByFileName(
                        dbSupport.loadSnapshotByEqpId(eqp.eqpId())
                                .map(snapshot -> snapshot.gatewayJar() == null ? null : snapshot.gatewayJar().jarFileName())
                                .orElse(null)
                ).map(jar -> jar.jarFileName()).orElse(null))
                .filter(Objects::nonNull)
                .toList()).stream().sorted(String::compareToIgnoreCase).toList();

        final List<String> businessJarFileNames = new LinkedHashSet<>(dbSupport.loadAllEqps().stream()
                .map(eqp -> eqp.eqpKey() == null ? null : dbSupport.loadSnapshotByEqpId(eqp.eqpId())
                        .map(snapshot -> snapshot.businessJar() == null ? null : snapshot.businessJar().jarFileName())
                        .orElse(null))
                .filter(Objects::nonNull)
                .toList()).stream().sorted(String::compareToIgnoreCase).toList();

        final List<EqpManagementOptions.ModelOption> allModelOptions = dbSupport.loadAllModels().stream()
                .map(model -> new EqpManagementOptions.ModelOption(
                        model.modelVersionKey(),
                        model.modelKey(),
                        model.modelName(),
                        model.parentModel(),
                        model.modelVersion(),
                        model.commInterface(),
                        model.status()
                ))
                .sorted(Comparator
                        .comparing(EqpManagementOptions.ModelOption::commInterface)
                        .thenComparing(EqpManagementOptions.ModelOption::modelName, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(option -> option.parentModel() == null ? "" : option.parentModel(), String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(EqpManagementOptions.ModelOption::modelVersion, String.CASE_INSENSITIVE_ORDER))
                .toList();

        final List<EqpManagementOptions.ModelOption> developModelOptions = allModelOptions.stream()
                .filter(option -> option.status() == ModelStatus.DEVELOP)
                .toList();
        final List<EqpManagementOptions.ModelOption> operateModelOptions = allModelOptions.stream()
                .filter(option -> option.status() == ModelStatus.OPERATE)
                .toList();

        return new EqpManagementOptions(
                socketProtocolTypes,
                gatewayJarFileNames,
                businessJarFileNames,
                developModelOptions,
                operateModelOptions
        );
    }
}

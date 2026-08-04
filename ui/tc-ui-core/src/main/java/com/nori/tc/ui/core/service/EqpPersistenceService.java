package com.nori.tc.ui.core.service;

import com.nori.tc.ui.core.eqp.EqpManagementCommand;
import com.nori.tc.ui.core.eqp.EqpManagementSnapshot;
import com.nori.tc.ui.core.port.db.EqpCrudPort;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Optional;

/**
 * EQP DB 반영 책임을 분리합니다.
 */
@Service
public class EqpPersistenceService {

    private final EqpCrudPort eqpCrudPort;

    public EqpPersistenceService(final EqpCrudPort eqpCrudPort) {
        this.eqpCrudPort = Objects.requireNonNull(eqpCrudPort, "eqpCrudPort is null");
    }

    public Optional<EqpManagementSnapshot> findSnapshotByEqpId(final String eqpId) {
        return eqpCrudPort.findSnapshotByEqpId(eqpId);
    }

    public EqpManagementSnapshot create(final EqpManagementCommand.Create command) {
        return eqpCrudPort.create(command);
    }

    public EqpManagementSnapshot update(
            final String eqpId,
            final EqpManagementCommand.Update command
    ) {
        return eqpCrudPort.update(eqpId, command);
    }

    /**
     * deploy 성공 후 확정된 버전 3개만 반영합니다 — 나머지 컬럼은 저장 시점의 현재 행을 유지합니다.
     */
    public EqpManagementSnapshot finalizeDeployedVersions(
            final String eqpId,
            final long modelVersionKey,
            final String appliedParamVersion,
            final Long businessJarKey,
            final String actor
    ) {
        return eqpCrudPort.finalizeDeployedVersions(
                eqpId,
                modelVersionKey,
                appliedParamVersion,
                businessJarKey,
                actor
        );
    }

    public void delete(final String eqpId) {
        eqpCrudPort.delete(eqpId);
    }

    public void restore(final EqpManagementSnapshot snapshot) {
        eqpCrudPort.restore(snapshot);
    }
}

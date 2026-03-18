package com.nori.tc.ui.adapter.db;

import com.nori.tc.ui.core.eqp.EqpManagementSnapshot;
import com.nori.tc.ui.core.exception.UiBadRequestException;
import com.nori.tc.ui.core.port.db.EqpManageQueryPort;
import org.springframework.stereotype.Repository;

import java.util.Objects;
import java.util.Optional;

/**
 * EQP 관리 상세 조회 포트의 DB 구현체입니다.
 */
@Repository
public class JpaEqpManageQueryPort implements EqpManageQueryPort {

    private final EqpManagementDbSupport dbSupport;

    public JpaEqpManageQueryPort(final EqpManagementDbSupport dbSupport) {
        this.dbSupport = Objects.requireNonNull(dbSupport, "dbSupport is null");
    }

    @Override
    public Optional<EqpManagementSnapshot> findManageSnapshotByEqpId(final String eqpId) {
        if (eqpId == null || eqpId.isBlank()) {
            throw new UiBadRequestException("eqpId는 비어 있을 수 없습니다.");
        }
        return dbSupport.loadSnapshotByEqpId(eqpId);
    }
}

package com.nori.tc.ui.core.coordination;

import com.nori.tc.ui.core.coordination.model.UiOperationManifestView;
import com.nori.tc.ui.core.coordination.model.UiOperationRequest;
import com.nori.tc.ui.core.coordination.port.UiOperationDbFinalizationPort;

import java.util.Objects;

/**
 * DB 확정을 명령 종류에 따라 분배합니다(§13.7).
 *
 * <p>설비 row 를 만들고 지우는 확정과 배포 결과를 확정하는 일은 다른 집합체를 건드립니다. 한 구현에
 * 둘을 넣으면 설비 영속화 서비스에 deploy store 의존이 딸려 들어오고, 클래스 이름이 하는 일과
 * 어긋납니다.</p>
 */
public final class UiOperationDbFinalizationRouter implements UiOperationDbFinalizationPort {

    private final UiOperationDbFinalizationPort eqpFinalizer;
    private final UiOperationDbFinalizationPort deployFinalizer;

    /**
     * 라우터를 만듭니다.
     *
     * @param eqpFinalizer 설비 CRUD 확정기
     * @param deployFinalizer deploy target 확정기
     */
    public UiOperationDbFinalizationRouter(
            final UiOperationDbFinalizationPort eqpFinalizer,
            final UiOperationDbFinalizationPort deployFinalizer
    ) {
        this.eqpFinalizer = Objects.requireNonNull(eqpFinalizer, "eqpFinalizer is null");
        this.deployFinalizer = Objects.requireNonNull(deployFinalizer, "deployFinalizer is null");
    }

    @Override
    public Outcome finalizeToTarget(
            final String operationId,
            final UiOperationManifestView manifest,
            final long desiredRevision
    ) {
        if (UiOperationRequest.DB_COMMAND_DEPLOY_TARGET.equals(manifest.dbCommandKind())) {
            return deployFinalizer.finalizeToTarget(operationId, manifest, desiredRevision);
        }
        return eqpFinalizer.finalizeToTarget(operationId, manifest, desiredRevision);
    }
}

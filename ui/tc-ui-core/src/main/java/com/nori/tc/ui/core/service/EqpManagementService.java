package com.nori.tc.ui.core.service;

import com.nori.tc.ui.core.eqp.EqpCommandResult;
import com.nori.tc.ui.core.eqp.EqpManagementCommand;
import com.nori.tc.ui.core.eqp.EqpManagementOptions;
import com.nori.tc.ui.core.eqp.EqpManagementSnapshot;
import com.nori.tc.ui.core.port.db.EqpManageQueryPort;
import com.nori.tc.ui.core.port.db.EqpOptionsQueryPort;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * EQP 관리 생성/수정/삭제와 옵션/상세 조회를 총괄하는 서비스입니다.
 *
 * <p>웹 계층은 본 서비스를 호출한 뒤 결과를 HTTP 응답으로만 변환합니다.</p>
 */
@Service
public class EqpManagementService {

    public static final String EQP_MANAGEMENT_EXECUTOR_BEAN_NAME = "uiEqpManagementExecutor";
    private final EqpManageQueryPort eqpManageQueryPort;
    private final EqpOptionsQueryPort eqpOptionsQueryPort;
    private final EqpCommandOrchestrator eqpCommandOrchestrator;
    private final Executor eqpManagementExecutor;

    /**
     * 필수 의존성을 초기화합니다.
     */
    public EqpManagementService(
            final EqpManageQueryPort eqpManageQueryPort,
            final EqpOptionsQueryPort eqpOptionsQueryPort,
            final EqpCommandOrchestrator eqpCommandOrchestrator,
            @Qualifier(EQP_MANAGEMENT_EXECUTOR_BEAN_NAME) final Executor eqpManagementExecutor
    ) {
        this.eqpManageQueryPort = Objects.requireNonNull(eqpManageQueryPort, "eqpManageQueryPort is null");
        this.eqpOptionsQueryPort = Objects.requireNonNull(eqpOptionsQueryPort, "eqpOptionsQueryPort is null");
        this.eqpCommandOrchestrator = Objects.requireNonNull(eqpCommandOrchestrator, "eqpCommandOrchestrator is null");
        this.eqpManagementExecutor = Objects.requireNonNull(eqpManagementExecutor, "eqpManagementExecutor is null");
    }

    /**
     * EQP 관리 상세를 조회합니다.
     *
     * @param eqpId 조회 대상 eqp id
     * @return 관리 스냅샷
     */
    public Optional<EqpManagementSnapshot> getManageDetail(final String eqpId) {
        if (eqpId == null || eqpId.isBlank()) {
            throw new com.nori.tc.ui.core.exception.UiBadRequestException("eqpId는 비어 있을 수 없습니다.");
        }
        return eqpManageQueryPort.findManageSnapshotByEqpId(eqpId);
    }

    /**
     * EQP 관리 옵션을 조회합니다.
     *
     * @return 관리 옵션
     */
    public EqpManagementOptions getOptions() {
        return eqpOptionsQueryPort.loadOptions();
    }

    /**
     * EQP를 생성하고 runtime sync까지 완료합니다.
     *
     * @param command 생성 명령
     * @param timeoutMs dual/lifecycle timeout
     * @return 최종 처리 결과
     */
    public CompletableFuture<EqpCommandResult> create(
            final EqpManagementCommand.Create command,
            final long timeoutMs
    ) {
        return CompletableFuture.supplyAsync(() -> eqpCommandOrchestrator.create(command, timeoutMs), eqpManagementExecutor);
    }

    /**
     * EQP를 수정하고 runtime sync까지 완료합니다.
     *
     * @param eqpId 수정 대상 eqp id
     * @param command 수정 명령
     * @param timeoutMs dual/lifecycle timeout
     * @return 최종 처리 결과
     */
    public CompletableFuture<EqpCommandResult> update(
            final String eqpId,
            final EqpManagementCommand.Update command,
            final long timeoutMs
    ) {
        return CompletableFuture.supplyAsync(() -> eqpCommandOrchestrator.update(eqpId, command, timeoutMs), eqpManagementExecutor);
    }

    /**
     * EQP를 종료 후 삭제하고 runtime sync까지 완료합니다.
     *
     * @param eqpId 삭제 대상 eqp id
     * @param timeoutMs dual/lifecycle timeout
     * @return 최종 처리 결과
     */
    public CompletableFuture<EqpCommandResult> delete(
            final String eqpId,
            final long timeoutMs
    ) {
        return CompletableFuture.supplyAsync(() -> eqpCommandOrchestrator.delete(eqpId, timeoutMs), eqpManagementExecutor);
    }
}

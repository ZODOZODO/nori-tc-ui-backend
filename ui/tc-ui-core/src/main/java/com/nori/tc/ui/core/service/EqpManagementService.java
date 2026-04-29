package com.nori.tc.ui.core.service;

import tools.jackson.databind.JsonNode;
import com.nori.tc.common.runtime.support.TraceIdGenerator;
import com.nori.tc.ui.core.eqp.EqpAsyncAcceptResult;
import com.nori.tc.ui.core.eqp.EqpCommandResult;
import com.nori.tc.ui.core.eqp.EqpManagementCommand;
import com.nori.tc.ui.core.eqp.EqpManagementOptions;
import com.nori.tc.ui.core.eqp.EqpManagementSnapshot;
import com.nori.tc.ui.core.model.UiCommandEventType;
import com.nori.tc.ui.core.model.UiCommandReply;
import com.nori.tc.ui.core.port.db.EqpManageQueryPort;
import com.nori.tc.ui.core.port.db.EqpOptionsQueryPort;
import com.nori.tc.ui.core.port.redis.AsyncResultStorePort;
import com.nori.tc.ui.domain.task.UiTaskStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    private static final Logger log = LoggerFactory.getLogger(EqpManagementService.class);
    public static final String EQP_MANAGEMENT_EXECUTOR_BEAN_NAME = "uiEqpManagementExecutor";
    private static final String DIRECT_ASYNC_SOURCE = "TC-UI-APP";
    private final EqpManageQueryPort eqpManageQueryPort;
    private final EqpOptionsQueryPort eqpOptionsQueryPort;
    private final EqpDirectScenarioService eqpDirectScenarioService;
    private final Executor eqpManagementExecutor;
    private final AsyncResultStorePort asyncResultStorePort;

    /**
     * 필수 의존성을 초기화합니다.
     */
    public EqpManagementService(
            final EqpManageQueryPort eqpManageQueryPort,
            final EqpOptionsQueryPort eqpOptionsQueryPort,
            final EqpDirectScenarioService eqpDirectScenarioService,
            final AsyncResultStorePort asyncResultStorePort,
            @Qualifier(EQP_MANAGEMENT_EXECUTOR_BEAN_NAME) final Executor eqpManagementExecutor
    ) {
        this.eqpManageQueryPort = Objects.requireNonNull(eqpManageQueryPort, "eqpManageQueryPort is null");
        this.eqpOptionsQueryPort = Objects.requireNonNull(eqpOptionsQueryPort, "eqpOptionsQueryPort is null");
        this.eqpDirectScenarioService = Objects.requireNonNull(eqpDirectScenarioService, "eqpDirectScenarioService is null");
        this.asyncResultStorePort = Objects.requireNonNull(asyncResultStorePort, "asyncResultStorePort is null");
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
     * 여러 설비의 관리 상세를 한 번에 조회합니다.
     *
     * <p>그룹 화면처럼 다건을 동시에 보여줘야 하는 화면에서 N-fan-out HTTP 호출 대신 사용합니다.
     * 입력 순서를 보존한 LinkedHashMap을 반환하며, 존재하지 않는 eqpId는 결과 맵에서 빠집니다.</p>
     *
     * @param eqpIds 대상 설비 ID 목록 (null 또는 빈 값은 무시)
     * @return 입력 순서 그대로 보존된 eqpId → snapshot 맵
     */
    public Map<String, EqpManagementSnapshot> getManageDetailsByEqpIds(final List<String> eqpIds) {
        if (eqpIds == null || eqpIds.isEmpty()) {
            return Map.of();
        }
        final LinkedHashMap<String, EqpManagementSnapshot> result = new LinkedHashMap<>();
        for (final String rawEqpId : eqpIds) {
            if (rawEqpId == null) {
                continue;
            }
            final String eqpId = rawEqpId.trim();
            if (eqpId.isEmpty() || result.containsKey(eqpId)) {
                continue;
            }
            // 한 EQP의 스냅샷 로딩 실패가 batch 전체를 죽이지 않도록 격리합니다.
            // 실패한 항목은 missingEqpIds로 노출해 사용자에게 명시적으로 표시합니다.
            try {
                eqpManageQueryPort.findManageSnapshotByEqpId(eqpId)
                        .ifPresent(snapshot -> result.put(eqpId, snapshot));
            } catch (RuntimeException exception) {
                log.warn(
                        "EQP manage snapshot 조회 실패. 해당 항목은 응답에서 제외합니다. eqpId={}",
                        eqpId,
                        exception
                );
            }
        }
        return result;
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
        return CompletableFuture.supplyAsync(() -> eqpDirectScenarioService.executeCreate(command), eqpManagementExecutor);
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
        return CompletableFuture.supplyAsync(() -> eqpDirectScenarioService.executeUpdate(eqpId, command), eqpManagementExecutor);
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
        return CompletableFuture.supplyAsync(() -> eqpDirectScenarioService.executeDelete(eqpId), eqpManagementExecutor);
    }

    /**
     * EQP 시작 명령을 scenario 경로로 비동기 실행하고 polling traceId를 반환합니다.
     */
    public EqpAsyncAcceptResult startAsync(
            final String eqpId,
            final String interfaceType,
            final JsonNode uiMessage,
            final long timeoutMs
    ) {
        return submitAsyncScenario(
                eqpId,
                interfaceType,
                UiCommandEventType.EQP_START,
                timeoutMs,
                () -> eqpDirectScenarioService.executeStart(eqpId, interfaceType, uiMessage, "SYSTEM")
        );
    }

    /**
     * EQP 종료 명령을 scenario 경로로 비동기 실행하고 polling traceId를 반환합니다.
     */
    public EqpAsyncAcceptResult endAsync(
            final String eqpId,
            final String interfaceType,
            final JsonNode uiMessage,
            final long timeoutMs
    ) {
        return submitAsyncScenario(
                eqpId,
                interfaceType,
                UiCommandEventType.EQP_END,
                timeoutMs,
                () -> eqpDirectScenarioService.executeEnd(eqpId, interfaceType, uiMessage, "SYSTEM")
        );
    }

    private EqpAsyncAcceptResult submitAsyncScenario(
            final String eqpId,
            final String interfaceType,
            final UiCommandEventType eventType,
            final long timeoutMs,
            final java.util.function.Supplier<EqpCommandResult> action
    ) {
        final String traceId = TraceIdGenerator.newTraceId();
        asyncResultStorePort.registerPending(traceId, timeoutMs);

        try {
            CompletableFuture.runAsync(() -> {
                final EqpCommandResult result = action.get();
                if (!result.success() && "TIMEOUT".equalsIgnoreCase(result.errorCode())) {
                    asyncResultStorePort.markTimeout(traceId);
                    return;
                }
                asyncResultStorePort.markCompleted(traceId, toAsyncReply(traceId, eqpId, interfaceType, eventType, result));
            }, eqpManagementExecutor);
            return EqpAsyncAcceptResult.accepted(traceId);
        } catch (RuntimeException exception) {
            asyncResultStorePort.markTimeout(traceId);
            return EqpAsyncAcceptResult.error(
                    500,
                    "PUBLISH_FAILED",
                    exception.getMessage() == null ? "비동기 작업 등록 중 오류가 발생했습니다." : exception.getMessage()
            );
        }
    }

    private UiCommandReply toAsyncReply(
            final String traceId,
            final String eqpId,
            final String interfaceType,
            final UiCommandEventType eventType,
            final EqpCommandResult result
    ) {
        return new UiCommandReply(
                traceId,
                DIRECT_ASYNC_SOURCE,
                eventType.name() + "_REP",
                eqpId,
                interfaceType,
                result.success() ? UiTaskStatus.PASS : UiTaskStatus.FAIL,
                result.errorCode(),
                result.errorMessage()
        );
    }
}

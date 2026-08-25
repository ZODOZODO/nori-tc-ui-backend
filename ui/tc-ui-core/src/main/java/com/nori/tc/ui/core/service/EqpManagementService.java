package com.nori.tc.ui.core.service;

import tools.jackson.databind.JsonNode;
import com.nori.tc.common.runtime.support.TraceIdGenerator;
import com.nori.tc.ui.core.coordination.UiEqpMutationCoordinator;
import com.nori.tc.ui.core.coordination.model.UiOperationAcceptance;
import com.nori.tc.ui.core.eqp.EqpAsyncAcceptResult;
import com.nori.tc.ui.core.eqp.EqpCommandResult;
import com.nori.tc.ui.core.eqp.EqpManagementCommand;
import com.nori.tc.ui.core.eqp.EqpManagementOptions;
import com.nori.tc.ui.core.eqp.EqpManagementSnapshot;
import com.nori.tc.ui.core.eqp.StartOutcome;
import com.nori.tc.ui.core.model.AsyncResultEntry;
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
    private static final long ASYNC_POLL_INTERVAL_MS = 100L;
    private final EqpManageQueryPort eqpManageQueryPort;
    private final EqpOptionsQueryPort eqpOptionsQueryPort;
    private final EqpDirectScenarioService eqpDirectScenarioService;
    private final Executor eqpManagementExecutor;
    private final AsyncResultStorePort asyncResultStorePort;
    private final UiEqpMutationCoordinator mutationCoordinator;

    /**
     * 필수 의존성을 초기화합니다.
     */
    public EqpManagementService(
            final EqpManageQueryPort eqpManageQueryPort,
            final EqpOptionsQueryPort eqpOptionsQueryPort,
            final EqpDirectScenarioService eqpDirectScenarioService,
            final AsyncResultStorePort asyncResultStorePort,
            final UiEqpMutationCoordinator mutationCoordinator,
            @Qualifier(EQP_MANAGEMENT_EXECUTOR_BEAN_NAME) final Executor eqpManagementExecutor
    ) {
        this.mutationCoordinator = Objects.requireNonNull(mutationCoordinator, "mutationCoordinator is null");
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
     * <p>설비마다 단건 조회를 부르지 않고 포트의 배치 조회를 한 번만 씁니다. 예전에는 설비 수만큼
     * 단건 조회를 돌렸고, 그 하나하나가 다시 서브 store 를 10 여 회씩 호출해 설비 150 대 기준
     * 한 요청이 1,500 회 넘는 DB 왕복을 냈습니다. 그 부하가 같은 순간의 다른 요청(EQP_CREATE 등)까지
     * 자기 timeout 밖으로 밀어냈습니다(2026-08-10 실측 12.2s).</p>
     *
     * @param eqpIds 대상 설비 ID 목록 (null 또는 빈 값은 무시)
     * @return 입력 순서 그대로 보존된 eqpId → snapshot 맵
     */
    public Map<String, EqpManagementSnapshot> getManageDetailsByEqpIds(final List<String> eqpIds) {
        if (eqpIds == null || eqpIds.isEmpty()) {
            return Map.of();
        }

        final List<String> normalizedEqpIds = eqpIds.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(eqpId -> !eqpId.isEmpty())
                .distinct()
                .toList();
        if (normalizedEqpIds.isEmpty()) {
            return Map.of();
        }

        final Map<String, EqpManagementSnapshot> loaded =
                eqpManageQueryPort.findManageSnapshotsByEqpIds(normalizedEqpIds);

        // 요청 순서를 응답 순서로 그대로 보존합니다 — 배치 조회는 store 스캔 순서를 따르기 때문입니다.
        final LinkedHashMap<String, EqpManagementSnapshot> result = new LinkedHashMap<>();
        for (final String eqpId : normalizedEqpIds) {
            final EqpManagementSnapshot snapshot = loaded.get(eqpId);
            if (snapshot != null) {
                result.put(eqpId, snapshot);
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
     * EQP 생성을 조정 원장 operation 으로 접수합니다(§13.2).
     *
     * @param command 생성 명령
     * @param idempotencyKey 요청 idempotency key
     * @return 접수 결과
     */
    public UiOperationAcceptance acceptCreate(
            final EqpManagementCommand.Create command,
            final String idempotencyKey
    ) {
        return mutationCoordinator.create(command, idempotencyKey);
    }

    /**
     * EQP 수정을 조정 원장 operation 으로 접수합니다(§13.2).
     *
     * @param eqpId 수정 대상 eqp id
     * @param command 수정 명령
     * @param idempotencyKey 요청 idempotency key
     * @return 접수 결과
     */
    public UiOperationAcceptance acceptUpdate(
            final String eqpId,
            final EqpManagementCommand.Update command,
            final String idempotencyKey
    ) {
        return mutationCoordinator.update(eqpId, command, idempotencyKey);
    }

    /**
     * EQP 삭제를 조정 원장 operation 으로 접수합니다(§13.2).
     *
     * @param eqpId 삭제 대상 eqp id
     * @param idempotencyKey 요청 idempotency key
     * @return 접수 결과
     */
    public UiOperationAcceptance acceptDelete(final String eqpId, final String idempotencyKey) {
        return mutationCoordinator.delete(eqpId, idempotencyKey);
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

    /**
     * EQP 재연결 명령을 scenario 경로로 비동기 실행하고 polling traceId를 반환합니다.
     *
     * <p>stuck(desired_state=STARTED + control=OFFLINE) 인 ACTIVE 설비 복구용. EQP_START 는 desired!=ENDED 를
     * 거부(정상 lifecycle 시맨틱)하므로, 이미 STARTED 인 stuck 설비의 재연결은 EQP_RECONNECT 로 라우팅한다(R-B1/R-B2).</p>
     */
    public EqpAsyncAcceptResult reconnectAsync(
            final String eqpId,
            final String interfaceType,
            final JsonNode uiMessage,
            final long timeoutMs
    ) {
        return submitAsyncScenario(
                eqpId,
                interfaceType,
                UiCommandEventType.EQP_RECONNECT,
                timeoutMs,
                () -> eqpDirectScenarioService.executeReconnect(eqpId, interfaceType, uiMessage, "SYSTEM")
        );
    }

    /**
     * PLC 폴링 그룹 시작 명령을 scenario 경로로 비동기 실행하고 polling traceId를 반환합니다.
     * gateway accept(PASS) 후 enabled_poll_groups add 까지는 {@link EqpDirectScenarioService#executeGroupStart} 가 수행합니다.
     */
    public EqpAsyncAcceptResult startPollingGroupAsync(
            final String eqpId,
            final String groupMessageName,
            final long timeoutMs
    ) {
        return submitAsyncScenario(
                eqpId,
                requireInterfaceType(eqpId),
                UiCommandEventType.EQP_PLC_GROUP_START,
                timeoutMs,
                () -> eqpDirectScenarioService.executeGroupStart(eqpId, groupMessageName, "SYSTEM")
        );
    }

    /**
     * PLC 폴링 그룹 중단 명령을 scenario 경로로 비동기 실행하고 polling traceId를 반환합니다.
     */
    public EqpAsyncAcceptResult endPollingGroupAsync(
            final String eqpId,
            final String groupMessageName,
            final long timeoutMs
    ) {
        return submitAsyncScenario(
                eqpId,
                requireInterfaceType(eqpId),
                UiCommandEventType.EQP_PLC_GROUP_END,
                timeoutMs,
                () -> eqpDirectScenarioService.executeGroupEnd(eqpId, groupMessageName, "SYSTEM")
        );
    }

    /**
     * 폴링 그룹 명령의 interfaceType 을 설비 스냅샷에서 해결합니다.
     *
     * <p>start/end 엔드포인트는 요청 본문이 없어(경로 변수만) interfaceType 을 운반하지 않는다.
     * 해결하지 않고 null 을 넘기면 {@link UiCommandReply} 의 필수값 검증이 비동기 본문에서 터지고,
     * {@code whenComplete} 가 이를 TIMEOUT 으로 확정한다 — gateway 가 수락하고 {@code enabled_poll_groups}
     * 변이까지 끝난 명령이 UI 에는 타임아웃 실패로 보고된다. PASS/FAIL 양쪽이 같은 지점을 지나므로
     * 결과와 무관하게 항상 TIMEOUT 이 된다.</p>
     *
     * <p>값의 출처는 {@code EqpDirectScenarioService} 가 gateway plan 을 세울 때 쓰는 것과 같은
     * 스냅샷 필드다 — 두 경로가 같은 행을 읽어야 reply 와 실제 명령이 어긋나지 않는다.</p>
     *
     * @param eqpId 대상 설비 ID
     * @return 설비의 통신 인터페이스 이름
     */
    private String requireInterfaceType(final String eqpId) {
        return eqpManageQueryPort.findManageSnapshotByEqpId(eqpId)
                .map(snapshot -> snapshot.eqp().commInterface().name())
                .orElseThrow(() -> new com.nori.tc.ui.core.exception.UiNotFoundException(
                        "설비를 찾을 수 없습니다. eqpId=" + eqpId));
    }

    /**
     * 비동기 EQP 명령 traceId 가 종료 상태(PASS/FAIL/TIMEOUT)에 도달할 때까지 polling 으로 대기합니다.
     *
     * <p>front 의 polling API({@code GET /api/async/{traceId}})를 호출자 스레드에서 대신 소화하는 server-side 대기입니다.
     * 데모-run 연결 게이트처럼 "EQP_START 발행 후 gateway 연결(PASS)을 확인한 뒤에만 다음 단계로 진행"해야 하는 흐름이
     * 사용합니다. {@code deadline} 까지 {@link AsyncResultStorePort#getWithStatus} 를 짧은 간격으로 조회하며,
     * COMPLETED 면 reply 의 PASS/FAIL 을, TIMEOUT 이면 TIMEOUT 을 반환합니다. deadline 초과(저장소가 PENDING 에 머묾)도
     * TIMEOUT 입니다.</p>
     *
     * @param traceId 대기할 작업 추적 ID
     * @param timeoutMs 최대 대기 시간(ms). 이 시간 내 종료 상태 미도달 시 TIMEOUT
     * @return PASS/FAIL/TIMEOUT 결과
     */
    public StartOutcome awaitAsyncResult(final String traceId, final long timeoutMs) {
        if (traceId == null || traceId.isBlank()) {
            throw new com.nori.tc.ui.core.exception.UiBadRequestException("traceId는 비어 있을 수 없습니다.");
        }
        final long deadlineMs = System.currentTimeMillis() + Math.max(0L, timeoutMs);
        while (true) {
            final Optional<AsyncResultEntry> entry = asyncResultStorePort.getWithStatus(traceId);
            if (entry.isPresent()) {
                final AsyncResultEntry result = entry.get();
                switch (result.status()) {
                    case COMPLETED -> {
                        final UiCommandReply reply = result.reply();
                        return reply != null && reply.isSuccess()
                                ? StartOutcome.pass()
                                : StartOutcome.fail(
                                        reply == null ? null : reply.errorCode(),
                                        reply == null ? null : reply.errorMsg());
                    }
                    case TIMEOUT -> {
                        return StartOutcome.timeout();
                    }
                    case PENDING -> {
                        // 아직 gateway 응답 전 — 계속 polling
                    }
                    default -> throw new IllegalStateException("Unsupported async status: " + result.status());
                }
            }
            if (System.currentTimeMillis() >= deadlineMs) {
                return StartOutcome.timeout();
            }
            try {
                Thread.sleep(ASYNC_POLL_INTERVAL_MS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return StartOutcome.timeout();
            }
        }
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
            }, eqpManagementExecutor).whenComplete((ignored, throwable) -> {
                if (throwable == null) {
                    return;
                }
                // 람다 본문 예외를 받는 경로가 없어 로그 한 줄 없이 소실됐다. traceId 가 PENDING 에 남으면
                // 스케줄러가 뒤늦게 TIMEOUT 으로 승격시켜 성공한 명령이 "타임아웃" 으로 보고된다(S08 D-10).
                // 실패를 즉시 TIMEOUT 으로 확정하고 원인을 남겨 관측 가능하게 한다.
                log.error("EQP 비동기 시나리오 본문이 실패했습니다. traceId={}, eqpId={}, eventType={}",
                        traceId, eqpId, eventType, throwable);
                markTimeoutBestEffort(traceId);
            });
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

    /**
     * 실패 확정 기록입니다 — 이 기록마저 실패하면(같은 Redis 장애) 더 할 수 있는 일이 없으므로 로그만 남깁니다.
     */
    private void markTimeoutBestEffort(final String traceId) {
        try {
            asyncResultStorePort.markTimeout(traceId);
        } catch (RuntimeException markFailure) {
            log.error("EQP 비동기 실패의 TIMEOUT 확정까지 실패했습니다. traceId={}", traceId, markFailure);
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

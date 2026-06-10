package com.nori.tc.ui.adapter.db;

import com.nori.tc.db.core.eqp.store.TcEqpPlcStore;
import com.nori.tc.db.core.eqp.upsert.UpsertTcEqpPlc;
import com.nori.tc.db.domain.eqp.TcEqpPlc;
import com.nori.tc.db.domain.plc.PlcEnabledPollGroupsCodec;
import com.nori.tc.ui.core.exception.UiBadRequestException;
import com.nori.tc.ui.core.exception.UiConflictException;
import com.nori.tc.ui.core.port.db.EqpPlcPollGroupPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * {@link EqpPlcPollGroupPort} 의 JPA 구현 — {@code tc_eqp_plc.enabled_poll_groups} read-modify-write.
 *
 * <p><b>@Version optimistic lock 이 실제로 동작하는 메커니즘</b>: {@code TcEqpPlcStore.upsert} 는 내부에서
 * {@code findById} 후 {@code save} 한다. 따라서 read(findByEqpKey)와 write(upsert)를 <b>하나의 트랜잭션</b>으로 묶어야
 * upsert 내부 findById 가 1차 캐시(같은 persistence context)에서 read 가 적재한 엔티티(=read 시점 version)를 돌려주고,
 * commit 시 {@code UPDATE ... WHERE version = <read시점>} 가 발사된다. 다른 트랜잭션이 사이에 version 을 올렸으면
 * 0 row → {@link OptimisticLockingFailureException} → 재시도(재조회·재적용)로 lost-update 를 막는다(SPEC §3, Plan §5 #2).</p>
 */
@Component
public class EqpPlcPollGroupAdapter implements EqpPlcPollGroupPort {

    private static final Logger log = LoggerFactory.getLogger(EqpPlcPollGroupAdapter.class);

    /** 동시 GROUP_START 충돌 시 재조회·재적용 재시도 한도. */
    private static final int MAX_OPTIMISTIC_LOCK_RETRIES = 5;

    private final TcEqpPlcStore eqpPlcStore;
    private final TransactionTemplate transactionTemplate;

    public EqpPlcPollGroupAdapter(
            final TcEqpPlcStore eqpPlcStore,
            final PlatformTransactionManager transactionManager
    ) {
        this.eqpPlcStore = Objects.requireNonNull(eqpPlcStore, "eqpPlcStore is null");
        this.transactionTemplate = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager is null"));
    }

    @Override
    public void addPollGroup(final long eqpKey, final String groupMessageName) {
        final String group = requireGroup(groupMessageName);
        mutateWithRetry(eqpKey, current -> {
            final List<String> next = new ArrayList<>(current);
            if (!next.contains(group)) {
                next.add(group);
            }
            return next;
        }, true);
    }

    @Override
    public void removePollGroup(final long eqpKey, final String groupMessageName) {
        final String group = requireGroup(groupMessageName);
        mutateWithRetry(eqpKey, current -> {
            final List<String> next = new ArrayList<>(current);
            next.remove(group);
            return next;
        }, true);
    }

    @Override
    public void clearPollGroups(final long eqpKey) {
        // EQP_END(graceful)는 protocol-agnostic 이라 SECS/SOCKET(=tc_eqp_plc 행 없음)에도 호출된다 → PLC 행 없으면 no-op.
        mutateWithRetry(eqpKey, current -> List.of(), false);
    }

    private void mutateWithRetry(
            final long eqpKey,
            final UnaryOperator<List<String>> mutation,
            final boolean requirePlcRow
    ) {
        OptimisticLockingFailureException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_OPTIMISTIC_LOCK_RETRIES; attempt++) {
            try {
                transactionTemplate.executeWithoutResult(status -> applyMutation(eqpKey, mutation, requirePlcRow));
                return;
            } catch (OptimisticLockingFailureException failure) {
                lastFailure = failure;
                log.debug("enabled_poll_groups optimistic lock 충돌 — 재조회·재시도. eqpKey={}, attempt={}/{}",
                        eqpKey, attempt, MAX_OPTIMISTIC_LOCK_RETRIES);
            }
        }
        log.error("enabled_poll_groups 동시 변경 충돌 재시도 한도 초과. eqpKey={}, retries={}",
                eqpKey, MAX_OPTIMISTIC_LOCK_RETRIES, lastFailure);
        throw new UiConflictException(
                "폴링 그룹 동시 변경 충돌로 갱신에 실패했습니다. eqpKey=" + eqpKey);
    }

    /**
     * 단일 트랜잭션 내에서 findByEqpKey → mutate → upsert 를 수행합니다(@Version 발사 조건).
     */
    private void applyMutation(
            final long eqpKey,
            final UnaryOperator<List<String>> mutation,
            final boolean requirePlcRow
    ) {
        final TcEqpPlc plc = eqpPlcStore.findByEqpKey(eqpKey).orElse(null);
        if (plc == null) {
            if (requirePlcRow) {
                throw new UiBadRequestException(
                        "PLC 설비가 아니거나 tc_eqp_plc 행이 없어 폴링 그룹을 변경할 수 없습니다. eqpKey=" + eqpKey);
            }
            return; // clear() on non-PLC eqp = no-op
        }

        final List<String> current = PlcEnabledPollGroupsCodec.deserialize(plc.enabledPollGroups());
        final String serialized = PlcEnabledPollGroupsCodec.serialize(mutation.apply(current));
        if (serialized.equals(plc.enabledPollGroups())) {
            return; // 변경 없음(멱등: 이미 포함된 add / 없는 group 의 remove) → version bump 불필요
        }

        eqpPlcStore.upsert(new UpsertTcEqpPlc(
                plc.eqpKey(),
                plc.plcProtocol(),
                plc.transport(),
                plc.unitId(),
                plc.pollTimeoutMs(),
                plc.reconnectMaxRetries(),
                serialized
        ));
    }

    private String requireGroup(final String groupMessageName) {
        if (groupMessageName == null || groupMessageName.isBlank()) {
            throw new UiBadRequestException("groupMessageName은 비어 있을 수 없습니다.");
        }
        return groupMessageName.trim();
    }
}

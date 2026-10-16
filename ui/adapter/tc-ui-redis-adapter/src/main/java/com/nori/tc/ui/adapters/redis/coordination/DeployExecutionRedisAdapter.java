package com.nori.tc.ui.adapters.redis.coordination;

import com.nori.tc.common.coordination.CoordinationKeys;
import com.nori.common.delivery.store.CoordinationOutcome;
import com.nori.tc.common.coordination.store.FencedLeaseRedisStore;
import com.nori.tc.ui.core.port.redis.DeployExecutionCoordinationPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

import static com.nori.tc.ui.adapters.redis.coordination.UiCoordinationFailures.translating;

/**
 * Deploy/direct target 실행의 lease 와 idempotency 를 Redis 에 기록하는 어댑터입니다(§13.4).
 *
 * <p>lease 는 공통 {@link FencedLeaseRedisStore} 로 위임합니다 — epoch counter 증가와 lease 설정을
 * 한 스크립트에서 수행하고, 해제는 {@code session + epoch} 일치일 때만 성공합니다. 종전 구현은
 * {@code SETNX} 실패 시 {@code GET} 으로 소유자를 확인하고 {@code EXPIRE} 로 연장했는데, 그 사이
 * lease 가 만료되어 다른 인스턴스가 소유자가 되면 옛 소유자가 새 lease 를 연장하거나 지웠습니다.</p>
 *
 * <p>실행 claim 은 시작 전 {@code COMPLETED} 를 쓰지 않습니다 — 완료 표시는 실행이 끝난 뒤에만
 * 기록되고, claim 은 소유 프로세스가 죽으면 TTL 로 풀려 다른 Pod 가 이어받습니다.</p>
 */
public final class DeployExecutionRedisAdapter implements DeployExecutionCoordinationPort {

    private static final Logger log = LoggerFactory.getLogger(DeployExecutionRedisAdapter.class);

    private static final String EXECUTION_KEY_PREFIX = "tc:ui:backend:deploy:execution:";
    private static final String CLAIM_VALUE_PREFIX = "CLAIMED:";
    private static final String COMPLETED_VALUE = "COMPLETED";

    private final FencedLeaseRedisStore leaseStore;
    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 어댑터를 만듭니다.
     *
     * <p>claim key 는 <b>String 직렬화 template</b> 으로만 다룹니다 — JSON 직렬화 template 과 섞으면
     * 같은 key 를 한쪽은 {@code COMPLETED}, 다른 쪽은 {@code "COMPLETED"} 로 읽어 비교가 어긋납니다.</p>
     *
     * @param leaseStore 공통 fenced lease store
     * @param stringRedisTemplate 실행 claim 저장용 String template
     */
    public DeployExecutionRedisAdapter(
            final FencedLeaseRedisStore leaseStore,
            final StringRedisTemplate stringRedisTemplate
    ) {
        this.leaseStore = Objects.requireNonNull(leaseStore, "leaseStore is null");
        this.stringRedisTemplate = Objects.requireNonNull(stringRedisTemplate, "stringRedisTemplate is null");
    }

    @Override
    public Optional<Long> tryAcquireTargetLease(final String eqpId, final String ownerToken, final long ttlMs) {
        Objects.requireNonNull(eqpId, "eqpId is null");
        Objects.requireNonNull(ownerToken, "ownerToken is null");

        /*
         * §13.6 — direct mutation 이 이 설비를 claim 중이면 lease 를 주지 않는다. 두 경로가 서로 다른
         * 키로 배타를 잡고 있어, 함께 검사하지 않으면 같은 설비에서 deploy 와 direct mutation 이 동시에
         * 돈다. 검사는 원자 연산 안에서 수행된다.
         */
        final CoordinationOutcome outcome = translating(() -> leaseStore.acquireEquipmentExecution(
                CoordinationKeys.equipmentExecutionEpochKey(eqpId),
                CoordinationKeys.equipmentExecutionLeaseKey(eqpId),
                CoordinationKeys.resourceHeadKey(CoordinationKeys.RESOURCE_TYPE_EQUIPMENT, eqpId),
                ownerToken,
                Math.max(1L, ttlMs),
                "{\"role\":\"deploy-target\"}",
                System.currentTimeMillis()));
        if ("OK".equals(outcome.code())) {
            return Optional.of(Long.parseLong(outcome.value(0)));
        }
        if ("RESOURCE_BUSY".equals(outcome.code())) {
            log.warn("deploy target lease 미획득 — direct mutation 이 진행 중입니다. eqpId={}, operationId={}",
                    eqpId, outcome.value(0));
            return Optional.empty();
        }
        if (log.isDebugEnabled()) {
            log.debug("deploy target lease 미획득. eqpId={}, code={}, owner={}",
                    eqpId, outcome.code(), outcome.value(0));
        }
        return Optional.empty();
    }

    @Override
    public void releaseTargetLease(final String eqpId, final String ownerToken, final long epoch) {
        if (eqpId == null || ownerToken == null) {
            return;
        }
        final String code = translating(() -> leaseStore.release(
                CoordinationKeys.equipmentExecutionLeaseKey(eqpId),
                ownerToken,
                epoch,
                System.currentTimeMillis()).code());
        if (!"OK".equals(code)) {
            log.warn("deploy target lease 해제가 거부되었습니다. eqpId={}, ownerToken={}, epoch={}, code={}",
                    eqpId, ownerToken, epoch, code);
        }
    }

    @Override
    public ExecutionClaim claimTargetExecution(
            final String executionKey,
            final String ownerToken,
            final long claimTtlMs
    ) {
        Objects.requireNonNull(executionKey, "executionKey is null");
        Objects.requireNonNull(ownerToken, "ownerToken is null");

        final String key = EXECUTION_KEY_PREFIX + executionKey;
        final String claimValue = CLAIM_VALUE_PREFIX + ownerToken;
        final Duration claimTtl = Duration.ofMillis(Math.max(1L, claimTtlMs));
        if (Boolean.TRUE.equals(stringRedisTemplate.opsForValue().setIfAbsent(key, claimValue, claimTtl))) {
            return ExecutionClaim.CLAIMED;
        }

        final String current = stringRedisTemplate.opsForValue().get(key);
        if (COMPLETED_VALUE.equals(current)) {
            return ExecutionClaim.ALREADY_COMPLETED;
        }
        if (claimValue.equals(current)) {
            stringRedisTemplate.expire(key, claimTtl);
            return ExecutionClaim.CLAIMED;
        }
        return ExecutionClaim.HELD_BY_OTHER;
    }

    @Override
    public void completeTargetExecution(final String executionKey, final long retentionTtlMs) {
        if (executionKey == null) {
            return;
        }
        final String key = EXECUTION_KEY_PREFIX + executionKey;
        stringRedisTemplate.opsForValue().set(
                key, COMPLETED_VALUE, Duration.ofMillis(Math.max(1L, retentionTtlMs)));
    }
}

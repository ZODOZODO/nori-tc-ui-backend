package com.nori.tc.ui.adapters.redis.coordination;

import com.nori.tc.common.coordination.script.CoordinationScriptExecutor;
import com.nori.tc.common.coordination.store.CatalogHeadRedisStore;
import com.nori.tc.common.coordination.store.ClusterEpochRedisStore;
import com.nori.tc.common.coordination.store.FencedLeaseRedisStore;
import com.nori.tc.common.coordination.store.MembershipRedisStore;
import com.nori.tc.common.coordination.store.OperationLedgerRedisStore;
import com.nori.tc.common.coordination.store.ResourceBarrierRedisStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import java.util.Objects;

/**
 * UI Backend 가 사용하는 coordination store 묶음입니다.
 *
 * <p>cluster epoch 은 <b>조회만</b> 합니다 — 애플리케이션이 epoch 을 발급하면 "epoch 제거 시 FENCED"
 * (§23.9) 요구가 깨지므로, 없으면 기동을 실패시킵니다. epoch 발급은 배포 절차(§22.1)의 몫입니다.</p>
 */
public final class UiCoordinationContext {

    private static final Logger log = LoggerFactory.getLogger(UiCoordinationContext.class);

    private final String clusterEpoch;
    private final CoordinationScriptExecutor executor;
    private final ClusterEpochRedisStore clusterEpochStore;
    private final OperationLedgerRedisStore operationLedgerStore;
    private final ResourceBarrierRedisStore resourceBarrierStore;
    private final MembershipRedisStore membershipStore;
    private final FencedLeaseRedisStore leaseStore;
    private final CatalogHeadRedisStore catalogHeadStore;

    /**
     * 공용 Redis 연결로 coordination store 를 구성합니다.
     *
     * @param connectionFactory Redis 연결 팩토리
     */
    public UiCoordinationContext(final RedisConnectionFactory connectionFactory) {
        Objects.requireNonNull(connectionFactory, "connectionFactory is null");
        this.executor = new CoordinationScriptExecutor(connectionFactory);
        this.clusterEpochStore = new ClusterEpochRedisStore(executor);
        this.clusterEpoch = clusterEpochStore.current()
                .orElseThrow(() -> new IllegalStateException(
                        "cluster epoch is not issued yet — the maintenance-window procedure must create "
                                + "tc:coord:v1:cluster:epoch before starting coordination-enabled pods"))
                .value();
        this.operationLedgerStore = new OperationLedgerRedisStore(executor, clusterEpoch);
        this.resourceBarrierStore = new ResourceBarrierRedisStore(executor, clusterEpoch);
        this.membershipStore = new MembershipRedisStore(executor, clusterEpoch);
        this.leaseStore = new FencedLeaseRedisStore(executor, clusterEpoch);
        this.catalogHeadStore = new CatalogHeadRedisStore(executor, clusterEpoch);
        log.info("UI_COORDINATION_CONTEXT_READY. clusterEpoch={}", clusterEpoch);
    }

    public String clusterEpoch() {
        return clusterEpoch;
    }

    public CoordinationScriptExecutor executor() {
        return executor;
    }

    public ClusterEpochRedisStore clusterEpochStore() {
        return clusterEpochStore;
    }

    public OperationLedgerRedisStore operationLedgerStore() {
        return operationLedgerStore;
    }

    public ResourceBarrierRedisStore resourceBarrierStore() {
        return resourceBarrierStore;
    }

    public MembershipRedisStore membershipStore() {
        return membershipStore;
    }

    public FencedLeaseRedisStore leaseStore() {
        return leaseStore;
    }

    public CatalogHeadRedisStore catalogHeadStore() {
        return catalogHeadStore;
    }
}

package com.nori.tc.ui.adapters.redis.coordination;

import com.nori.tc.common.coordination.store.CatalogHeadRedisStore;
import com.nori.tc.common.coordination.store.ClusterEpochRedisStore;
import com.nori.tc.ui.core.coordination.port.UiCoordinationProbePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * {@link UiCoordinationProbePort} 의 Redis 구현입니다(§13.11/§23.9).
 *
 * <p>{@code PING} 대신 <b>실제 atomic script</b> 를 돌립니다 — 연결만 살아 있고 script 실행이 막힌
 * 상태에서 Ready 로 두면 mutation 을 받아 그 자리에서 실패합니다. 부작용을 남기지 않도록, 이미 커밋된
 * head 를 같은 값으로 다시 커밋해 보는 대신 <b>과거 revision 으로의 커밋 시도</b>를 사용합니다:
 * 스크립트는 실행되지만 monotonic 조건에 걸려 상태를 바꾸지 않습니다.</p>
 */
public final class RedisUiCoordinationProbeAdapter implements UiCoordinationProbePort {

    private static final Logger log = LoggerFactory.getLogger(RedisUiCoordinationProbeAdapter.class);

    /** probe 전용 catalog type — 실제 catalog 와 key 가 겹치지 않게 둡니다. */
    private static final String PROBE_CATALOG_TYPE = "ui-backend-probe";

    private static final String PROBE_DIGEST = "0".repeat(64);

    private final CatalogHeadRedisStore catalogHeadStore;
    private final ClusterEpochRedisStore clusterEpochStore;
    private final String expectedClusterEpoch;

    /**
     * probe 어댑터를 만듭니다.
     *
     * @param catalogHeadStore 공통 catalog head store
     * @param clusterEpochStore cluster epoch store
     * @param expectedClusterEpoch 기동 시 확인한 cluster epoch
     */
    public RedisUiCoordinationProbeAdapter(
            final CatalogHeadRedisStore catalogHeadStore,
            final ClusterEpochRedisStore clusterEpochStore,
            final String expectedClusterEpoch
    ) {
        this.catalogHeadStore = Objects.requireNonNull(catalogHeadStore, "catalogHeadStore is null");
        this.clusterEpochStore = Objects.requireNonNull(clusterEpochStore, "clusterEpochStore is null");
        this.expectedClusterEpoch = Objects.requireNonNull(expectedClusterEpoch, "expectedClusterEpoch is null");
    }

    @Override
    public boolean verifyAtomicScriptReadWrite() {
        try {
            final long current = catalogHeadStore.readHead(PROBE_CATALOG_TYPE).revision();
            final String code = catalogHeadStore.commitHead(
                    PROBE_CATALOG_TYPE, current + 1L, PROBE_DIGEST, current, System.currentTimeMillis()).code();
            return "OK".equals(code);
        } catch (RuntimeException ex) {
            log.error("UI_COORDINATION_SCRIPT_PROBE_FAILED.", ex);
            return false;
        }
    }

    @Override
    public boolean clusterEpochUnchanged() {
        try {
            return clusterEpochStore.current()
                    .map(epoch -> expectedClusterEpoch.equals(epoch.value()))
                    .orElse(false);
        } catch (RuntimeException ex) {
            log.error("UI_COORDINATION_EPOCH_PROBE_FAILED.", ex);
            return false;
        }
    }
}

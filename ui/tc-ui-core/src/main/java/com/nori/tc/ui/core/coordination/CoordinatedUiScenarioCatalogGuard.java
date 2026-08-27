package com.nori.tc.ui.core.coordination;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link UiScenarioCatalogGuard} 의 조정 원장 구현입니다(§13.9).
 */
public final class CoordinatedUiScenarioCatalogGuard implements UiScenarioCatalogGuard {

    private static final Logger log = LoggerFactory.getLogger(CoordinatedUiScenarioCatalogGuard.class);

    private final UiCatalogHeadSynchronizer synchronizer;
    private final AtomicReference<String> localDigest = new AtomicReference<>();

    /**
     * 게이트를 만듭니다.
     *
     * @param synchronizer scenario catalog head 동기화기
     */
    public CoordinatedUiScenarioCatalogGuard(final UiCatalogHeadSynchronizer synchronizer) {
        this.synchronizer = Objects.requireNonNull(synchronizer, "synchronizer is null");
    }

    @Override
    public void onCatalogApplied(final String catalogChecksum) {
        localDigest.set(Objects.requireNonNull(catalogChecksum, "catalogChecksum is null"));
        final UiCatalogHeadSynchronizer.SyncState state =
                synchronizer.reconcile(catalogChecksum, System.currentTimeMillis());
        log.info("UI_SCENARIO_CATALOG_HEAD. inSync={}, localRevision={}, headRevision={}, digest={}",
                state.inSync(), state.localRevision(), state.headRevision(), catalogChecksum);
    }

    @Override
    public void requirePlanningAllowed(final String catalogChecksum) {
        synchronizer.requireInSync(catalogChecksum);
    }

    /**
     * head 와 일치하는지 <b>매번 다시 확인</b>합니다.
     *
     * <p>캐시된 판정을 돌려주면 안 됩니다. 이 Pod 이 reload 도 planning 도 하지 않는 동안 다른 Pod 이
     * 새 catalog 를 커밋하면 head 만 움직이는데, 그때 이 Pod 은 자기 마지막 판정을 그대로 들고 Ready 로
     * 남습니다 — §13.11 이 catalog revision mismatch 를 readiness 로 노출하라고 요구하는 지점이고,
     * §13.9 도 mismatch 상태를 readiness 로 드러내라고 명시합니다. permission catalog guard 가 같은
     * 이유로 이미 live 조회를 합니다.</p>
     *
     * @return head 와 일치하면 true
     */
    @Override
    public boolean inSync() {
        final String digest = localDigest.get();
        if (digest == null) {
            // 아직 한 번도 적재하지 못했다 — 미확정은 Ready 가 아니다.
            return false;
        }
        try {
            return synchronizer.verify(digest);
        } catch (RuntimeException ex) {
            /*
             * head 조회 실패를 "일치" 로 축약하면 Redis 장애 중 mismatch Pod 이 Ready 로 남는다 —
             * §9.3 의 fail-closed 를 정면으로 어긴다.
             */
            log.error("UI_SCENARIO_CATALOG_HEAD_UNAVAILABLE. readiness 를 fail-closed 로 처리합니다.", ex);
            return false;
        }
    }

    @Override
    public long headRevision() {
        return synchronizer.state().headRevision();
    }
}

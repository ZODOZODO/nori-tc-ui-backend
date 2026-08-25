package com.nori.tc.ui.core.coordination;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * {@link UiScenarioCatalogGuard} 의 조정 원장 구현입니다(§13.9).
 */
public final class CoordinatedUiScenarioCatalogGuard implements UiScenarioCatalogGuard {

    private static final Logger log = LoggerFactory.getLogger(CoordinatedUiScenarioCatalogGuard.class);

    private final UiCatalogHeadSynchronizer synchronizer;

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
        final UiCatalogHeadSynchronizer.SyncState state =
                synchronizer.reconcile(catalogChecksum, System.currentTimeMillis());
        log.info("UI_SCENARIO_CATALOG_HEAD. inSync={}, localRevision={}, headRevision={}, digest={}",
                state.inSync(), state.localRevision(), state.headRevision(), catalogChecksum);
    }

    @Override
    public void requirePlanningAllowed(final String catalogChecksum) {
        synchronizer.requireInSync(catalogChecksum);
    }

    @Override
    public boolean inSync() {
        return synchronizer.state().inSync();
    }

    @Override
    public long headRevision() {
        return synchronizer.state().headRevision();
    }
}

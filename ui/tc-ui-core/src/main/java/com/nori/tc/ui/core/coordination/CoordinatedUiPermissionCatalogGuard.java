package com.nori.tc.ui.core.coordination;

import com.nori.tc.ui.core.coordination.port.UiCoordinationDigestPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link UiPermissionCatalogGuard} 의 조정 원장 구현입니다(§13.10).
 */
public final class CoordinatedUiPermissionCatalogGuard implements UiPermissionCatalogGuard {

    private static final Logger log = LoggerFactory.getLogger(CoordinatedUiPermissionCatalogGuard.class);

    private final UiCatalogHeadSynchronizer synchronizer;
    private final UiCoordinationDigestPort digestPort;
    private final AtomicReference<String> localDigest = new AtomicReference<>();

    /**
     * 게이트를 만듭니다.
     *
     * @param synchronizer permission catalog head 동기화기
     * @param digestPort canonical digest 계산 포트
     */
    public CoordinatedUiPermissionCatalogGuard(
            final UiCatalogHeadSynchronizer synchronizer,
            final UiCoordinationDigestPort digestPort
    ) {
        this.synchronizer = Objects.requireNonNull(synchronizer, "synchronizer is null");
        this.digestPort = Objects.requireNonNull(digestPort, "digestPort is null");
    }

    @Override
    public void onPermissionsApplied(final String canonicalPermissions) {
        final String digest = digestPort.sha256Hex(Objects.requireNonNull(canonicalPermissions, "canonicalPermissions is null"));
        localDigest.set(digest);
        final UiCatalogHeadSynchronizer.SyncState state = synchronizer.reconcile(digest, System.currentTimeMillis());
        log.info("UI_PERMISSION_CATALOG_HEAD. inSync={}, localRevision={}, headRevision={}, digest={}",
                state.inSync(), state.localRevision(), state.headRevision(), digest);
    }

    @Override
    public boolean inSync() {
        final String digest = localDigest.get();
        if (digest == null) {
            // 아직 한 번도 적재하지 못했다 — 인가 판단에서 미확정은 거부다.
            return false;
        }
        try {
            return synchronizer.verify(digest);
        } catch (RuntimeException ex) {
            /*
             * head 조회 실패를 "일치" 로 축약하면 Redis 장애 중 모든 Pod 가 자기 캐시로 무기한 허용을
             * 계속한다 — §13.10 이 금지하는 동작이다.
             */
            log.error("UI_PERMISSION_CATALOG_HEAD_UNAVAILABLE. 인가 판단을 fail-closed 로 처리합니다.", ex);
            return false;
        }
    }

    @Override
    public long headRevision() {
        return synchronizer.state().headRevision();
    }
}

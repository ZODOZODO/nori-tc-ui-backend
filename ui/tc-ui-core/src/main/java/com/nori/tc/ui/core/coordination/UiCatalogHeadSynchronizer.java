package com.nori.tc.ui.core.coordination;

import com.nori.tc.ui.core.coordination.model.UiCatalogHeadView;
import com.nori.tc.ui.core.coordination.port.UiCatalogHeadPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 로컬 catalog digest 를 Redis head 와 맞추고, 어긋난 Pod 를 fail-closed 로 만듭니다(§13.9/§13.10).
 *
 * <p>전 session 의 적용 proof 를 모으는 전역 게이트는 두지 않습니다 — §13.9 가 명시적으로 그것을
 * 요구하지 않고, 정합성의 최종 방어선을 "요청 접수 직전의 head 비교" 로 두기 때문입니다. 그래서 이
 * 동기화기는 (1) 내 digest 가 head 와 같으면 head revision 을 채택하고, (2) 다르면 CAS 로 새 head 를
 * commit 하며, (3) commit 이 다른 Pod 에 밀리면 다시 읽어 판정합니다.</p>
 */
public final class UiCatalogHeadSynchronizer {

    private static final Logger log = LoggerFactory.getLogger(UiCatalogHeadSynchronizer.class);
    private static final int COMMIT_RETRY_MAX = 3;

    /**
     * 이 Pod 가 관측한 catalog 동기화 상태입니다.
     *
     * @param inSync head 와 일치하면 true
     * @param localRevision 이 Pod 가 채택한 revision(미동기면 마지막 관측값)
     * @param headRevision 마지막으로 읽은 head revision
     * @param localDigest 이 Pod 의 catalog digest
     * @param headDigest 마지막으로 읽은 head digest
     */
    public record SyncState(
            boolean inSync, long localRevision, long headRevision, String localDigest, String headDigest) {

        /** 아직 한 번도 동기화하지 않은 초기 상태입니다. */
        static SyncState initial() {
            return new SyncState(false, 0L, 0L, null, null);
        }
    }

    private final UiCatalogHeadPort catalogHeadPort;
    private final String catalogType;
    private final AtomicReference<SyncState> syncState = new AtomicReference<>(SyncState.initial());

    /**
     * 동기화기를 만듭니다.
     *
     * @param catalogHeadPort catalog head 포트
     * @param catalogType catalog 종류
     */
    public UiCatalogHeadSynchronizer(final UiCatalogHeadPort catalogHeadPort, final String catalogType) {
        this.catalogHeadPort = Objects.requireNonNull(catalogHeadPort, "catalogHeadPort is null");
        this.catalogType = Objects.requireNonNull(catalogType, "catalogType is null");
    }

    /**
     * 현재 동기화 상태를 반환합니다.
     *
     * @return 마지막 판정 결과
     */
    public SyncState state() {
        return syncState.get();
    }

    /**
     * 로컬 digest 를 head 와 맞춥니다 — 다르면 새 head 를 commit 합니다.
     *
     * <p>validation 을 통과한 candidate 에만 호출해야 합니다. validation 실패 시 호출하면 검증되지
     * 않은 catalog 가 head 가 되어 다른 Pod 전부가 fail-closed 됩니다(§13.9).</p>
     *
     * @param localDigest 이 Pod 의 catalog digest
     * @param nowEpochMs 현재 시각
     * @return 동기화 후 상태
     */
    public SyncState reconcile(final String localDigest, final long nowEpochMs) {
        Objects.requireNonNull(localDigest, "localDigest is null");

        for (int attempt = 1; attempt <= COMMIT_RETRY_MAX; attempt++) {
            final UiCatalogHeadView head = catalogHeadPort.readHead(catalogType);
            if (!head.uncommitted() && localDigest.equals(head.digest())) {
                return adopt(head, localDigest);
            }

            final long newRevision = head.revision() + 1L;
            final String code = catalogHeadPort.commitHead(
                    catalogType, newRevision, localDigest, head.revision(), nowEpochMs);
            if ("OK".equals(code)) {
                log.info("UI_CATALOG_HEAD_COMMITTED. catalogType={}, revision={}, digest={}",
                        catalogType, newRevision, localDigest);
                return adopt(new UiCatalogHeadView(catalogType, newRevision, localDigest), localDigest);
            }
            log.warn("UI_CATALOG_HEAD_COMMIT_RETRY. catalogType={}, attempt={}, code={}, headRevision={}",
                    catalogType, attempt, code, head.revision());
        }

        final UiCatalogHeadView head = catalogHeadPort.readHead(catalogType);
        final SyncState state = new SyncState(
                localDigest.equals(head.digest()), head.revision(), head.revision(), localDigest, head.digest());
        syncState.set(state);
        if (!state.inSync()) {
            log.error("UI_CATALOG_HEAD_MISMATCH. catalogType={}, localDigest={}, headDigest={}, headRevision={}",
                    catalogType, localDigest, head.digest(), head.revision());
        }
        return state;
    }

    /**
     * head 와 일치하는지만 다시 확인합니다(요청 접수 직전 판정 — commit 하지 않습니다).
     *
     * @param localDigest 이 Pod 의 catalog digest
     * @return 일치하면 true
     */
    public boolean verify(final String localDigest) {
        final UiCatalogHeadView head = catalogHeadPort.readHead(catalogType);
        final boolean matched = localDigest != null && localDigest.equals(head.digest());
        syncState.set(new SyncState(
                matched,
                matched ? head.revision() : syncState.get().localRevision(),
                head.revision(),
                localDigest,
                head.digest()));
        return matched;
    }

    /**
     * head 와 다르면 요청을 거부합니다.
     *
     * @param localDigest 이 Pod 의 catalog digest
     * @throws UiCatalogNotSyncedException head 와 다를 때
     */
    public void requireInSync(final String localDigest) {
        if (!verify(localDigest)) {
            final SyncState state = syncState.get();
            throw new UiCatalogNotSyncedException(catalogType,
                    "catalog head mismatch — 이 Pod 의 catalog 가 클러스터 head 와 다릅니다. catalogType=" + catalogType
                            + ", localDigest=" + state.localDigest()
                            + ", headDigest=" + state.headDigest()
                            + ", headRevision=" + state.headRevision());
        }
    }

    private SyncState adopt(final UiCatalogHeadView head, final String localDigest) {
        final SyncState state =
                new SyncState(true, head.revision(), head.revision(), localDigest, head.digest());
        syncState.set(state);
        return state;
    }
}

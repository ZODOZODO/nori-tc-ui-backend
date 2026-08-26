package com.nori.tc.ui.adapters.redis.coordination;

import com.nori.tc.common.coordination.store.CatalogHeadRedisStore;
import com.nori.tc.common.coordination.types.CatalogHead;
import com.nori.tc.ui.core.coordination.model.UiCatalogHeadView;
import com.nori.tc.ui.core.coordination.port.UiCatalogHeadPort;

import java.util.Objects;

import static com.nori.tc.ui.adapters.redis.coordination.UiCoordinationFailures.translating;

/**
 * {@link UiCatalogHeadPort} 의 Redis 구현입니다(§13.9/§13.10).
 */
public final class RedisUiCatalogHeadAdapter implements UiCatalogHeadPort {

    private final CatalogHeadRedisStore store;

    /**
     * 어댑터를 만듭니다.
     *
     * @param store 공통 catalog head store
     */
    public RedisUiCatalogHeadAdapter(final CatalogHeadRedisStore store) {
        this.store = Objects.requireNonNull(store, "store is null");
    }

    @Override
    public String commitHead(
            final String catalogType,
            final long newRevision,
            final String digest,
            final Long expectedRevision,
            final long nowEpochMs
    ) {
        return translating(() ->
                store.commitHead(catalogType, newRevision, digest, expectedRevision, nowEpochMs).code());
    }

    @Override
    public UiCatalogHeadView readHead(final String catalogType) {
        final CatalogHead head = translating(() -> store.readHead(catalogType));
        return new UiCatalogHeadView(head.catalogType(), head.revision(), head.digest());
    }
}

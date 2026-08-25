package com.nori.tc.ui.core.coordination;

import java.util.Objects;

/**
 * 이 Pod 의 catalog 가 Redis head 와 달라 요청을 받을 수 없는 상태입니다(§13.9/§13.10 → 503).
 *
 * <p>head mismatch Pod 는 자동으로 새 catalog 를 얻지 못한다 — scenario 디렉터리가 Pod-로컬
 * 외부 경로이기 때문이다. 복구는 새 YAML 배포 후 해당 Pod reload 또는 rollout restart 이며,
 * 그전까지 이 Pod 는 planning 을 거부하고 readiness 에서 빠진다.</p>
 */
public class UiCatalogNotSyncedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 이 예외에 대응하는 API error code 입니다. */
    public static final String ERROR_CODE = "CATALOG_NOT_SYNCED";

    private final String catalogType;

    /**
     * mismatch 예외를 만듭니다.
     *
     * @param catalogType catalog 종류
     * @param message 사유
     */
    public UiCatalogNotSyncedException(final String catalogType, final String message) {
        super(message);
        this.catalogType = Objects.requireNonNull(catalogType, "catalogType is null");
    }

    /**
     * catalog 종류를 반환합니다.
     *
     * @return catalog 종류
     */
    public String catalogType() {
        return catalogType;
    }
}

package com.nori.tc.ui.core.coordination.model;

import java.util.Objects;

/**
 * catalog head 스냅샷입니다(§13.9/§13.10).
 *
 * @param catalogType catalog 종류
 * @param revision head revision(미커밋이면 0)
 * @param digest canonical digest(미커밋이면 null)
 */
public record UiCatalogHeadView(String catalogType, long revision, String digest) {

    /**
     * 필수 값을 검증합니다.
     */
    public UiCatalogHeadView {
        Objects.requireNonNull(catalogType, "catalogType is null");
    }

    /**
     * head 가 아직 커밋되지 않았는지 반환합니다.
     *
     * @return 미커밋이면 true
     */
    public boolean uncommitted() {
        return digest == null || digest.isBlank();
    }

    /**
     * 로컬 catalog 가 head 와 같은지 판정합니다.
     *
     * @param localRevision 로컬 revision
     * @param localDigest 로컬 digest
     * @return revision 과 digest 가 모두 같으면 true
     */
    public boolean matches(final long localRevision, final String localDigest) {
        return revision == localRevision && Objects.equals(digest, localDigest);
    }
}

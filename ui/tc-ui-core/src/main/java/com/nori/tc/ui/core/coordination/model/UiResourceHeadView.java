package com.nori.tc.ui.core.coordination.model;

import java.util.Objects;

/**
 * 리소스 처리 장벽 스냅샷입니다(§7.3).
 *
 * @param resourceKey resource head key
 * @param committedRevision 커밋된 revision
 * @param openRevision 개방된 revision
 * @param blockedByOperationId 장벽을 닫고 있는 operation(없으면 null)
 * @param activeOperationId 리소스를 claim 한 operation(없으면 null)
 */
public record UiResourceHeadView(
        String resourceKey,
        long committedRevision,
        long openRevision,
        String blockedByOperationId,
        String activeOperationId
) {

    /**
     * 필수 값을 검증합니다.
     */
    public UiResourceHeadView {
        Objects.requireNonNull(resourceKey, "resourceKey is null");
    }

    /**
     * 이 리소스가 새 mutation 을 받아들일 수 있는지 반환합니다.
     *
     * @return 장벽이 열려 있고 다른 operation 이 claim 중이 아니면 true
     */
    public boolean freeForNewOperation() {
        return blockedByOperationId == null && activeOperationId == null && committedRevision == openRevision;
    }
}

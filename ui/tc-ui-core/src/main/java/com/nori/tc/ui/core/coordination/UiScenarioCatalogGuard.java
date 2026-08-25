package com.nori.tc.ui.core.coordination;

/**
 * deploy scenario catalog 의 head 정합성 게이트입니다(§13.9).
 *
 * <p>manual reload 가 특정 Pod 의 메모리만 바꾸고 끝나는 동작을 금지하기 위해, catalog 를 적재한
 * Pod 는 반드시 head commit 을 동반해야 하고, planning 을 받기 전 head 와의 일치를 확인해야 합니다.</p>
 */
public interface UiScenarioCatalogGuard {

    /**
     * catalog 를 적재/재적재한 뒤 head 를 맞춥니다.
     *
     * <p>validation 을 통과한 candidate 에만 호출해야 합니다 — 실패한 candidate 로 head 를 커밋하면
     * 나머지 Pod 전부가 fail-closed 됩니다.</p>
     *
     * @param catalogChecksum 적재된 catalog 의 canonical checksum
     */
    void onCatalogApplied(String catalogChecksum);

    /**
     * planning 접수 직전에 head 일치를 확인합니다.
     *
     * @param catalogChecksum 현재 이 Pod 의 catalog checksum
     * @throws UiCatalogNotSyncedException head 와 다를 때
     */
    void requirePlanningAllowed(String catalogChecksum);

    /**
     * 이 Pod 가 head 와 동기 상태인지 반환합니다(readiness 용 — §13.11).
     *
     * @return 동기이면 true
     */
    boolean inSync();

    /**
     * 이 Pod 가 채택한 head revision 을 반환합니다.
     *
     * @return head revision(미동기/미조정이면 0)
     */
    long headRevision();

    /**
     * 조정을 쓰지 않는 구성용 게이트를 반환합니다.
     *
     * @return 항상 통과시키는 게이트
     */
    static UiScenarioCatalogGuard disabled() {
        return new UiScenarioCatalogGuard() {
            @Override
            public void onCatalogApplied(final String catalogChecksum) {
                // 조정 비활성 구성은 클러스터 head 를 두지 않는다.
            }

            @Override
            public void requirePlanningAllowed(final String catalogChecksum) {
                // 단일 인스턴스 구성에는 비교 대상 head 가 없다.
            }

            @Override
            public boolean inSync() {
                return true;
            }

            @Override
            public long headRevision() {
                return 0L;
            }
        };
    }
}

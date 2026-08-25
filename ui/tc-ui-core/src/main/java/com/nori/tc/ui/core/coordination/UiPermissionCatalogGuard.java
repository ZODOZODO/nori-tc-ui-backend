package com.nori.tc.ui.core.coordination;

/**
 * API permission catalog 의 head 정합성 게이트입니다(§13.10).
 *
 * <p>전 session 적용 완료를 기다리는 전역 게이트는 두지 않습니다. 각 Pod 가 보호 요청 처리 시점에
 * local permission revision 을 head 와 비교하고, mismatch 인 Pod <b>만</b> fail-closed 합니다 —
 * refresh 실패를 이유로 기존 cache 로 무기한 허용을 계속하지 않습니다.</p>
 */
public interface UiPermissionCatalogGuard {

    /**
     * 로드한 permission 목록의 canonical 표현으로 head 를 맞춥니다.
     *
     * @param canonicalPermissions resource/method/matchType/permCode 순으로 정규화한 표현
     */
    void onPermissionsApplied(String canonicalPermissions);

    /**
     * 이 Pod 의 permission catalog 가 head 와 같은지 반환합니다.
     *
     * <p>보호 요청 처리 지점에서 호출합니다. Redis 조회가 불확실하면 <b>false</b> 를 돌려줍니다 —
     * 인가 판단에서 불확실은 허용이 아니라 거부입니다.</p>
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
     * @return 항상 동기로 보고하는 게이트
     */
    static UiPermissionCatalogGuard disabled() {
        return new UiPermissionCatalogGuard() {
            @Override
            public void onPermissionsApplied(final String canonicalPermissions) {
                // 조정 비활성 구성은 클러스터 head 를 두지 않는다.
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

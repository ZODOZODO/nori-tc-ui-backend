package com.nori.tc.ui.core.coordination.port;

import java.util.OptionalLong;

/**
 * 업무 DB 에 기록된 리소스의 마지막 finalized revision 조회 포트입니다(§13.7/§11.11).
 *
 * <p>이 값이 없으면 원장 후퇴 검증이 성립하지 않는다 — Redis 를 과거 백업으로 되돌린 상태를
 * 정상으로 오인한다. 컬럼 도입은 Flyway V7(P5) 소관이며, 그전까지 {@link #absent()} 는 검증을
 * 무력화한 채 기동 경고로 그 사실을 드러낸다.</p>
 */
public interface UiFinalizedRevisionProvider {

    /**
     * 설비의 마지막 finalized revision 을 조회합니다.
     *
     * @param eqpId 설비 id
     * @return 기록이 있으면 revision, 없으면 empty
     */
    OptionalLong lastFinalizedRevision(String eqpId);

    /**
     * DB finalization 시 마지막 finalized revision 을 기록합니다.
     *
     * @param eqpId 설비 id
     * @param revision 확정된 revision
     * @return 기록에 성공하면 true, 저장소가 이 값을 보관하지 않으면 false
     */
    boolean recordFinalizedRevision(String eqpId, long revision);

    /**
     * 기록을 보관하지 않는 구현을 반환합니다.
     *
     * @return 항상 empty 를 돌려주는 provider
     */
    static UiFinalizedRevisionProvider absent() {
        return new UiFinalizedRevisionProvider() {
            @Override
            public OptionalLong lastFinalizedRevision(final String eqpId) {
                return OptionalLong.empty();
            }

            @Override
            public boolean recordFinalizedRevision(final String eqpId, final long revision) {
                return false;
            }
        };
    }
}

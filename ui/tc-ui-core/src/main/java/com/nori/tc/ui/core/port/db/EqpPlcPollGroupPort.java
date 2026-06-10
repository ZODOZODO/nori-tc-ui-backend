package com.nori.tc.ui.core.port.db;

/**
 * PLC 폴링 그룹 집합({@code tc_eqp_plc.enabled_poll_groups})의 영속 변이 포트입니다.
 *
 * <p>그룹 명령 saga(gateway-confirm-then-DB)의 DB 변이 절반을 담당합니다 — gateway 가
 * EQP_PLC_GROUP_START/END 를 accept(PASS reply)한 뒤에만 호출되어 enabled_poll_groups 를 갱신합니다.
 * ui-backend 가 {@code tc_eqp_plc} 단일 writer 이며(SPEC §2/§3), 그룹 폴링 의도를 영속해
 * gateway 재기동 시 복원의 진실 원천이 됩니다.</p>
 *
 * <p>동시성: enabled_poll_groups 는 varchar(4000) JSON 통째라 read-modify-write 다. 같은 설비의
 * 서로 다른 그룹을 동시에 GROUP_START 하면 last-write-wins 로 한쪽이 유실될 수 있으므로
 * 구현체는 {@code @Version} optimistic lock + 충돌 재시도로 보호한다(SPEC §3 / Plan §5 #2).</p>
 */
public interface EqpPlcPollGroupPort {

    /**
     * 폴링 그룹을 enabled_poll_groups 에 추가합니다(멱등 — 이미 있으면 no-op).
     *
     * @param eqpKey 설비 PK
     * @param groupMessageName 추가할 그룹 messageName
     */
    void addPollGroup(long eqpKey, String groupMessageName);

    /**
     * 폴링 그룹을 enabled_poll_groups 에서 제거합니다(멱등 — 없으면 no-op).
     *
     * @param eqpKey 설비 PK
     * @param groupMessageName 제거할 그룹 messageName
     */
    void removePollGroup(long eqpKey, String groupMessageName);

    /**
     * enabled_poll_groups 를 비웁니다(EQP_END graceful disconnect 시 — SPEC §2-6/§3).
     *
     * <p>PLC 행이 없는 설비(SECS/SOCKET)는 no-op 입니다 — EQP_END 는 protocol-agnostic 경로라
     * 모든 프로토콜에서 호출되기 때문입니다.</p>
     *
     * @param eqpKey 설비 PK
     */
    void clearPollGroups(long eqpKey);
}

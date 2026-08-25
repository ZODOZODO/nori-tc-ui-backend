package com.nori.tc.ui.core.coordination.port;

import com.nori.tc.ui.core.coordination.model.UiMembershipState;

import java.util.List;

/**
 * membership 등록/heartbeat 와 participant 후보 조회 포트입니다(§9.1/§9.2/§11.3).
 */
public interface UiCoordinationMembershipPort {

    /** 등록/갱신 결과입니다. */
    enum Result {
        /** 정상 처리되었습니다. */
        OK,
        /** 같은 sessionId 가 이미 등록돼 있습니다. */
        ALREADY_REGISTERED,
        /** TTL 만료 이력이 있어 같은 sessionId 로 되살아날 수 없습니다. */
        FENCED
    }

    /**
     * session 을 등록합니다.
     *
     * @param sessionId {@code serviceName:podUid:bootId}
     * @param podName 관측용 Pod 이름
     * @param protocolVersion protocol version
     * @param initialState 초기 membership 상태
     * @param heartbeatTtlMs heartbeat TTL
     * @param nowEpochMs 현재 시각
     * @return 등록 결과
     */
    Result register(
            String sessionId,
            String podName,
            String protocolVersion,
            UiMembershipState initialState,
            long heartbeatTtlMs,
            long nowEpochMs);

    /**
     * heartbeat 를 갱신합니다.
     *
     * @param sessionId session 식별자
     * @param protocolVersion protocol version
     * @param state 현재 membership 상태
     * @param lastAppliedGlobalRevision 마지막으로 관측한 전역 revision
     * @param heartbeatTtlMs heartbeat TTL
     * @param nowEpochMs 현재 시각
     * @return 갱신 결과
     */
    Result renew(
            String sessionId,
            String protocolVersion,
            UiMembershipState state,
            long lastAppliedGlobalRevision,
            long heartbeatTtlMs,
            long nowEpochMs);

    /**
     * 지정 서비스의 살아 있는 {@code ACTIVE_SYNCED} + 동일 protocolVersion session 목록을 조회합니다.
     *
     * <p>participant 집합 산출은 <b>반드시 이 필터 조회</b>를 써야 한다 — 상태/protocolVersion 을
     * 거르지 않으면 JOINING/FENCED/구 Pod 가 고정 집합에 들어가 §11.3 과 §22.1 이 깨진다(Plan B17).</p>
     *
     * @param serviceName 대상 서비스명
     * @param protocolVersion 요구 protocol version
     * @param limit 조회 상한
     * @return 조건을 만족하는 sessionId 목록
     */
    List<String> activeSyncedSessionIds(String serviceName, String protocolVersion, long limit);

    /**
     * 특정 session 의 heartbeat 가 만료되었는지 확인합니다.
     *
     * @param serviceName 서비스명
     * @param sessionId session 식별자
     * @return member key 가 없으면 true
     */
    boolean expired(String serviceName, String sessionId);
}

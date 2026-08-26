package com.nori.tc.ui.adapters.redis.coordination;

import com.nori.tc.common.coordination.store.CoordinationOutcome;
import com.nori.tc.common.coordination.store.MembershipRedisStore;
import com.nori.tc.common.coordination.types.MembershipState;
import com.nori.tc.common.coordination.types.SessionIdentity;
import com.nori.tc.ui.core.coordination.model.UiMembershipState;
import com.nori.tc.ui.core.coordination.port.UiCoordinationMembershipPort;

import java.util.List;
import java.util.Objects;

import static com.nori.tc.ui.adapters.redis.coordination.UiCoordinationFailures.translating;

/**
 * {@link UiCoordinationMembershipPort} 의 Redis 구현입니다(§9.1/§9.2/§11.3).
 */
public final class RedisUiMembershipAdapter implements UiCoordinationMembershipPort {

    private final MembershipRedisStore store;

    /**
     * 어댑터를 만듭니다.
     *
     * @param store 공통 membership store
     */
    public RedisUiMembershipAdapter(final MembershipRedisStore store) {
        this.store = Objects.requireNonNull(store, "store is null");
    }

    @Override
    public Result register(
            final String sessionId,
            final String podName,
            final String protocolVersion,
            final UiMembershipState initialState,
            final long heartbeatTtlMs,
            final long nowEpochMs
    ) {
        final CoordinationOutcome outcome = translating(() -> store.register(
                identityOf(sessionId),
                podName,
                protocolVersion,
                MembershipState.valueOf(initialState.name()),
                heartbeatTtlMs,
                nowEpochMs));
        return switch (outcome.code()) {
            case "OK" -> Result.OK;
            case "ALREADY" -> Result.ALREADY_REGISTERED;
            case "FENCED_EXPIRED" -> Result.FENCED;
            default -> throw new IllegalStateException("unexpected member_register code: " + outcome.code());
        };
    }

    @Override
    public Result renew(
            final String sessionId,
            final String protocolVersion,
            final UiMembershipState state,
            final long lastAppliedGlobalRevision,
            final long heartbeatTtlMs,
            final long nowEpochMs
    ) {
        final CoordinationOutcome outcome = translating(() -> store.renewHeartbeat(
                identityOf(sessionId),
                protocolVersion,
                MembershipState.valueOf(state.name()),
                lastAppliedGlobalRevision,
                heartbeatTtlMs,
                nowEpochMs));
        return switch (outcome.code()) {
            case "OK" -> Result.OK;
            case "FENCED" -> Result.FENCED;
            default -> throw new IllegalStateException("unexpected member_renew code: " + outcome.code());
        };
    }

    /**
     * participant 후보를 조회합니다.
     *
     * <p>반드시 상태·protocolVersion 필터 오버로드를 씁니다 — 무필터 조회는 member key 존재만 보므로
     * JOINING/FENCED/구 Pod 를 함께 돌려주고, 그 집합으로 participant 를 고정하면 §11.3 과 §22.1 이
     * 동시에 깨집니다(Plan §8 B17).</p>
     */
    @Override
    public List<String> activeSyncedSessionIds(
            final String serviceName,
            final String protocolVersion,
            final long limit
    ) {
        return translating(() ->
                store.liveSessionIds(serviceName, MembershipState.ACTIVE_SYNCED, protocolVersion, limit));
    }

    @Override
    public boolean expired(final String serviceName, final String sessionId) {
        return translating(() -> store.readMember(serviceName, sessionId).isEmpty());
    }

    private static SessionIdentity identityOf(final String sessionId) {
        final String[] parts = Objects.requireNonNull(sessionId, "sessionId is null").split(":");
        if (parts.length != 3) {
            throw new IllegalArgumentException("malformed sessionId: " + sessionId);
        }
        return new SessionIdentity(parts[0], parts[1], parts[2]);
    }
}

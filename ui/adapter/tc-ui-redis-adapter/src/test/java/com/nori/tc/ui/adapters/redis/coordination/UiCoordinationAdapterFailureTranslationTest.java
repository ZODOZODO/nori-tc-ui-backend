package com.nori.tc.ui.adapters.redis.coordination;

import com.nori.tc.common.coordination.script.CoordinationScriptExecutor;
import com.nori.tc.common.coordination.store.CatalogHeadRedisStore;
import com.nori.tc.common.coordination.store.FencedLeaseRedisStore;
import com.nori.tc.common.coordination.store.MembershipRedisStore;
import com.nori.tc.ui.core.coordination.UiCoordinationUnavailableException;
import com.nori.tc.ui.core.coordination.model.UiExecutorLease;
import com.nori.tc.ui.core.coordination.model.UiMembershipState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Redis 에 접근하는 <b>모든</b> 조정 어댑터가 실패를 ui-core 예외로 번역하는지 고정합니다.
 *
 * <p>왜 store 를 mock 하지 않는가: 이 테스트는 연결 팩토리만 실패시키고
 * {@code CoordinationScriptExecutor → *RedisStore → 어댑터} 체인을 <b>실물로</b> 태웁니다. store 를
 * mock 하면 "이 mock 이 어떤 실패를 재현하는가" 에 답할 수 없고, 실제로는 executor 가 예외를 어떤
 * 타입으로 감싸는지가 번역 성립의 조건이기 때문입니다. 여기서 재현하는 실패는 <b>Redis 연결 불가</b>
 * 이며, 2026-08-26 P6 통합 검증에서 cluster epoch 변조로 관측된 것과 같은 계열입니다.</p>
 *
 * <p>번역이 빠지면 공통 {@code CoordinationUnavailableException} 이 그대로 올라가 웹 계층이 타입을
 * 몰라 <b>500 INTERNAL_ERROR</b> 가 된다 — §13.2 는 503 COORDINATION_UNAVAILABLE 을 요구한다.
 * {@code tc-ui-core} 는 조정 라이브러리에 의존하지 않으므로(헥사고날 경계) core 코드는 그 예외를
 * 잡을 수조차 없다. 따라서 번역은 HTTP 도달 여부와 무관하게 <b>포트 경계의 계약</b>이다.</p>
 *
 * <p>제외 대상 2 종과 그 이유:</p>
 * <ul>
 *   <li>{@code CommonCoordinationDigestAdapter} — canonicalize/sha256 순수 계산이라 Redis 를 타지 않는다.</li>
 *   <li>{@code RedisUiCoordinationProbeAdapter} — readiness probe 라 {@code catch (RuntimeException)}
 *       으로 false 를 돌려주는 것이 계약이다. 던지면 readiness 가 DOWN 대신 오류가 된다.</li>
 * </ul>
 */
class UiCoordinationAdapterFailureTranslationTest {

    private static final String CLUSTER_EPOCH = "epoch-1";
    private static final String SESSION_ID = "tc-ui-backend:pod-1:sess-1";

    private CoordinationScriptExecutor executor;

    @BeforeEach
    void setUp() {
        final RedisConnectionFactory connectionFactory = mock(RedisConnectionFactory.class);
        when(connectionFactory.getConnection())
                .thenThrow(new RedisConnectionFailureException("connection refused"));
        executor = new CoordinationScriptExecutor(connectionFactory);
    }

    @Test
    @DisplayName("catalog head 어댑터는 commit/read 실패를 번역한다")
    void catalogHeadAdapterTranslates() {
        final RedisUiCatalogHeadAdapter adapter =
                new RedisUiCatalogHeadAdapter(new CatalogHeadRedisStore(executor, CLUSTER_EPOCH));

        assertThrows(UiCoordinationUnavailableException.class,
                () -> adapter.commitHead("deploy-scenario", 2L, "digest", 1L, 1L));
        assertThrows(UiCoordinationUnavailableException.class,
                () -> adapter.readHead("deploy-scenario"));
    }

    @Test
    @DisplayName("executor lease 어댑터는 acquire/renew/release 실패를 번역한다")
    void executorLeaseAdapterTranslates() {
        final RedisUiExecutorLeaseAdapter adapter =
                new RedisUiExecutorLeaseAdapter(new FencedLeaseRedisStore(executor, CLUSTER_EPOCH));
        final UiExecutorLease lease = new UiExecutorLease("op-1", SESSION_ID, 1L);

        assertThrows(UiCoordinationUnavailableException.class,
                () -> adapter.tryAcquire("op-1", SESSION_ID, 1_000L, 1L));
        assertThrows(UiCoordinationUnavailableException.class,
                () -> adapter.renew(lease, 1_000L, 1L));
        assertThrows(UiCoordinationUnavailableException.class,
                () -> adapter.release(lease, 1L));
    }

    @Test
    @DisplayName("membership 어댑터는 register/renew/조회 실패를 번역한다")
    void membershipAdapterTranslates() {
        final RedisUiMembershipAdapter adapter =
                new RedisUiMembershipAdapter(new MembershipRedisStore(executor, CLUSTER_EPOCH));

        assertThrows(UiCoordinationUnavailableException.class,
                () -> adapter.register(SESSION_ID, "pod-1", "2", UiMembershipState.JOINING, 1_000L, 1L));
        assertThrows(UiCoordinationUnavailableException.class,
                () -> adapter.renew(SESSION_ID, "2", UiMembershipState.ACTIVE_SYNCED, 0L, 1_000L, 1L));
        assertThrows(UiCoordinationUnavailableException.class,
                () -> adapter.activeSyncedSessionIds("tc-business", "2", 10L));
        assertThrows(UiCoordinationUnavailableException.class,
                () -> adapter.expired("tc-business", SESSION_ID));
    }

    @Test
    @DisplayName("deploy 실행 어댑터는 lease 획득/해제 실패를 번역한다")
    void deployExecutionAdapterTranslates() {
        final DeployExecutionRedisAdapter adapter = new DeployExecutionRedisAdapter(
                new FencedLeaseRedisStore(executor, CLUSTER_EPOCH),
                new org.springframework.data.redis.core.StringRedisTemplate());

        assertThrows(UiCoordinationUnavailableException.class,
                () -> adapter.tryAcquireTargetLease("CLN_001", "owner-1", 1_000L));
        assertThrows(UiCoordinationUnavailableException.class,
                () -> adapter.releaseTargetLease("CLN_001", "owner-1", 1L));
    }
}

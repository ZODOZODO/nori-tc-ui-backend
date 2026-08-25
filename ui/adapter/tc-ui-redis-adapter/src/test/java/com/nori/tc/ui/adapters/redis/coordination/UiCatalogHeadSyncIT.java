package com.nori.tc.ui.adapters.redis.coordination;

import com.nori.tc.common.coordination.script.CoordinationScriptExecutor;
import com.nori.tc.common.coordination.store.ClusterEpochRedisStore;
import com.nori.tc.ui.adapters.redis.support.AbstractRedisContainerTest;
import com.nori.tc.ui.core.coordination.CoordinatedUiPermissionCatalogGuard;
import com.nori.tc.ui.core.coordination.CoordinatedUiScenarioCatalogGuard;
import com.nori.tc.ui.core.coordination.UiCatalogHeadSynchronizer;
import com.nori.tc.ui.core.coordination.UiCatalogNotSyncedException;
import com.nori.tc.ui.core.coordination.UiPermissionCatalogGuard;
import com.nori.tc.ui.core.coordination.UiScenarioCatalogGuard;
import com.nori.tc.ui.core.coordination.port.UiCatalogHeadPort;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §23.8 — catalog head 정합성을 실 Redis 위에서 3 인스턴스로 검증합니다.
 *
 * <p>핵심은 "한 Pod 만 reload 했을 때 나머지가 조용히 옛 catalog 로 계속 일하지 않는다" 입니다.
 * 종전에는 reload 가 그 Pod 의 {@code AtomicReference} 만 교체했으므로, 같은 요청이 어느 Pod 에
 * 도달하느냐에 따라 다른 step 계획이 실행됐습니다.</p>
 */
class UiCatalogHeadSyncIT extends AbstractRedisContainerTest {

    private static LettuceConnectionFactory connectionFactory;

    private UiCatalogHeadPort catalogHeadPort;
    private String scenarioCatalogType;
    private String permissionCatalogType;

    @BeforeAll
    static void createConnectionFactory() {
        connectionFactory = newConnectionFactory();
    }

    @AfterAll
    static void closeConnectionFactory() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @BeforeEach
    void setUp() {
        final CoordinationScriptExecutor executor = new CoordinationScriptExecutor(connectionFactory);
        new ClusterEpochRedisStore(executor).initializeIfAbsent(UUID.randomUUID().toString());

        final UiCoordinationContext context = new UiCoordinationContext(connectionFactory);
        catalogHeadPort = new RedisUiCatalogHeadAdapter(context.catalogHeadStore());
        // reuse 컨테이너라 catalog head 도 테스트 간 남는다 — 종류 이름으로 격리한다.
        scenarioCatalogType = "scenario-" + UUID.randomUUID().toString().substring(0, 8);
        permissionCatalogType = "permission-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    @DisplayName("한 Pod 가 reload 로 head 를 commit 하면 옛 catalog Pod 의 planning 은 503 으로 거부된다")
    void headMismatchPodRejectsPlanning() {
        final UiScenarioCatalogGuard podA = scenarioGuard();
        final UiScenarioCatalogGuard podB = scenarioGuard();
        final UiScenarioCatalogGuard podC = scenarioGuard();

        // 세 Pod 가 같은 YAML 로 기동 — 첫 Pod 가 head 를 만들고 나머지는 그 revision 을 채택한다.
        podA.onCatalogApplied("checksum-v1");
        podB.onCatalogApplied("checksum-v1");
        podC.onCatalogApplied("checksum-v1");
        assertTrue(podA.inSync() && podB.inSync() && podC.inSync());
        assertEquals(podA.headRevision(), podC.headRevision());

        // Pod A 에만 새 YAML 을 배포하고 reload — validation 통과 후 head 를 올린다.
        podA.onCatalogApplied("checksum-v2");
        assertTrue(podA.inSync());

        podA.requirePlanningAllowed("checksum-v2");

        final UiCatalogNotSyncedException rejected = assertThrows(UiCatalogNotSyncedException.class,
                () -> podB.requirePlanningAllowed("checksum-v1"));
        assertEquals(UiCatalogNotSyncedException.ERROR_CODE, "CATALOG_NOT_SYNCED");
        assertTrue(rejected.getMessage().contains("checksum-v1"));
        assertFalse(podB.inSync(), "mismatch Pod 는 readiness 에서도 빠져야 합니다.");
    }

    @Test
    @DisplayName("validation 에 실패한 candidate 는 head 를 바꾸지 않아 전 Pod 가 기존 catalog 를 유지한다")
    void failedValidationDoesNotMoveTheHead() {
        final UiScenarioCatalogGuard podA = scenarioGuard();
        final UiScenarioCatalogGuard podB = scenarioGuard();
        podA.onCatalogApplied("checksum-v1");
        podB.onCatalogApplied("checksum-v1");
        final long headBefore = podA.headRevision();

        /*
         * validation 실패 경로는 onCatalogApplied 를 호출하지 않는다 — 호출하면 검증되지 않은 catalog 가
         * head 가 되어 나머지 Pod 전부가 fail-closed 된다. 여기서는 그 계약을 "호출하지 않으면 head 가
         * 그대로" 로 고정한다.
         */
        assertEquals(headBefore, podA.headRevision());
        assertTrue(podB.inSync());
        podB.requirePlanningAllowed("checksum-v1");
    }

    @Test
    @DisplayName("permission catalog 도 같은 규율로 mismatch Pod 만 fail-closed 된다")
    void permissionCatalogMismatchFailsClosedOnlyOnThatPod() {
        final UiPermissionCatalogGuard podA = permissionGuard();
        final UiPermissionCatalogGuard podB = permissionGuard();

        podA.onPermissionsApplied("EQP_READ|GET|PREFIX|/api/eqp");
        podB.onPermissionsApplied("EQP_READ|GET|PREFIX|/api/eqp");
        assertTrue(podA.inSync() && podB.inSync());

        // DB 변경을 Pod A 만 먼저 관측했다.
        podA.onPermissionsApplied("EQP_READ|GET|PREFIX|/api/eqp\nEQP_WRITE|POST|PREFIX|/api/eqp");

        assertTrue(podA.inSync());
        assertFalse(podB.inSync(), "옛 권한 목록을 든 Pod 는 인가 판단을 거부해야 합니다.");

        // Pod B 가 같은 DB 상태를 관측하면 다시 동기가 된다.
        podB.onPermissionsApplied("EQP_READ|GET|PREFIX|/api/eqp\nEQP_WRITE|POST|PREFIX|/api/eqp");
        assertTrue(podB.inSync());
    }

    @Test
    @DisplayName("아직 한 번도 적재하지 못한 Pod 는 인가 판단을 허용하지 않는다")
    void permissionGuardBeforeFirstLoadIsNotInSync() {
        assertFalse(permissionGuard().inSync());
    }

    private UiScenarioCatalogGuard scenarioGuard() {
        return new CoordinatedUiScenarioCatalogGuard(
                new UiCatalogHeadSynchronizer(catalogHeadPort, scenarioCatalogType));
    }

    private UiPermissionCatalogGuard permissionGuard() {
        return new CoordinatedUiPermissionCatalogGuard(
                new UiCatalogHeadSynchronizer(catalogHeadPort, permissionCatalogType),
                new CommonCoordinationDigestAdapter());
    }
}

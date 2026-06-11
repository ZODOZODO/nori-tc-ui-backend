package com.nori.tc.ui.adapter.db;

import com.nori.tc.db.core.eqp.store.TcEqpPlcStore;
import com.nori.tc.db.core.eqp.upsert.UpsertTcEqpPlc;
import com.nori.tc.db.domain.common.model.PlcProtocol;
import com.nori.tc.db.domain.common.model.PlcTransport;
import com.nori.tc.db.domain.plc.PlcEnabledPollGroupsCodec;
import com.nori.tc.db.jpa.common.entity.eqp.TcEqpPlcEntity;
import com.nori.tc.db.jpa.common.mapper.eqp.TcEqpPlcEntityMapper;
import com.nori.tc.db.jpa.common.mapper.eqp.TcEqpPlcEntityMapperImpl;
import com.nori.tc.db.jpa.common.repository.eqp.TcEqpPlcJpaRepository;
import com.nori.tc.db.jpa.common.store.eqp.TcEqpPlcJpaStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@link EqpPlcPollGroupAdapter} 의 <b>실 H2 + 공통 JPA store @Version 동시성</b> 통합 테스트(SPEC L1344).
 *
 * <p>같은 설비의 서로 다른 그룹을 <b>실 스레드</b>로 동시에 GROUP_START 했을 때, 어댑터의 단일 트랜잭션
 * read-modify-write + {@code OptimisticLockingFailureException} 재시도가 last-write-wins 로 인한 lost-update 를
 * 막아 모든 그룹이 {@code enabled_poll_groups} 에 남는지 검증한다. {@code @Version} 의 1차 캐시 의미는 mock 으로는
 * 재현 불가하므로 실제 Hibernate + H2 로 띄운다(common 무변경 — 이미 publish 된 좌표 소비).</p>
 */
@SpringBootTest
class EqpPlcPollGroupAdapterConcurrencyTest {

    private static final long EQP_KEY = 1L;

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = TcEqpPlcEntity.class)
    @EnableJpaRepositories(basePackageClasses = TcEqpPlcJpaRepository.class)
    static class TestApp {

        @Bean
        TcEqpPlcEntityMapper tcEqpPlcEntityMapper() {
            return new TcEqpPlcEntityMapperImpl();
        }

        @Bean
        TcEqpPlcStore tcEqpPlcStore(final TcEqpPlcJpaRepository repository, final TcEqpPlcEntityMapper mapper) {
            return new TcEqpPlcJpaStore(repository, mapper);
        }

        @Bean
        EqpPlcPollGroupAdapter eqpPlcPollGroupAdapter(
                final TcEqpPlcStore store,
                final PlatformTransactionManager transactionManager
        ) {
            return new EqpPlcPollGroupAdapter(store, transactionManager);
        }
    }

    @Autowired
    private TcEqpPlcStore plcStore;

    @Autowired
    private EqpPlcPollGroupAdapter adapter;

    @BeforeEach
    void seed() {
        plcStore.upsert(new UpsertTcEqpPlc(
                EQP_KEY, PlcProtocol.MODBUS_TCP, PlcTransport.TCP, 1, 1000, 3, "[]"));
    }

    @AfterEach
    void cleanup() {
        plcStore.deleteByEqpKey(EQP_KEY);
    }

    @Test
    void concurrentGroupStartsOfDistinctGroupsBothRemain() throws Exception {
        // 여러 라운드 동안 매번 2개의 서로 다른 그룹을 동시에 add → 누적 행에 대한 @Version 충돌이 확실히 발생.
        // 메커니즘(단일 tx + 재시도)이 깨지면 일부 그룹이 lost-update 로 사라져 assert 가 실패한다.
        final int rounds = 25;
        final List<String> expected = new ArrayList<>();
        final ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < rounds; round++) {
                final String groupA = "GROUP_A_" + round;
                final String groupB = "GROUP_B_" + round;
                expected.add(groupA);
                expected.add(groupB);

                final CountDownLatch fireTogether = new CountDownLatch(1);
                final Future<?> futureA = pool.submit(() -> {
                    awaitLatch(fireTogether);
                    adapter.addPollGroup(EQP_KEY, groupA);
                });
                final Future<?> futureB = pool.submit(() -> {
                    awaitLatch(fireTogether);
                    adapter.addPollGroup(EQP_KEY, groupB);
                });
                fireTogether.countDown();
                futureA.get(10, TimeUnit.SECONDS);
                futureB.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(currentGroups()).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    void removeIsIdempotent() {
        adapter.addPollGroup(EQP_KEY, "EQP_STATUS_REPORT");
        adapter.removePollGroup(EQP_KEY, "EQP_STATUS_REPORT");
        // 없는 그룹의 두 번째 remove 는 예외 없이 no-op (멱등).
        assertThatCode(() -> adapter.removePollGroup(EQP_KEY, "EQP_STATUS_REPORT")).doesNotThrowAnyException();
        assertThat(currentGroups()).isEmpty();
    }

    @Test
    void clearEmptiesEnabledPollGroups() {
        adapter.addPollGroup(EQP_KEY, "GROUP_A");
        adapter.addPollGroup(EQP_KEY, "GROUP_B");

        adapter.clearPollGroups(EQP_KEY);

        assertThat(currentGroups()).isEmpty();
    }

    @Test
    void clearOnEquipmentWithoutPlcRowIsNoOp() {
        // SECS/SOCKET 설비(tc_eqp_plc 행 없음)에 EQP_END clear 가 와도 예외 없이 no-op (protocol-agnostic EQP_END).
        assertThatCode(() -> adapter.clearPollGroups(987654L)).doesNotThrowAnyException();
    }

    private List<String> currentGroups() {
        return PlcEnabledPollGroupsCodec.deserialize(
                plcStore.findByEqpKey(EQP_KEY).orElseThrow().enabledPollGroups());
    }

    private static void awaitLatch(final CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}

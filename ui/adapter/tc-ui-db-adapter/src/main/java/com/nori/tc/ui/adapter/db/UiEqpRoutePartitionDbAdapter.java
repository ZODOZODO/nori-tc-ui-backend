package com.nori.tc.ui.adapter.db;

import com.nori.tc.db.core.eqp.store.TcEqpStore;
import com.nori.tc.db.domain.eqp.TcEqp;
import com.nori.tc.ui.core.port.messaging.UiGatewayEqpRoutePartitionLookupPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;

/**
 * {@link UiGatewayEqpRoutePartitionLookupPort}의 DB 기반 구현 어댑터입니다.
 *
 * <p>역할:</p>
 * <p>{@link TcEqpStore}를 통해 tc_eqp 테이블을 조회하고,
 * tc.ui.events.gateway 토픽의 고정 라우팅(U13 규칙)에 필요한
 * {@code route_partition} 값을 반환합니다.</p>
 *
 * <p>운영 관점 주의사항:</p>
 * <ul>
 *   <li>설비는 존재하지만 route_partition이 null이면 라우팅 배정이 미완료된 상태입니다.</li>
 *   <li>본 어댑터는 조회 결과만 전달하며, 발행 허용 여부 판단은
 *       {@code UiGatewayEventKafkaPublisher}에서 수행합니다.</li>
 *   <li>route_partition의 SSOT(Single Source of Truth)는 tc_eqp 테이블입니다.</li>
 * </ul>
 *
 * <p>BusinessEqpRoutePartitionDbAdapter와 동일한 패턴을 따릅니다.</p>
 */
@Component
public class UiEqpRoutePartitionDbAdapter implements UiGatewayEqpRoutePartitionLookupPort {

    private static final Logger log = LoggerFactory.getLogger(UiEqpRoutePartitionDbAdapter.class);

    private final TcEqpStore eqpStore;

    /**
     * 의존성을 초기화합니다.
     *
     * @param eqpStore tc_eqp 저장소 포트
     */
    public UiEqpRoutePartitionDbAdapter(final TcEqpStore eqpStore) {
        this.eqpStore = Objects.requireNonNull(eqpStore, "eqpStore is null");
        log.info("UiEqpRoutePartitionDbAdapter initialized. source=tc_eqp.route_partition");
    }

    /**
     * 설비 ID(eqpId)로 Gateway 고정 라우팅 partition을 조회합니다.
     *
     * <p>동작 순서:</p>
     * <ol>
     *   <li>eqpId 기본 검증 (null/blank → 빈 Optional 즉시 반환)</li>
     *   <li>tc_eqp 단건 조회</li>
     *   <li>route_partition 값 추출하여 반환 (null이면 빈 Optional)</li>
     * </ol>
     *
     * @param eqpId 설비 식별자 (비즈니스 키)
     * @return route_partition 값 (미배정 또는 설비 미존재 시 빈 Optional)
     */
    @Override
    public Optional<Integer> findRoutePartition(final String eqpId) {
        if (eqpId == null || eqpId.isBlank()) {
            log.debug("route_partition 조회 건너뜀. 사유=eqpId 비어 있음");
            return Optional.empty();
        }

        final Optional<TcEqp> found = eqpStore.findByEqpId(eqpId);
        if (found.isEmpty()) {
            log.debug("route_partition 조회 결과 없음. eqpId={}, 사유=tc_eqp 미존재", eqpId);
            return Optional.empty();
        }

        final Integer routePartition = found.get().routePartition();
        log.debug("route_partition 조회 완료. eqpId={}, routePartition={}, enabled={}",
                eqpId, routePartition, found.get().enabled());

        return Optional.ofNullable(routePartition);
    }
}

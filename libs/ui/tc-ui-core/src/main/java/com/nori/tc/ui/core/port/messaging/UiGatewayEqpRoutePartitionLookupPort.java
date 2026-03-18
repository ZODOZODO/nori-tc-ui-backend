package com.nori.tc.ui.core.port.messaging;

import java.util.Optional;

/**
 * 설비의 Gateway Kafka 라우팅 파티션 조회 포트입니다 (U13 요구사항).
 *
 * <p>역할:</p>
 * <p>tc.ui.events.gateway 토픽에 메시지를 발행할 때 eqp_id에 해당하는
 * route_partition 번호를 조회합니다. Gateway는 토픽 파티션별로 설비 연결을
 * 담당하므로, 잘못된 파티션으로 발행하면 해당 설비를 관리하는 Gateway 인스턴스에
 * 메시지가 전달되지 않습니다.</p>
 *
 * <p>U13 규칙:</p>
 * <p>tc_eqp.route_partition이 배정되지 않았거나 음수이면 메시지 발행을 차단하고
 * ERROR 로그를 남겨 운영자가 인지할 수 있도록 합니다.</p>
 *
 * <p>구현체:</p>
 * <p>tc-ui-db-adapter의 UiEqpRoutePartitionDbAdapter가 이 인터페이스를 구현합니다.</p>
 */
@FunctionalInterface
public interface UiGatewayEqpRoutePartitionLookupPort {

    /**
     * 설비 ID에 해당하는 Gateway Kafka 라우팅 파티션 번호를 조회합니다.
     *
     * <p>tc_eqp.route_partition 컬럼 값을 조회합니다.
     * 설비가 존재하지 않거나 route_partition이 null / 음수이면 빈 Optional을 반환합니다.</p>
     *
     * @param eqpId 설비 식별자
     * @return 라우팅 파티션 번호 (0 이상), 미배정이면 빈 Optional
     */
    Optional<Integer> findRoutePartition(String eqpId);

    /**
     * 테스트 또는 초기 구성에 사용할 noop(아무 동작 없음) 구현체를 반환합니다.
     *
     * @return 항상 빈 Optional을 반환하는 noop 구현체
     */
    static UiGatewayEqpRoutePartitionLookupPort noop() {
        return eqpId -> Optional.empty();
    }
}

package com.nori.tc.ui.core.coordination;

import com.nori.tc.ui.core.coordination.model.UiOperationManifestView;
import com.nori.tc.ui.core.port.messaging.UiGatewayEqpRoutePartitionLookupPort;

import java.util.Objects;

/**
 * operation 이 라우팅할 partition 과, 이동이라면 옛 partition 을 함께 정합니다(§12.5).
 *
 * <p>이 계산이 한 곳에 있어야 하는 이유: 참여자 판정과 명령 발행이 <b>같은 partition 쌍</b>을 봐야 합니다.
 * 서로 다른 규칙을 쓰면 "명령은 A Pod 에 갔는데 증적은 B Pod 에게 요구" 하는 상태가 되어 barrier 가
 * 닫히지 않고, 이동에서는 아무도 release 하지 못하는 bind 대기가 생깁니다. 실제로 두 곳이 갈려
 * EQP_ROUTE_MOVE_NOT_RELEASED 로 abort 되는 결함이 운영에서 났습니다.</p>
 *
 * @param target 목표 route partition(정할 수 없으면 null)
 * @param previous 옛 route partition(이동이 아니거나 옛 상태가 없으면 null)
 */
public record UiGatewayRoutePlan(Integer target, Integer previous) {

    /**
     * 이동 여부입니다.
     *
     * @return 옛/새 partition 이 모두 있고 서로 다르면 true
     */
    public boolean isMove() {
        return target != null && previous != null && !previous.equals(target);
    }

    /**
     * manifest 로부터 라우팅 계획을 만듭니다.
     *
     * <p>목표 partition 은 manifest descriptor 값이 우선이고, 없으면(변경 대상이 아니면 null 이다)
     * {@code tc_eqp.route_partition} 조회로 떨어집니다 — 조정 경로의 DB 확정은 commit 이후이므로
     * descriptor 가 그 operation 이 의도한 값의 유일한 출처입니다(§13.1/§13.4).</p>
     *
     * <p>옛 partition 은 이전 descriptor 에서만 읽습니다. 이전 상태가 tombstone 인 생성 operation 은
     * 옛 owner 가 <b>없는</b> 것이므로 null 로 둡니다 — 없는 값을 지어내면 Gateway 가 있지도 않은
     * 이동을 기다립니다.</p>
     *
     * @param manifest 원장에 고정된 manifest
     * @param descriptorReader descriptor 해석기
     * @param routePartitionLookupPort eqpId -&gt; route_partition 조회 포트
     * @return 라우팅 계획
     */
    public static UiGatewayRoutePlan of(
            final UiOperationManifestView manifest,
            final UiRuntimeDescriptorReader descriptorReader,
            final UiGatewayEqpRoutePartitionLookupPort routePartitionLookupPort
    ) {
        Objects.requireNonNull(manifest, "manifest is null");
        Objects.requireNonNull(descriptorReader, "descriptorReader is null");
        Objects.requireNonNull(routePartitionLookupPort, "routePartitionLookupPort is null");

        final String primaryResourceKey =
                manifest.resourceKeys().isEmpty() ? "" : manifest.resourceKeys().get(0);

        final String descriptorJson = manifest.descriptorJsonByResourceKey().get(primaryResourceKey);
        final Integer fromDescriptor = descriptorJson == null
                ? null
                : descriptorReader.read(descriptorJson).routePartition();
        final Integer target = fromDescriptor != null
                ? fromDescriptor
                : routePartitionLookupPort.findRoutePartition(manifest.eqpId()).orElse(null);

        return new UiGatewayRoutePlan(target, previousOf(manifest, descriptorReader, primaryResourceKey));
    }

    private static Integer previousOf(
            final UiOperationManifestView manifest,
            final UiRuntimeDescriptorReader descriptorReader,
            final String primaryResourceKey
    ) {
        final String previousJson = manifest.previousDescriptorJsonByResourceKey().get(primaryResourceKey);
        if (previousJson == null) {
            return null;
        }
        final UiRuntimeDescriptorReader.DescriptorFields previous = descriptorReader.read(previousJson);
        return previous.deleted() ? null : previous.routePartition();
    }
}

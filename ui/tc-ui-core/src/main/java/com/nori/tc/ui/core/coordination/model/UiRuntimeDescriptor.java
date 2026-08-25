package com.nori.tc.ui.core.coordination.model;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * UI Backend 가 생산하는 revisioned resource descriptor 입니다(§11.1 Business + §12.3 Gateway).
 *
 * <p>이 레코드가 곧 소비자 계약이다. Business 는 {@code BusinessRuntimeDescriptorCodec},
 * Gateway 는 {@code GatewayEquipmentOverlayCodec} 이 같은 문서에서 자기 필드만 읽고 모르는 필드는
 * 무시한다(additive).</p>
 *
 * <p><b>부재와 빈 값의 의미가 다르다.</b> {@code modelParams}/{@code eqpParams} 가 {@code null} 이면
 * "이번 revision 에서 미변경", 빈 map 이면 "빈 snapshot 으로 교체" 다. {@code plcEnabledPollGroups}
 * 가 {@code null} 이면 DB baseline 유지, 빈 목록이면 "전부 해제" 다. {@code gatewayDesiredState} 가
 * {@code null} 이면 이번 revision 이 연결 의도를 바꾸지 않는다.</p>
 *
 * <p>Gateway 3 필드를 비우면 overlay 가 조용히 DB baseline 으로 떨어진다 — 실패가 아니라 무동작이라
 * 테스트로도 드러나지 않으므로, 생산 지점에서 명시적으로 채운다(p2-validate §7.3).</p>
 *
 * @param eqpId 대상 설비
 * @param deleted 삭제 tombstone 여부
 * @param eqpKey 설비 PK
 * @param modelVersionKey 모델 버전 키(공유 상태의 정규화 기준)
 * @param routePartition Gateway route partition
 * @param businessJarKey Business plugin JAR key
 * @param businessJarFileName Business plugin JAR 파일명
 * @param businessJarSha256 Business plugin JAR digest
 * @param modelParams 모델 파라미터 snapshot(null = 미변경)
 * @param eqpParams 설비 파라미터 snapshot(null = 미변경)
 * @param pluginRuntimeAffected plugin runtime 재적재 필요 여부
 * @param gatewayDesiredState {@code "STARTED"} 또는 {@code "ENDED"}(null = 미변경)
 * @param plcEnabledPollGroups 활성 폴 그룹 messageName 목록(null = 미변경, 빈 목록 = 전부 해제)
 */
public record UiRuntimeDescriptor(
        String eqpId,
        boolean deleted,
        Long eqpKey,
        Long modelVersionKey,
        Integer routePartition,
        Long businessJarKey,
        String businessJarFileName,
        String businessJarSha256,
        Map<String, String> modelParams,
        Map<String, String> eqpParams,
        boolean pluginRuntimeAffected,
        String gatewayDesiredState,
        List<String> plcEnabledPollGroups
) {

    /** Business/Gateway 코덱이 요구하는 descriptor schema version 입니다. */
    public static final int SCHEMA_VERSION = 2;

    /** {@link #gatewayDesiredState} 가 가질 수 있는 값입니다. */
    public static final String GATEWAY_STATE_STARTED = "STARTED";

    /** {@link #gatewayDesiredState} 가 가질 수 있는 값입니다. */
    public static final String GATEWAY_STATE_ENDED = "ENDED";

    /**
     * 값 계약을 검증하고 불변 컬렉션으로 정규화합니다.
     */
    public UiRuntimeDescriptor {
        Objects.requireNonNull(eqpId, "eqpId is null");
        if (gatewayDesiredState != null
                && !GATEWAY_STATE_STARTED.equals(gatewayDesiredState)
                && !GATEWAY_STATE_ENDED.equals(gatewayDesiredState)) {
            throw new IllegalArgumentException(
                    "gatewayDesiredState must be STARTED or ENDED: " + gatewayDesiredState);
        }
        modelParams = modelParams == null ? null : Map.copyOf(modelParams);
        eqpParams = eqpParams == null ? null : Map.copyOf(eqpParams);
        plcEnabledPollGroups = plcEnabledPollGroups == null ? null : List.copyOf(plcEnabledPollGroups);
    }
}

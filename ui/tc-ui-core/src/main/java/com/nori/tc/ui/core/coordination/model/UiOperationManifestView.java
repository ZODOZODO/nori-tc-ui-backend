package com.nori.tc.ui.core.coordination.model;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 원장에 고정된 불변 manifest 의 해석 결과입니다(§13.1).
 *
 * <p>executor 는 로컬 요청 객체가 아니라 <b>이 뷰</b>로 실행합니다 — 다른 Pod 가 인계받아도 같은
 * 입력으로 이어가야 하고, 그 유일한 출처가 원장의 manifest 이기 때문입니다(§13.4).</p>
 *
 * @param eventType 요청 이벤트 타입
 * @param eqpId 직접 대상 설비
 * @param actor 요청자의 비민감 식별 정보
 * @param executionId 연관 실행 식별자
 * @param interfaceType 설비 인터페이스 타입
 * @param requiresGatewayParticipant Gateway owner participant 요구 여부
 * @param resourceKeys 정렬된 영향 리소스 키
 * @param descriptorJsonByResourceKey resourceKey 별 candidate descriptor JSON
 * @param expectedBaseRevisions resourceKey 별 기대 base revision
 * @param catalogRevision 고정한 catalog revision
 * @param catalogDigest 고정한 catalog digest
 * @param previousDescriptorJsonByResourceKey 변경 전 상태 descriptor JSON(§13.7/§13.8)
 * @param dbCommandKind DB finalization 명령 종류
 * @param dbCommandJson DB finalization 명령 payload JSON
 */
public record UiOperationManifestView(
        String eventType,
        String eqpId,
        String actor,
        String executionId,
        String interfaceType,
        boolean requiresGatewayParticipant,
        List<String> resourceKeys,
        Map<String, String> descriptorJsonByResourceKey,
        Map<String, Long> expectedBaseRevisions,
        Long catalogRevision,
        String catalogDigest,
        Map<String, String> previousDescriptorJsonByResourceKey,
        String dbCommandKind,
        String dbCommandJson
) {

    /**
     * 불변 컬렉션으로 정규화합니다.
     */
    public UiOperationManifestView {
        Objects.requireNonNull(eventType, "eventType is null");
        Objects.requireNonNull(eqpId, "eqpId is null");
        resourceKeys = resourceKeys == null ? List.of() : List.copyOf(resourceKeys);
        descriptorJsonByResourceKey =
                descriptorJsonByResourceKey == null ? Map.of() : Map.copyOf(descriptorJsonByResourceKey);
        expectedBaseRevisions = expectedBaseRevisions == null ? Map.of() : Map.copyOf(expectedBaseRevisions);
        previousDescriptorJsonByResourceKey = previousDescriptorJsonByResourceKey == null
                ? Map.of()
                : Map.copyOf(previousDescriptorJsonByResourceKey);
    }
}

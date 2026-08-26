package com.nori.tc.ui.core.coordination.model;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 조정 원장에 접수할 mutation 요청입니다(§13.1/§13.2).
 *
 * <p>{@code descriptors} 는 {@code resourceKeys} 전부를 덮어야 합니다 — 영향 집합에 넣어 놓고
 * descriptor 를 주지 않으면 그 설비의 participant 가 무엇을 준비해야 할지 알 수 없습니다.</p>
 *
 * @param idempotencyKey Front 가 요청마다 보내는 UUID
 * @param eventType 요청 이벤트 타입
 * @param eqpId 직접 대상 설비
 * @param actor 요청자의 비민감 식별 정보
 * @param executionId 연관 실행 식별자(없으면 null)
 * @param resourceKeys lexicographic 정렬된 영향 리소스 키
 * @param descriptors resourceKey 별 candidate descriptor
 * @param expectedBaseRevisions resourceKey 별 기대 base revision
 * @param catalogRevision 고정한 scenario catalog revision(없으면 null)
 * @param catalogDigest 고정한 scenario catalog digest(없으면 null)
 * @param requiresGatewayParticipant Gateway owner 를 participant 로 요구하는지 여부
 * @param interfaceType 명령 발행에 필요한 설비 인터페이스 타입
 * @param previousDescriptors 변경 전 상태 descriptor(§13.7 expected old 검증 / §13.8 보상 원본)
 * @param dbCommandKind DB finalization 이 수행할 명령 종류({@code CREATE}/{@code UPDATE}/{@code DELETE})
 * @param dbCommandJson DB finalization 이 사용할 명령 payload JSON(비민감 값만)
 */
public record UiOperationRequest(
        String idempotencyKey,
        String eventType,
        String eqpId,
        String actor,
        String executionId,
        List<String> resourceKeys,
        Map<String, UiRuntimeDescriptor> descriptors,
        Map<String, Long> expectedBaseRevisions,
        Long catalogRevision,
        String catalogDigest,
        boolean requiresGatewayParticipant,
        String interfaceType,
        Map<String, UiRuntimeDescriptor> previousDescriptors,
        String dbCommandKind,
        String dbCommandJson
) {

    /**
     * 계약 불변식을 검증하고 불변 컬렉션으로 정규화합니다.
     */
    public UiOperationRequest {
        requireText("idempotencyKey", idempotencyKey);
        requireText("eventType", eventType);
        requireText("eqpId", eqpId);
        Objects.requireNonNull(resourceKeys, "resourceKeys is null");
        Objects.requireNonNull(descriptors, "descriptors is null");
        Objects.requireNonNull(expectedBaseRevisions, "expectedBaseRevisions is null");
        if (resourceKeys.isEmpty()) {
            throw new IllegalArgumentException("resourceKeys is empty");
        }
        for (final String resourceKey : resourceKeys) {
            if (!descriptors.containsKey(resourceKey)) {
                throw new IllegalArgumentException("descriptor is missing for resourceKey: " + resourceKey);
            }
            if (!expectedBaseRevisions.containsKey(resourceKey)) {
                throw new IllegalArgumentException("expectedBaseRevision is missing for resourceKey: " + resourceKey);
            }
        }
        resourceKeys = List.copyOf(resourceKeys);
        descriptors = Map.copyOf(descriptors);
        expectedBaseRevisions = Map.copyOf(expectedBaseRevisions);
        previousDescriptors = previousDescriptors == null ? Map.of() : Map.copyOf(previousDescriptors);
    }

    /** DB finalization 이 설비를 새로 만드는 명령입니다. */
    public static final String DB_COMMAND_CREATE = "CREATE";

    /** DB finalization 이 설비를 수정하는 명령입니다. */
    public static final String DB_COMMAND_UPDATE = "UPDATE";

    /** DB finalization 이 설비를 삭제하는 명령입니다. */
    public static final String DB_COMMAND_DELETE = "DELETE";

    /**
     * DB finalization 이 deploy target 의 배포 결과를 확정하는 명령입니다(§13.6).
     *
     * <p>설비 row 를 만들거나 지우지 않고 배포된 버전(model/param/jar)과 target 완료 표시만
     * 확정합니다. 한 operation 이 영향 폐쇄 그룹에 속한 여러 target 을 함께 확정합니다.</p>
     */
    public static final String DB_COMMAND_DEPLOY_TARGET = "DEPLOY_TARGET";

    private static void requireText(final String fieldName, final String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
    }
}

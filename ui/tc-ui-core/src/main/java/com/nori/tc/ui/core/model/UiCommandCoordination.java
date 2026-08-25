package com.nori.tc.ui.core.model;

import java.util.List;
import java.util.Objects;

/**
 * UI 명령에 실리는 coordination metadata v2 입니다(§10.1).
 *
 * <p>이 값이 없으면 수신 서비스는 v1 명령으로 취급하고 조정 원장을 사용하지 않습니다. 조정 구성에서
 * 이 값을 비워 보내면 participant 가 자기 proof 를 어느 operation 에 남겨야 하는지 알 수 없습니다.</p>
 *
 * @param operationId operation 식별자
 * @param executionId 연관 실행 식별자(없으면 null)
 * @param phase 이 명령이 요구하는 protocol phase
 * @param expectedBaseRevision 기대 base revision
 * @param desiredRevision 발급된 desired revision
 * @param payloadSha256 canonical manifest digest
 * @param resourceKeys 정렬된 영향 리소스 키
 * @param deadlineAt 관찰 deadline(ISO-8601)
 */
public record UiCommandCoordination(
        String operationId,
        String executionId,
        String phase,
        Long expectedBaseRevision,
        Long desiredRevision,
        String payloadSha256,
        List<String> resourceKeys,
        String deadlineAt
) {

    /** prepare 단계 명령임을 나타내는 phase 값입니다. */
    public static final String PHASE_PREPARE = "PREPARE";

    /** apply 단계 명령임을 나타내는 phase 값입니다. */
    public static final String PHASE_APPLY = "APPLY";

    /**
     * 필수 값을 검증하고 불변 목록으로 정규화합니다.
     */
    public UiCommandCoordination {
        Objects.requireNonNull(operationId, "operationId is null");
        Objects.requireNonNull(phase, "phase is null");
        Objects.requireNonNull(payloadSha256, "payloadSha256 is null");
        Objects.requireNonNull(desiredRevision, "desiredRevision is null");
        resourceKeys = resourceKeys == null ? List.of() : List.copyOf(resourceKeys);
    }
}

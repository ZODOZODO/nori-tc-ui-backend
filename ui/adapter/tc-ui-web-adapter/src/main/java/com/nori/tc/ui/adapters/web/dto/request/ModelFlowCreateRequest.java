package com.nori.tc.ui.adapters.web.dto.request;

/**
 * Message Flow 신규 생성 요청 DTO 입니다.
 *
 * <p>POST {@code /api/model/{modelVersionKey}/flows} 의 본문 형식입니다.
 * Identity 섹션만 채운 신규 Workflow row 1 개를 생성합니다 — Trigger / Action /
 * Collect 섹션은 후속 PUT 으로 갱신합니다.</p>
 *
 * <p>blank / null 검증은 {@link com.nori.tc.ui.core.service.ModelFlowCommandService}
 * 가 담당합니다 — 단일 검증 위치 원칙 유지.</p>
 *
 * @param workflowName Identity 섹션 — Workflow 명
 * @param messageName  Identity 섹션 — Message 명
 */
public record ModelFlowCreateRequest(
        String workflowName,
        String messageName
) {
}

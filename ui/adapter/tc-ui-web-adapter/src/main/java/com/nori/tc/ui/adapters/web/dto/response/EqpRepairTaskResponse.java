package com.nori.tc.ui.adapters.web.dto.response;

import com.nori.tc.ui.core.eqp.EqpRepairTask;

import java.time.OffsetDateTime;

/**
 * EQP 보상 실패 후 누적된 repair task GET 응답 DTO 입니다.
 *
 * <p>{@code GET /api/dlq/repair-task} 응답에 사용됩니다.
 * 도메인 {@link EqpRepairTask} 의 7 필드를 그대로 HTTP 표현으로 평면화하며,
 * enum {@code EqpRepairAction} 은 {@code name()} 문자열로 직렬화하여
 * 프론트에서 안정적으로 라벨 매핑하도록 합니다.</p>
 *
 * @param taskId       repair task 안정 식별자 (eqpId:commandType:repairAction)
 * @param eqpId        대상 설비 ID
 * @param commandType  실패한 커맨드 종류
 * @param repairAction 운영자가 수행해야 할 복구 액션 (enum name)
 * @param errorCode    실패 코드 (nullable)
 * @param errorMessage 실패 메시지 (nullable)
 * @param createdAt    task 기록 시각 (UTC)
 */
public record EqpRepairTaskResponse(
        String taskId,
        String eqpId,
        String commandType,
        String repairAction,
        String errorCode,
        String errorMessage,
        OffsetDateTime createdAt
) {

    /**
     * 도메인 {@link EqpRepairTask} 를 HTTP 응답 DTO 로 매핑합니다.
     *
     * @param task repair task 도메인 객체
     * @return 평면화된 응답 DTO
     */
    public static EqpRepairTaskResponse from(final EqpRepairTask task) {
        return new EqpRepairTaskResponse(
                task.taskId(),
                task.eqpId(),
                task.commandType(),
                task.repairAction().name(),
                task.errorCode(),
                task.errorMessage(),
                task.createdAt()
        );
    }
}

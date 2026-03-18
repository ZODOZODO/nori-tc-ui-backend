package com.nori.tc.ui.core.registry;

import com.nori.tc.ui.domain.task.UiTaskResult;

import java.util.Optional;

/**
 * eqp_create / eqp_update / eqp_delete 작업의 최종 판정 결과입니다.
 *
 * <p>역할:</p>
 * <p>DualResponseRegistry가 Gateway와 Business Core 양쪽의 응답을 모두 수집한 후
 * 최종 성공/실패를 판정한 결과를 담습니다.
 * EqpController의 DeferredResult가 이 객체를 받아 HTTP 응답으로 변환합니다.</p>
 *
 * <p>판정 규칙:</p>
 * <ul>
 *   <li>gatewayResult PASS + businessResult PASS → success = true</li>
 *   <li>어느 한쪽이라도 FAIL → success = false (부분 성공 포함)</li>
 * </ul>
 *
 * @param traceId        작업 추적 ID (ULID)
 * @param success        최종 성공 여부 (양쪽 모두 PASS인 경우만 true)
 * @param gatewayResult  Gateway 응답 결과 (TC-COMM-GATEWAY source)
 * @param businessResult Business Core 응답 결과 (TC-BUSINESS-CORE source)
 */
public record UiDualTaskFinalResult(
        String traceId,
        boolean success,
        UiTaskResult gatewayResult,
        UiTaskResult businessResult
) {

    /**
     * 부분 실패 여부를 확인합니다.
     *
     * <p>한쪽은 PASS, 다른 쪽은 FAIL인 경우입니다.
     * 운영자가 부분 실패를 인지하고 보정 작업을 수행해야 할 수 있습니다.</p>
     *
     * @return 한쪽만 FAIL이면 true
     */
    public boolean hasPartialFailure() {
        return gatewayResult.isSuccess() != businessResult.isSuccess();
    }

    /**
     * 첫 번째 실패 결과를 반환합니다.
     *
     * <p>오류 코드와 메시지를 HTTP 응답에 포함할 때 사용합니다.
     * Gateway FAIL이 있으면 Gateway 결과를 우선 반환합니다.</p>
     *
     * @return 실패한 결과 (없으면 빈 Optional - 모두 성공)
     */
    public Optional<UiTaskResult> firstFailedResult() {
        if (gatewayResult.isFailed()) {
            return Optional.of(gatewayResult);
        }
        if (businessResult.isFailed()) {
            return Optional.of(businessResult);
        }
        return Optional.empty();
    }
}

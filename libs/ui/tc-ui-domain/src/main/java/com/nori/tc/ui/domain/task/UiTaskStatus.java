package com.nori.tc.ui.domain.task;

/**
 * UI 비동기 작업(eqp_create/update/delete/start/end)의 처리 결과 상태입니다.
 *
 * <p>사용 흐름:</p>
 * <ul>
 *   <li>tc.ui.commands 수신 시 {@code KafkaUiTaskReplyData.status} 문자열을 이 enum으로 변환합니다.</li>
 *   <li>DualResponseRegistry에서 gateway/business 양측 결과를 취합할 때 사용합니다.</li>
 *   <li>AsyncResultStorePort가 Redis에 저장하는 eqp_start/end reply 결과에 포함됩니다.</li>
 * </ul>
 *
 * <p>최종 판정 규칙 (eqp_create/update/delete):</p>
 * <ul>
 *   <li>gateway PASS + business PASS → 최종 PASS</li>
 *   <li>어느 한쪽이라도 FAIL → 최종 FAIL (부분 성공 로그 기록)</li>
 * </ul>
 */
public enum UiTaskStatus {

    /**
     * 작업이 정상 완료되었습니다.
     */
    PASS,

    /**
     * 작업 처리 중 오류가 발생했습니다.
     * errorCode, errorMsg에 원인이 기록됩니다.
     */
    FAIL
}

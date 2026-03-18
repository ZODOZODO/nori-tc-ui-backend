package com.nori.tc.ui.domain.task;

import java.util.Objects;

/**
 * UI 비동기 작업 한 건의 처리 결과입니다.
 *
 * <p>역할:</p>
 * <p>tc.ui.commands 토픽에서 수신한 gateway/business 응답을 도메인 객체로 표현합니다.
 * DualResponseRegistry(eqp_create/update/delete)와 AsyncResultStore(eqp_start/end)
 * 양쪽에서 공통으로 사용하는 결과 컨테이너입니다.</p>
 *
 * <p>source 필드:</p>
 * <p>{@code KafkaUiTaskReplyMessage.metadata.source} 값을 그대로 보관합니다.
 * DualResponseRegistry에서 gateway 응답과 business 응답을 구분하는 데 사용합니다.</p>
 * <ul>
 *   <li>{@code TC-COMM-GATEWAY}: gateway가 발행한 응답</li>
 *   <li>{@code TC-BUSINESS-CORE}: business가 발행한 응답</li>
 * </ul>
 *
 * @param traceId   작업 추적 ID (ULID, UI Backend가 작업 요청 시 발급)
 * @param source    응답 출처 ({@code TC-COMM-GATEWAY} 또는 {@code TC-BUSINESS-CORE})
 * @param status    처리 결과 상태 ({@link UiTaskStatus#PASS} 또는 {@link UiTaskStatus#FAIL})
 * @param errorCode 오류 코드 (status=FAIL일 때 원인 식별용, nullable)
 * @param errorMsg  오류 메시지 (status=FAIL일 때 상세 설명, nullable)
 */
public record UiTaskResult(
        String traceId,
        String source,
        UiTaskStatus status,
        String errorCode,
        String errorMsg
) {

    /**
     * 생성 시 필수 필드를 검증합니다.
     *
     * <p>errorCode, errorMsg는 FAIL 시에만 의미가 있으나 도메인 계층에서 강제하지 않습니다.
     * 상위 계층(어댑터/서비스)에서 FAIL 시 errorCode를 포함하도록 처리하십시오.</p>
     */
    public UiTaskResult {
        if (traceId == null || traceId.isBlank()) {
            throw new IllegalArgumentException("traceId는 필수입니다.");
        }
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("source는 필수입니다.");
        }
        Objects.requireNonNull(status, "status는 null일 수 없습니다.");
    }

    // ---------------------------------------------------------------------------
    // 팩토리 메서드
    // ---------------------------------------------------------------------------

    /**
     * 성공(PASS) 결과를 생성합니다.
     *
     * @param traceId 작업 추적 ID
     * @param source  응답 출처
     * @return PASS 상태의 UiTaskResult
     */
    public static UiTaskResult pass(final String traceId, final String source) {
        return new UiTaskResult(traceId, source, UiTaskStatus.PASS, null, null);
    }

    /**
     * 실패(FAIL) 결과를 생성합니다.
     *
     * @param traceId   작업 추적 ID
     * @param source    응답 출처
     * @param errorCode 오류 코드
     * @param errorMsg  오류 메시지
     * @return FAIL 상태의 UiTaskResult
     */
    public static UiTaskResult fail(
            final String traceId,
            final String source,
            final String errorCode,
            final String errorMsg
    ) {
        return new UiTaskResult(traceId, source, UiTaskStatus.FAIL, errorCode, errorMsg);
    }

    // ---------------------------------------------------------------------------
    // 헬퍼 메서드
    // ---------------------------------------------------------------------------

    /**
     * 작업이 성공적으로 완료되었는지 확인합니다.
     *
     * @return status == PASS이면 true
     */
    public boolean isSuccess() {
        return status == UiTaskStatus.PASS;
    }

    /**
     * 작업이 실패했는지 확인합니다.
     *
     * @return status == FAIL이면 true
     */
    public boolean isFailed() {
        return status == UiTaskStatus.FAIL;
    }
}

package com.nori.tc.ui.adapters.web.dto.response;

/**
 * REST API 공통 응답 래퍼입니다.
 *
 * <p>모든 REST API 응답을 {@code success / data / errorCode / errorMsg} 형태로 일관되게 감쌉니다.
 * 성공 시 {@code data}에 결과를 담고 {@code errorCode / errorMsg}는 null,
 * 실패 시 {@code data}는 null이고 {@code errorCode / errorMsg}에 오류 원인을 담습니다.</p>
 *
 * @param <T>       응답 데이터 타입
 * @param success   처리 성공 여부
 * @param data      성공 시 반환 데이터 (실패 시 null)
 * @param errorCode 오류 코드 (성공 시 null)
 * @param errorMsg  오류 메시지 (성공 시 null)
 */
public record ApiResponse<T>(
        boolean success,
        T data,
        String errorCode,
        String errorMsg
) {

    /**
     * 성공 응답을 생성합니다.
     *
     * @param data  응답 데이터
     * @param <T>   데이터 타입
     * @return success=true, data=data, errorCode/errorMsg=null인 응답
     */
    public static <T> ApiResponse<T> success(final T data) {
        return new ApiResponse<>(true, data, null, null);
    }

    /**
     * 오류 응답을 생성합니다.
     *
     * <p>타입 파라미터 T는 호출 컨텍스트에서 추론됩니다.
     * data는 항상 null이지만, 반환 타입을 호출부 제네릭과 일치시킵니다.</p>
     *
     * @param errorCode 오류 코드 (예: "AUTH_FAILED", "TIMEOUT", "PUBLISH_FAILED")
     * @param errorMsg  사용자 친화적 오류 메시지
     * @param <T>       데이터 타입 (호출부 컨텍스트에서 추론)
     * @return success=false, data=null, errorCode/errorMsg 포함인 응답
     */
    public static <T> ApiResponse<T> error(final String errorCode, final String errorMsg) {
        return new ApiResponse<>(false, null, errorCode, errorMsg);
    }
}

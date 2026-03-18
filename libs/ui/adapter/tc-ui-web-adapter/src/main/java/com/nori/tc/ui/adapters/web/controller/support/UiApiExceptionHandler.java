package com.nori.tc.ui.adapters.web.controller.support;

import com.nori.tc.ui.adapters.web.dto.response.ApiResponse;
import com.nori.tc.ui.core.exception.UiBadRequestException;
import com.nori.tc.ui.core.exception.UiConflictException;
import com.nori.tc.ui.core.exception.UiNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * UI Web Adapter 공통 예외 응답 핸들러입니다.
 *
 * <p>역할:</p>
 * <ul>
 *   <li>Controller/Port 계층에서 발생한 예외를 HTTP 의미(400/409/500)에 맞게 변환합니다.</li>
 *   <li>응답 본문을 {@link ApiResponse} 형태로 통일하여 클라이언트 처리 일관성을 보장합니다.</li>
 * </ul>
 *
 * <p>핵심 매핑 정책:</p>
 * <ul>
 *   <li>{@link UiBadRequestException} → 400 INVALID_REQUEST</li>
 *   <li>{@link UiNotFoundException} → 404 NOT_FOUND</li>
 *   <li>{@link UiConflictException} → 409 CONFLICT</li>
 *   <li>검증/역직렬화 실패 → 400 INVALID_REQUEST</li>
 *   <li>그 외 예외 → 500 INTERNAL_ERROR</li>
 * </ul>
 */
@RestControllerAdvice
public class UiApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(UiApiExceptionHandler.class);

    /**
     * 입력값 정책 위반 예외를 400으로 변환합니다.
     *
     * @param exception 변환 대상 예외
     * @param request   현재 HTTP 요청
     * @return 400 응답
     */
    @ExceptionHandler(UiBadRequestException.class)
    public ResponseEntity<ApiResponse<Void>> handleUiBadRequest(
            final UiBadRequestException exception,
            final HttpServletRequest request
    ) {
        log.warn("요청 입력 오류. method={}, uri={}, message={}",
                resolveMethod(request), resolveRequestUri(request), exception.getMessage());
        return ResponseEntity.badRequest()
                .body(ApiResponse.error("INVALID_REQUEST", exception.getMessage()));
    }

    /**
     * 충돌 예외를 409로 변환합니다.
     *
     * @param exception 변환 대상 예외
     * @param request   현재 HTTP 요청
     * @return 409 응답
     */
    @ExceptionHandler(UiConflictException.class)
    public ResponseEntity<ApiResponse<Void>> handleUiConflict(
            final UiConflictException exception,
            final HttpServletRequest request
    ) {
        log.warn("요청 충돌. method={}, uri={}, message={}",
                resolveMethod(request), resolveRequestUri(request), exception.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error("CONFLICT", exception.getMessage()));
    }

    /**
     * 미존재 예외를 404로 변환합니다.
     *
     * @param exception 변환 대상 예외
     * @param request 현재 HTTP 요청
     * @return 404 응답
     */
    @ExceptionHandler(UiNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleUiNotFound(
            final UiNotFoundException exception,
            final HttpServletRequest request
    ) {
        log.warn("요청 대상 없음. method={}, uri={}, message={}",
                resolveMethod(request), resolveRequestUri(request), exception.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error("NOT_FOUND", exception.getMessage()));
    }

    /**
     * Bean Validation 실패를 400으로 변환합니다.
     *
     * @param exception 변환 대상 예외
     * @param request   현재 HTTP 요청
     * @return 400 응답
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodArgumentNotValid(
            final MethodArgumentNotValidException exception,
            final HttpServletRequest request
    ) {
        final String message = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fieldError -> fieldError.getDefaultMessage())
                .orElse("요청 본문 검증에 실패했습니다.");

        log.warn("요청 본문 검증 실패. method={}, uri={}, message={}",
                resolveMethod(request), resolveRequestUri(request), message);
        return ResponseEntity.badRequest()
                .body(ApiResponse.error("INVALID_REQUEST", message));
    }

    /**
     * 메서드 파라미터 제약 위반을 400으로 변환합니다.
     *
     * @param exception 변환 대상 예외
     * @param request   현재 HTTP 요청
     * @return 400 응답
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(
            final ConstraintViolationException exception,
            final HttpServletRequest request
    ) {
        final String message = exception.getConstraintViolations().stream()
                .findFirst()
                .map(violation -> violation.getMessage())
                .orElse("요청 파라미터 검증에 실패했습니다.");

        log.warn("요청 파라미터 검증 실패. method={}, uri={}, message={}",
                resolveMethod(request), resolveRequestUri(request), message);
        return ResponseEntity.badRequest()
                .body(ApiResponse.error("INVALID_REQUEST", message));
    }

    /**
     * 경로 변수/쿼리 파라미터 타입 변환 실패를 400으로 변환합니다.
     *
     * @param exception 변환 대상 예외
     * @param request   현재 HTTP 요청
     * @return 400 응답
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(
            final MethodArgumentTypeMismatchException exception,
            final HttpServletRequest request
    ) {
        final String message = exception.getName() + " 값의 형식이 올바르지 않습니다.";
        log.warn("요청 파라미터 타입 변환 실패. method={}, uri={}, parameter={}, value={}",
                resolveMethod(request), resolveRequestUri(request), exception.getName(), exception.getValue());
        return ResponseEntity.badRequest()
                .body(ApiResponse.error("INVALID_REQUEST", message));
    }

    /**
     * JSON 역직렬화 실패를 400으로 변환합니다.
     *
     * @param exception 변환 대상 예외
     * @param request   현재 HTTP 요청
     * @return 400 응답
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleMessageNotReadable(
            final HttpMessageNotReadableException exception,
            final HttpServletRequest request
    ) {
        log.warn("요청 본문 역직렬화 실패. method={}, uri={}, reason={}",
                resolveMethod(request), resolveRequestUri(request), exception.getMessage());
        return ResponseEntity.badRequest()
                .body(ApiResponse.error("INVALID_REQUEST", "요청 본문 형식이 올바르지 않습니다."));
    }

    /**
     * 분류되지 않은 예외를 500으로 변환합니다.
     *
     * @param exception 변환 대상 예외
     * @param request   현재 HTTP 요청
     * @return 500 응답
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnhandledException(
            final Exception exception,
            final HttpServletRequest request
    ) {
        log.error("처리되지 않은 예외 발생. method={}, uri={}",
                resolveMethod(request), resolveRequestUri(request), exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("INTERNAL_ERROR", "요청 처리 중 내부 오류가 발생했습니다."));
    }

    /**
     * 요청 URI를 null-safe하게 반환합니다.
     *
     * @param request 현재 요청
     * @return URI 문자열
     */
    private static String resolveRequestUri(final HttpServletRequest request) {
        if (request == null || request.getRequestURI() == null || request.getRequestURI().isBlank()) {
            return "N/A";
        }
        return request.getRequestURI();
    }

    /**
     * 요청 메서드를 null-safe하게 반환합니다.
     *
     * @param request 현재 요청
     * @return 메서드 문자열
     */
    private static String resolveMethod(final HttpServletRequest request) {
        if (request == null || request.getMethod() == null || request.getMethod().isBlank()) {
            return "N/A";
        }
        return request.getMethod();
    }
}

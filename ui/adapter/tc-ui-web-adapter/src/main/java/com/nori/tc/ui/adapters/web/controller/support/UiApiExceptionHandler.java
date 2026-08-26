package com.nori.tc.ui.adapters.web.controller.support;

import com.nori.tc.ui.adapters.web.dto.response.ApiResponse;
import com.nori.tc.ui.core.coordination.UiCatalogNotSyncedException;
import com.nori.tc.ui.core.coordination.UiCoordinationUnavailableException;
import com.nori.tc.ui.core.coordination.UiOperationConflictException;
import com.nori.tc.ui.core.exception.UiBadRequestException;
import com.nori.tc.ui.core.exception.UiConflictException;
import com.nori.tc.ui.core.exception.UiErrorCode;
import com.nori.tc.ui.core.exception.UiForbiddenException;
import com.nori.tc.ui.core.exception.UiNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.io.IOException;
import java.util.Objects;

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
 *   <li>Spring MVC 표준 예외 → 상위 클래스가 정한 상태 코드(404/405/415/503 등)</li>
 *   <li>그 외 예외 → 500 INTERNAL_ERROR</li>
 * </ul>
 *
 * <p>{@link ResponseEntityExceptionHandler} 를 상속하는 이유: {@code @ExceptionHandler(Exception.class)}
 * catch-all 은 {@code ExceptionHandlerExceptionResolver} 단계에서 모든 예외를 먼저 잡아 Spring 기본
 * 상태 코드 매핑을 아예 실행시키지 않는다. 상속 전에는 404/405/415/400 이어야 할 응답이 전부
 * 500 + ERROR 로그로 나갔다 — 오타 URL 하나가 서버 장애로 계측됐다(2026-08-10 실측).
 * 상위 클래스가 표준 20종을 선점하고 catch-all 은 그 밖만 담당한다.</p>
 *
 * <p>응답 본문은 {@link #createResponseEntity} 한 곳에서 {@link ApiResponse} 로 통일한다 —
 * 상위 클래스 기본값인 {@code ProblemDetail} 이 그대로 나가면 클라이언트 계약이 깨진다.</p>
 */
@RestControllerAdvice
public class UiApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(UiApiExceptionHandler.class);

    /**
     * 응답 메시지 i18n 해석기입니다. 요청 Locale(Accept-Language) 기준으로 ko/en 메시지를 선택합니다.
     */
    private final MessageSource messageSource;

    /**
     * 의존성을 주입합니다.
     *
     * @param messageSource 메시지 i18n 해석기
     */
    public UiApiExceptionHandler(final MessageSource messageSource) {
        this.messageSource = Objects.requireNonNull(messageSource, "messageSource is null");
    }

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
                .body(ApiResponse.error(UiErrorCode.INVALID_REQUEST, exception.getMessage()));
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
                .body(ApiResponse.error(exception.errorCode(), exception.getMessage()));
    }

    /**
     * 조정 원장 충돌을 409로 변환합니다(§13.2/§18.1).
     *
     * <p>기존 operation/result 는 바뀌지 않았음을 뜻하므로, Front 는 같은 요청을 다른 key 로 다시
     * 보내거나 진행 중 작업이 끝나기를 기다려야 합니다.</p>
     *
     * @param exception 충돌 예외
     * @param request 요청
     * @return 409 응답
     */
    @ExceptionHandler(UiOperationConflictException.class)
    public ResponseEntity<ApiResponse<Void>> handleOperationConflict(
            final UiOperationConflictException exception,
            final HttpServletRequest request
    ) {
        log.warn("operation 충돌. method={}, uri={}, errorCode={}, conflictingOperationId={}, message={}",
                resolveMethod(request), resolveRequestUri(request), exception.errorCode(),
                exception.conflictingOperationId(), exception.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(exception.errorCode(), exception.getMessage()));
    }

    /**
     * 조정 원장 접근 불가를 503으로 변환합니다(§13.2).
     *
     * <p>404 로 환원하지 않습니다 — Front 가 "작업이 사라졌다" 로 읽고 재요청하면 중복 mutation 이
     * 생깁니다.</p>
     *
     * <p>이 모듈은 조정 라이브러리에 의존하지 않으므로(헥사고날 경계) 공통 조정 예외를 여기서 직접
     * 받을 수 없습니다. 번역은 store 를 감싸는 redis 어댑터가 수행합니다
     * ({@code UiCoordinationFailures}) — 번역이 빠지면 cluster epoch 불일치가 500 으로 나갑니다.</p>
     *
     * @param exception 조정 불가 예외
     * @param request 요청
     * @return 503 응답
     */
    @ExceptionHandler(UiCoordinationUnavailableException.class)
    public ResponseEntity<ApiResponse<Void>> handleCoordinationUnavailable(
            final UiCoordinationUnavailableException exception,
            final HttpServletRequest request
    ) {
        log.error("조정 원장 접근 불가. method={}, uri={}, message={}",
                resolveMethod(request), resolveRequestUri(request), exception.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponse.error(UiCoordinationUnavailableException.ERROR_CODE, exception.getMessage()));
    }

    /**
     * catalog head mismatch 를 503으로 변환합니다(§13.9/§13.10).
     *
     * @param exception catalog mismatch 예외
     * @param request 요청
     * @return 503 응답
     */
    @ExceptionHandler(UiCatalogNotSyncedException.class)
    public ResponseEntity<ApiResponse<Void>> handleCatalogNotSynced(
            final UiCatalogNotSyncedException exception,
            final HttpServletRequest request
    ) {
        log.error("catalog head mismatch. method={}, uri={}, catalogType={}, message={}",
                resolveMethod(request), resolveRequestUri(request), exception.catalogType(), exception.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponse.error(UiCatalogNotSyncedException.ERROR_CODE, exception.getMessage()));
    }

    /**
     * 대상별 인가 거부를 403으로 변환합니다.
     *
     * @param exception 변환 대상 예외
     * @param request 현재 HTTP 요청
     * @return 403 응답
     */
    @ExceptionHandler(UiForbiddenException.class)
    public ResponseEntity<ApiResponse<Void>> handleUiForbidden(
            final UiForbiddenException exception,
            final HttpServletRequest request
    ) {
        log.warn("인가 거부. method={}, uri={}, message={}",
                resolveMethod(request), resolveRequestUri(request), exception.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error(UiErrorCode.FORBIDDEN, exception.getMessage()));
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
                .body(ApiResponse.error(UiErrorCode.NOT_FOUND, exception.getMessage()));
    }

    /**
     * Bean Validation 실패를 400으로 변환합니다.
     *
     * <p>상위 클래스가 이 예외를 이미 매핑하므로 {@code @ExceptionHandler} 로 중복 선언하면
     * 기동 시 {@code Ambiguous @ExceptionHandler} 로 실패한다 — 반드시 override 여야 한다.</p>
     *
     * @param exception  변환 대상 예외
     * @param headers    상위 클래스가 준비한 응답 헤더
     * @param statusCode 상위 클래스가 정한 상태 코드
     * @param request    현재 요청
     * @return 400 응답
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            final MethodArgumentNotValidException exception,
            final HttpHeaders headers,
            final HttpStatusCode statusCode,
            final WebRequest request
    ) {
        final String message = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fieldError -> fieldError.getDefaultMessage())
                .orElseGet(() -> resolve("error.request.body.invalid"));

        final HttpServletRequest servletRequest = servletRequestOf(request);
        log.warn("요청 본문 검증 실패. method={}, uri={}, message={}",
                resolveMethod(servletRequest), resolveRequestUri(servletRequest), message);
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(UiErrorCode.INVALID_REQUEST, message));
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
                .orElseGet(() -> resolve("error.request.param.invalid"));

        log.warn("요청 파라미터 검증 실패. method={}, uri={}, message={}",
                resolveMethod(request), resolveRequestUri(request), message);
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(UiErrorCode.INVALID_REQUEST, message));
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
        final String message = resolve("error.request.type.mismatch", exception.getName());
        log.warn("요청 파라미터 타입 변환 실패. method={}, uri={}, parameter={}, value={}",
                resolveMethod(request), resolveRequestUri(request), exception.getName(), exception.getValue());
        return ResponseEntity.badRequest()
                .body(ApiResponse.error("INVALID_REQUEST", message));
    }

    /**
     * JSON 역직렬화 실패를 400으로 변환합니다.
     *
     * <p>{@link #handleMethodArgumentNotValid} 와 같은 이유로 override 여야 한다.</p>
     *
     * @param exception  변환 대상 예외
     * @param headers    상위 클래스가 준비한 응답 헤더
     * @param statusCode 상위 클래스가 정한 상태 코드
     * @param request    현재 요청
     * @return 400 응답
     */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            final HttpMessageNotReadableException exception,
            final HttpHeaders headers,
            final HttpStatusCode statusCode,
            final WebRequest request
    ) {
        final HttpServletRequest servletRequest = servletRequestOf(request);
        log.warn("요청 본문 역직렬화 실패. method={}, uri={}, reason={}",
                resolveMethod(servletRequest), resolveRequestUri(servletRequest), exception.getMessage());
        return ResponseEntity.badRequest()
                .body(ApiResponse.error("INVALID_REQUEST", resolve("error.request.body.unreadable")));
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
                .body(ApiResponse.error("INTERNAL_ERROR", resolve("error.internal")));
    }

    /**
     * 상위 클래스가 처리하는 Spring MVC 표준 예외를 한 곳에서 로깅합니다.
     *
     * <p>클라이언트 잘못(4xx)은 스택 없이 WARN 으로, 서버 잘못(5xx)만 스택과 함께 ERROR 로 남긴다.
     * 상속 전에는 오타 URL 하나가 ERROR + 스택으로 찍혀 ERROR 0 게이트를 오염시켰다.</p>
     *
     * @param exception  변환 대상 예외
     * @param body       상위 클래스가 만든 본문(대개 {@code ProblemDetail})
     * @param headers    응답 헤더
     * @param statusCode 상위 클래스가 정한 상태 코드
     * @param request    현재 요청
     * @return 상위 클래스가 구성한 응답
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            final Exception exception,
            final Object body,
            final HttpHeaders headers,
            final HttpStatusCode statusCode,
            final WebRequest request
    ) {
        final HttpServletRequest servletRequest = servletRequestOf(request);
        if (isClientDisconnect(exception)) {
            log.warn("응답 전송 중 클라이언트가 연결을 끊었습니다. method={}, uri={}, reason={}",
                    resolveMethod(servletRequest), resolveRequestUri(servletRequest), exception.getMessage());
        } else if (statusCode.is5xxServerError()) {
            log.error("표준 MVC 예외(서버). method={}, uri={}, status={}",
                    resolveMethod(servletRequest), resolveRequestUri(servletRequest), statusCode.value(), exception);
        } else {
            log.warn("표준 MVC 예외(요청). method={}, uri={}, status={}, reason={}: {}",
                    resolveMethod(servletRequest), resolveRequestUri(servletRequest), statusCode.value(),
                    exception.getClass().getSimpleName(), exception.getMessage());
        }
        return super.handleExceptionInternal(exception, body, headers, statusCode, request);
    }

    /**
     * 상위 클래스 경로의 응답 본문을 {@link ApiResponse} 계약으로 통일합니다.
     *
     * <p>상위 클래스 기본값은 RFC 9457 {@code ProblemDetail} 이라 그대로 두면
     * {@code success / data / errorCode / errorMsg} 계약이 깨진다. 개별 handler 가 이미
     * {@link ApiResponse} 를 만들어 넘긴 경우에는 그 본문을 그대로 살린다 — 필드 오류 메시지처럼
     * 상태 코드만으로는 만들 수 없는 정보를 잃지 않기 위해서다.</p>
     *
     * @param body       상위 클래스 또는 개별 handler 가 만든 본문
     * @param headers    응답 헤더
     * @param statusCode 응답 상태 코드
     * @param request    현재 요청
     * @return {@link ApiResponse} 본문을 가진 응답
     */
    @Override
    protected ResponseEntity<Object> createResponseEntity(
            final Object body,
            final HttpHeaders headers,
            final HttpStatusCode statusCode,
            final WebRequest request
    ) {
        final Object responseBody = body instanceof ApiResponse<?>
                ? body
                : ApiResponse.error(errorCodeOf(statusCode), resolve(messageCodeOf(statusCode)));
        return super.createResponseEntity(responseBody, headers, statusCode, request);
    }

    /**
     * 상태 코드를 {@link UiErrorCode} 로 환원합니다.
     *
     * @param statusCode 응답 상태 코드
     * @return 오류 코드
     */
    private static String errorCodeOf(final HttpStatusCode statusCode) {
        if (statusCode.value() == HttpStatus.NOT_FOUND.value()) {
            return UiErrorCode.NOT_FOUND;
        }
        return statusCode.is4xxClientError() ? UiErrorCode.INVALID_REQUEST : UiErrorCode.INTERNAL_ERROR;
    }

    /**
     * 상태 코드를 i18n 메시지 코드로 환원합니다.
     *
     * @param statusCode 응답 상태 코드
     * @return 메시지 코드
     */
    private static String messageCodeOf(final HttpStatusCode statusCode) {
        return switch (HttpStatus.valueOf(statusCode.value())) {
            case NOT_FOUND -> "error.request.notfound";
            case METHOD_NOT_ALLOWED, NOT_ACCEPTABLE, UNSUPPORTED_MEDIA_TYPE, PAYLOAD_TOO_LARGE ->
                    "error.request.unsupported";
            case SERVICE_UNAVAILABLE -> "error.request.timeout";
            default -> statusCode.is4xxClientError() ? "error.request.invalid" : "error.internal";
        };
    }

    /**
     * {@link WebRequest} 에서 서블릿 요청을 꺼냅니다(로그용).
     *
     * @param request 현재 요청
     * @return 서블릿 요청. 서블릿 환경이 아니면 null
     */
    /**
     * 응답을 쓰는 도중 클라이언트가 연결을 끊은 경우인지 판별합니다.
     *
     * <p>브라우저가 탭을 닫거나 요청을 취소하면 응답 write 가 {@link IOException} 으로 실패하고,
     * Spring 이 이를 {@link HttpMessageNotWritableException}(500) 으로 감싼다. 서버는 잘못한 것이
     * 없는데 ERROR 로 남으면 실제 장애와 구분되지 않고 "런타임 ERROR 0" 게이트를 오염시킨다.</p>
     *
     * <p>원인 사슬에 {@link IOException} 이 없는 {@link HttpMessageNotWritableException} 은 서버 자신의
     * 직렬화 실패이므로 이 판정에서 제외한다 — 그것은 진짜 결함이라 ERROR 로 남아야 한다.</p>
     *
     * @param exception 처리 중인 예외
     * @return 클라이언트가 연결을 끊어 발생한 쓰기 실패이면 {@code true}
     */
    private static boolean isClientDisconnect(final Throwable exception) {
        if (!(exception instanceof HttpMessageNotWritableException)) {
            return false;
        }

        for (Throwable cause = exception.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof IOException) {
                return true;
            }
        }
        return false;
    }

    private static HttpServletRequest servletRequestOf(final WebRequest request) {
        return request instanceof ServletWebRequest servletWebRequest ? servletWebRequest.getRequest() : null;
    }

    /**
     * 요청 Locale(Accept-Language) 기준으로 메시지 코드를 해석합니다.
     *
     * @param code 메시지 코드
     * @param args 메시지 인자
     * @return 해석된 메시지
     */
    private String resolve(final String code, final Object... args) {
        // 예외 핸들러는 절대 throw 하면 안 된다 — 메시지 누락(예: 요청 locale 이 messages_<lang> 으로 폴백되지 않는
        // 런타임 환경)으로 NoSuchMessageException 이 나면 핸들러 자체가 깨져 ApiResponse 대신 raw 500 이 나간다.
        // 4-arg getMessage 는 누락 시 throw 대신 기본값(code)을 반환하므로 핸들러가 항상 ApiResponse 를 완성한다.
        return messageSource.getMessage(code, args, code, LocaleContextHolder.getLocale());
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

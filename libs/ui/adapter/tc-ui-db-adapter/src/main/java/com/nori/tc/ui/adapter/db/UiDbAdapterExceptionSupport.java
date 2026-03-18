package com.nori.tc.ui.adapter.db;

import com.nori.tc.db.core.exception.DbDuplicateKeyException;

import java.sql.SQLException;
import java.util.Locale;
import java.util.Set;

/**
 * UI DB 어댑터에서 공통으로 사용하는 예외 분류/메시지 추출 유틸리티입니다.
 *
 * <p>목적:</p>
 * <ul>
 *   <li>DB Store 계층에서 넘어오는 예외를 HTTP 의미(400/409)로 해석하기 위한 보조 로직 제공</li>
 *   <li>JPA/MyBatis 구현 차이(예외 타입/메시지 포맷)를 최소한의 규칙으로 흡수</li>
 * </ul>
 *
 * <p>주의:</p>
 * <p>본 클래스는 예외를 직접 던지지 않습니다.
 * 상위 어댑터가 본 분류 결과를 사용해 {@code UiBadRequestException},
 * {@code UiConflictException} 등으로 변환합니다.</p>
 */
final class UiDbAdapterExceptionSupport {

    /**
     * 충돌(409) 가능성이 높은 키워드 집합입니다.
     *
     * <p>DB 벤더/드라이버별 메시지 차이를 고려해 공통적으로 등장하는 단어를 사용합니다.</p>
     */
    private static final Set<String> CONFLICT_MESSAGE_KEYWORDS = Set.of(
            "duplicate key",
            "unique constraint",
            "foreign key",
            "integrity constraint",
            "constraint violation",
            "violates",
            "already exists",
            "23503",
            "23505",
            "fk_",
            "uk_"
    );

    /**
     * 유틸리티 클래스 인스턴스화를 방지합니다.
     */
    private UiDbAdapterExceptionSupport() {
        // 유틸리티 클래스는 정적 메서드만 사용합니다.
    }

    /**
     * 전달된 예외 체인에 입력값 검증 오류(400)가 포함되어 있는지 판별합니다.
     *
     * @param throwable 검사할 예외
     * @return 400 성격이면 true
     */
    static boolean isBadRequest(final Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof IllegalArgumentException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * 전달된 예외 체인에 데이터 충돌(409) 성격이 포함되어 있는지 판별합니다.
     *
     * <p>판별 기준:</p>
     * <ul>
     *   <li>{@link DbDuplicateKeyException}</li>
     *   <li>SQLSTATE class 23(무결성 제약 위반)</li>
     *   <li>예외 클래스명/메시지의 충돌 키워드</li>
     * </ul>
     *
     * @param throwable 검사할 예외
     * @return 409 성격이면 true
     */
    static boolean isConflict(final Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof DbDuplicateKeyException) {
                return true;
            }

            if (current instanceof SQLException sqlException) {
                final String sqlState = sqlException.getSQLState();
                if (sqlState != null && sqlState.startsWith("23")) {
                    return true;
                }
            }

            final String className = current.getClass().getName().toLowerCase(Locale.ROOT);
            if (className.contains("duplicatekey")
                    || className.contains("dataintegrityviolation")
                    || className.contains("constraintviolation")
                    || className.contains("sqlintegrityconstraintviolation")) {
                return true;
            }

            final String message = current.getMessage();
            if (message != null && !message.isBlank()) {
                final String lowerMessage = message.toLowerCase(Locale.ROOT);
                for (String keyword : CONFLICT_MESSAGE_KEYWORDS) {
                    if (lowerMessage.contains(keyword)) {
                        return true;
                    }
                }
            }

            current = current.getCause();
        }
        return false;
    }

    /**
     * 예외 체인에서 사용자/로그에 사용할 메시지를 추출합니다.
     *
     * <p>우선순위:</p>
     * <ol>
     *   <li>최상위 예외 메시지</li>
     *   <li>원인 체인의 첫 번째 비어있지 않은 메시지</li>
     *   <li>fallback 메시지</li>
     * </ol>
     *
     * @param throwable 원본 예외
     * @param fallback 모든 메시지가 비어 있을 때 사용할 기본 문구
     * @return 정규화된 메시지
     */
    static String resolveMessage(final Throwable throwable, final String fallback) {
        if (throwable == null) {
            return fallback;
        }

        if (throwable.getMessage() != null && !throwable.getMessage().isBlank()) {
            return throwable.getMessage();
        }

        Throwable current = throwable.getCause();
        while (current != null) {
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                return current.getMessage();
            }
            current = current.getCause();
        }

        return fallback;
    }
}

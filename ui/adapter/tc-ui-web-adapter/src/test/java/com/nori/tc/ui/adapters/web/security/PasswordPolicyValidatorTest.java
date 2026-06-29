package com.nori.tc.ui.adapters.web.security;

import com.nori.tc.ui.core.exception.UiBadRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * W1-5 (AC-W1-5) — 비밀번호 길이·복잡도 정책 강제 검증.
 *
 * <p>강한 비밀번호 허용 + 약한 비밀번호 거부를 직접 검증한다. 만료/재사용 제한은
 * {@code tc_user_info} 에 만료일/이력 컬럼이 없어 본 주 코드 강제 대상이 아니며
 * (정책 문서 + W3 Flyway V2 이월), 본 테스트는 길이·복잡도 강제분만 다룬다.</p>
 */
class PasswordPolicyValidatorTest {

    private final PasswordPolicyValidator validator = new PasswordPolicyValidator();

    @ParameterizedTest
    @ValueSource(strings = {
            "Str0ng!Pass99",   // 13자, 4종(소/대/숫자/특수)
            "abcdefgh1A",       // 10자(경계), 3종(소/대/숫자)
            "Pa55word!!",       // 10자, 4종
            "Sommer2026#"       // 11자, 4종(소/대/숫자/특수)
    })
    @DisplayName("강한 비밀번호는 통과한다")
    void strongPasswordsPass(final String password) {
        assertDoesNotThrow(() -> validator.validate(password));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Ab1!xyz",          // 7자 — 길이 미달
            "abcdefghij",       // 1종(소문자만)
            "abcdefgh12",       // 2종(소문자+숫자)
            "ABCDEFGH12",       // 2종(대문자+숫자)
            "Str0ng Pass1!",    // 공백 포함
            "\tAb1!xyzabc"      // 탭(공백류) 포함
    })
    @DisplayName("약한 비밀번호는 거부한다")
    void weakPasswordsRejected(final String password) {
        assertThrows(UiBadRequestException.class, () -> validator.validate(password));
    }

    @Test
    @DisplayName("null / 빈 문자열은 거부한다")
    void nullOrEmptyRejected() {
        assertThrows(UiBadRequestException.class, () -> validator.validate(null));
        assertThrows(UiBadRequestException.class, () -> validator.validate(""));
    }

    @Test
    @DisplayName("최대 길이 초과는 거부한다")
    void tooLongRejected() {
        final String tooLong = "Aa1!".repeat(40); // 160자 > 128
        assertThrows(UiBadRequestException.class, () -> validator.validate(tooLong));
    }
}

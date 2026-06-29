package com.nori.tc.ui.adapters.web.security;

import com.nori.tc.ui.core.exception.UiBadRequestException;

/**
 * 신규/변경 비밀번호의 길이·복잡도 정책을 코드로 강제하는 순수 검증기입니다.
 *
 * <p>강제 규칙(이번 주 코드 강제분):</p>
 * <ul>
 *   <li>최소 길이 {@value #MIN_LENGTH}자, 최대 길이 {@value #MAX_LENGTH}자</li>
 *   <li>공백 문자 불허(앞뒤/중간 모두)</li>
 *   <li>문자 종류 4가지(소문자/대문자/숫자/특수문자) 중 최소 {@value #MIN_CHARACTER_CLASSES}종 포함</li>
 * </ul>
 *
 * <p><b>미강제(정책 문서 + 차기 이월):</b> 비밀번호 만료 주기·재사용 제한은 {@code tc_user_info} 에
 * 만료일/이력 컬럼이 없어 본 주 코드로 강제하지 않습니다. 정책 값은
 * {@code docs/security/account-lifecycle-policy.md} 에 명시하며, 컬럼/이력 테이블 신설과 강제는
 * W3 Flyway V2(스키마 변경 게이트)로 이월합니다.</p>
 */
final class PasswordPolicyValidator {

    static final int MIN_LENGTH = 10;
    static final int MAX_LENGTH = 128;
    static final int MIN_CHARACTER_CLASSES = 3;

    /**
     * 비밀번호가 길이·복잡도 정책을 만족하는지 검증합니다.
     *
     * @param rawPassword 검증할 원문 비밀번호
     * @throws UiBadRequestException 정책 위반 시(길이 미달/초과, 공백 포함, 문자 종류 부족, 빈 값)
     */
    void validate(final String rawPassword) {
        if (rawPassword == null || rawPassword.isEmpty()) {
            throw new UiBadRequestException("비밀번호는 비어 있을 수 없습니다.");
        }
        if (rawPassword.length() < MIN_LENGTH || rawPassword.length() > MAX_LENGTH) {
            throw new UiBadRequestException(
                    "비밀번호는 " + MIN_LENGTH + "자 이상 " + MAX_LENGTH + "자 이하여야 합니다.");
        }
        if (containsWhitespace(rawPassword)) {
            throw new UiBadRequestException("비밀번호에는 공백 문자를 사용할 수 없습니다.");
        }
        if (characterClassCount(rawPassword) < MIN_CHARACTER_CLASSES) {
            throw new UiBadRequestException(
                    "비밀번호는 소문자/대문자/숫자/특수문자 중 " + MIN_CHARACTER_CLASSES + "종 이상을 포함해야 합니다.");
        }
    }

    private static boolean containsWhitespace(final String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isWhitespace(value.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static int characterClassCount(final String value) {
        boolean hasLower = false;
        boolean hasUpper = false;
        boolean hasDigit = false;
        boolean hasSpecial = false;
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            if (Character.isLowerCase(c)) {
                hasLower = true;
            } else if (Character.isUpperCase(c)) {
                hasUpper = true;
            } else if (Character.isDigit(c)) {
                hasDigit = true;
            } else {
                hasSpecial = true;
            }
        }
        int count = 0;
        if (hasLower) {
            count++;
        }
        if (hasUpper) {
            count++;
        }
        if (hasDigit) {
            count++;
        }
        if (hasSpecial) {
            count++;
        }
        return count;
    }
}

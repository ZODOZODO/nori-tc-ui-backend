package com.nori.tc.ui.adapters.web.security;

import com.nori.tc.ui.core.exception.UiBadRequestException;
import com.nori.tc.ui.core.port.db.PasswordHistoryPort;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * W1-5 (AC-W1-5) — 비밀번호 재사용 금지 검사 단위 검증.
 *
 * <p>실 {@link BCryptPasswordEncoder} + 실 {@link BcryptPasswordVerifier} 로 재사용 시나리오를 재현한다.
 * 이력 포트는 in-memory fake 지만, 저장되는 해시는 실제 BCrypt 해시이고 재사용 판정도 실제 BCrypt.matches 로
 * 수행되므로 "직전 N회와 동일 평문은 거부 / 새 평문은 허용 + 이력 적재" 라는 실제 동작을 검증한다.</p>
 */
class UiPasswordPolicyServiceTest {

    private static final String VALID_PASSWORD_1 = "Aa1!aaaaaa";   // 10자, 4종
    private static final String VALID_PASSWORD_2 = "Bb2@bbbbbb";   // 다른 강한 비밀번호
    private static final long USER_PK = 11L;

    @Test
    void encodeChangedPasswordRejectsReuseWithinRecentWindow() {
        final InMemoryPasswordHistoryPort historyPort = new InMemoryPasswordHistoryPort();
        final PasswordEncoder encoder = new BCryptPasswordEncoder();
        final UiPasswordPolicyService service = new UiPasswordPolicyService(
                encoder, historyPort, new BcryptPasswordVerifier(encoder));

        // 최초 변경: 이력 1건 적재
        final String firstHash = service.encodeChangedPassword(USER_PK, VALID_PASSWORD_1);
        assertTrue(encoder.matches(VALID_PASSWORD_1, firstHash));
        assertEquals(1, historyPort.hashesByUser.getOrDefault(USER_PK, List.of()).size());

        // 같은 평문 재사용 → 거부(이력 미적재)
        final UiBadRequestException ex = assertThrows(
                UiBadRequestException.class,
                () -> service.encodeChangedPassword(USER_PK, VALID_PASSWORD_1));
        assertTrue(ex.getMessage().contains("재사용"), "재사용 거부 메시지여야 한다.");
        assertEquals(1, historyPort.hashesByUser.get(USER_PK).size(), "거부 시 이력이 늘면 안 된다.");
    }

    @Test
    void encodeChangedPasswordAllowsNonReuseAndRecordsHistory() {
        final InMemoryPasswordHistoryPort historyPort = new InMemoryPasswordHistoryPort();
        final PasswordEncoder encoder = new BCryptPasswordEncoder();
        final UiPasswordPolicyService service = new UiPasswordPolicyService(
                encoder, historyPort, new BcryptPasswordVerifier(encoder));

        service.encodeChangedPassword(USER_PK, VALID_PASSWORD_1);
        final String secondHash = service.encodeChangedPassword(USER_PK, VALID_PASSWORD_2);

        assertTrue(encoder.matches(VALID_PASSWORD_2, secondHash));
        assertEquals(2, historyPort.hashesByUser.get(USER_PK).size(), "새 비밀번호 허용 시 이력 2건이어야 한다.");
    }

    /**
     * 재사용 윈도우는 직전 N(=5)회. 6번째 변경 시점에 가장 오래된(1번째) 비밀번호는 윈도우 밖이라 다시 허용된다.
     */
    @Test
    void encodeChangedPasswordAllowsReuseBeyondWindow() {
        final InMemoryPasswordHistoryPort historyPort = new InMemoryPasswordHistoryPort();
        final PasswordEncoder encoder = new BCryptPasswordEncoder();
        final UiPasswordPolicyService service = new UiPasswordPolicyService(
                encoder, historyPort, new BcryptPasswordVerifier(encoder));

        // 6개의 서로 다른 강한 비밀번호를 순차 변경 → 이력 6건
        final List<String> sixPasswords = List.of(
                "Aa1!aaaaaa", "Bb2@bbbbbb", "Cc3#cccccc", "Dd4$dddddd", "Ee5%eeeeee", "Ff6^ffffff");
        for (final String pw : sixPasswords) {
            service.encodeChangedPassword(USER_PK, pw);
        }

        // 최신 5개 윈도우 = [6,5,4,3,2]. 1번째(Aa1!aaaaaa)는 윈도우 밖 → 재사용 허용.
        final String reusedOldHash = service.encodeChangedPassword(USER_PK, sixPasswords.get(0));
        assertTrue(encoder.matches(sixPasswords.get(0), reusedOldHash));
        assertEquals(7, historyPort.hashesByUser.get(USER_PK).size());
    }

    /**
     * 변경 시각 최신순 limit N 을 정확히 재현하는 in-memory 이력 포트.
     */
    private static final class InMemoryPasswordHistoryPort implements PasswordHistoryPort {
        private final java.util.Map<Long, List<String>> hashesByUser = new java.util.HashMap<>();

        @Override
        public List<String> recentHashes(final long userPk, final int limit) {
            final List<String> all = hashesByUser.getOrDefault(userPk, List.of());
            final List<String> latestFirst = new ArrayList<>(all);
            java.util.Collections.reverse(latestFirst);
            return latestFirst.stream().limit(limit).toList();
        }

        @Override
        public void record(final long userPk, final String passwordHash, final OffsetDateTime changedAt) {
            hashesByUser.computeIfAbsent(userPk, k -> new ArrayList<>()).add(passwordHash);
        }
    }
}

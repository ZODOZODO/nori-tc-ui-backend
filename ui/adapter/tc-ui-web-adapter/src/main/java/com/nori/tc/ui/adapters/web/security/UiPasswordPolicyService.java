package com.nori.tc.ui.adapters.web.security;

import com.nori.tc.ui.core.exception.UiBadRequestException;
import com.nori.tc.ui.core.port.db.PasswordHistoryPort;
import com.nori.tc.ui.core.port.db.PasswordVerifierPort;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

/**
 * UI 비밀번호 인코딩 정책을 한 곳에 모읍니다.
 *
 * <p>신규/변경 비밀번호는 {@link PasswordPolicyValidator} 로 길이·복잡도 정책을 강제한 뒤
 * 해시합니다(약한 비밀번호 거부). 기존 사용자의 비밀번호 변경은 추가로 직전 N회 재사용 금지
 * (account-lifecycle-policy.md §2.2 = 5회)를 강제하고, 통과 시 이력에 적재합니다.</p>
 */
public class UiPasswordPolicyService {

    /** 재사용 금지 윈도우(직전 N회). account-lifecycle-policy.md §2.2 = 5회. */
    static final int REUSE_HISTORY_LIMIT = 5;

    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicyValidator passwordPolicyValidator;
    private final PasswordHistoryPort passwordHistoryPort;
    private final PasswordVerifierPort passwordVerifierPort;

    public UiPasswordPolicyService(
            final PasswordEncoder passwordEncoder,
            final PasswordHistoryPort passwordHistoryPort,
            final PasswordVerifierPort passwordVerifierPort
    ) {
        this.passwordEncoder = Objects.requireNonNull(passwordEncoder, "passwordEncoder is null");
        this.passwordHistoryPort = Objects.requireNonNull(passwordHistoryPort, "passwordHistoryPort is null");
        this.passwordVerifierPort = Objects.requireNonNull(passwordVerifierPort, "passwordVerifierPort is null");
        this.passwordPolicyValidator = new PasswordPolicyValidator();
    }

    /**
     * 신규 비밀번호를 정책(길이·복잡도) 기준으로 검증한 뒤 해시합니다.
     *
     * <p>신규 생성(create)은 사용자 PK 가 없어 이력 비교가 불가능하므로 재사용 검사·이력 적재를 하지 않습니다.
     * 만료 카운트다운 기준 시각(password_changed_at)은 호출 측에서 now 로 세팅합니다.</p>
     *
     * @param rawPassword 입력 비밀번호
     * @return 저장할 password hash
     * @throws UiBadRequestException 정책 위반 시
     */
    public String encodeNewPassword(final String rawPassword) {
        passwordPolicyValidator.validate(rawPassword);
        return passwordEncoder.encode(rawPassword);
    }

    /**
     * 기존 사용자의 변경 비밀번호를 검증(길이·복잡도 + 직전 N회 재사용 금지)한 뒤 해시하고 이력에 적재합니다.
     *
     * @param userPk      대상 사용자 PK
     * @param rawPassword 입력 비밀번호
     * @return 저장할 password hash
     * @throws UiBadRequestException 정책 위반 또는 최근 N회 비밀번호 재사용 시
     */
    public String encodeChangedPassword(final long userPk, final String rawPassword) {
        passwordPolicyValidator.validate(rawPassword);

        final List<String> recentHashes = passwordHistoryPort.recentHashes(userPk, REUSE_HISTORY_LIMIT);
        for (final String oldHash : recentHashes) {
            if (passwordVerifierPort.matches(rawPassword, oldHash)) {
                throw new UiBadRequestException("최근 " + REUSE_HISTORY_LIMIT + "회 내 사용한 비밀번호는 재사용할 수 없습니다.");
            }
        }

        final String encoded = passwordEncoder.encode(rawPassword);
        passwordHistoryPort.record(userPk, encoded, OffsetDateTime.now());
        return encoded;
    }
}

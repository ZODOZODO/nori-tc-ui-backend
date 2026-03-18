package com.nori.tc.ui.core.usecase;

import com.nori.tc.db.domain.common.user.UserStatus;
import com.nori.tc.db.domain.user.TcUiAuthSession;
import com.nori.tc.db.domain.user.TcUserInfo;
import com.nori.tc.ui.core.exception.UiAuthenticationException;
import com.nori.tc.ui.core.port.db.PasswordVerifierPort;
import com.nori.tc.ui.core.port.db.SessionPort;
import com.nori.tc.ui.core.port.db.UserPort;
import com.nori.tc.ui.core.properties.UiAuthProperties;
import com.nori.tc.ui.domain.auth.AuthToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.OffsetDateTime;

/**
 * 로그인(인증 세션 발급) 유스케이스입니다.
 *
 * <p>처리 흐름:</p>
 * <ol>
 *   <li>userId를 정규화(trim + toLowerCase)하여 tc_user_info 조회</li>
 *   <li>계정 상태 확인 - ACTIVE가 아니면 인증 거부</li>
 *   <li>BCrypt 비밀번호 검증 - 불일치 시 인증 거부</li>
 *   <li>SecureRandom 64자 세션 토큰 생성</li>
 *   <li>tc_ui_auth_session에 세션 저장</li>
 *   <li>AuthToken 반환 (Web Adapter가 HttpOnly 인증 쿠키로 전달)</li>
 * </ol>
 *
 * <p>보안 원칙:</p>
 * <p>사용자 미존재와 비밀번호 불일치를 동일한 예외 메시지로 처리하여
 * 사용자 존재 여부가 외부에 노출되지 않도록 합니다 (사용자 열거 공격 완화).</p>
 */
@Component
public class LoginUseCase {

    private static final Logger log = LoggerFactory.getLogger(LoginUseCase.class);

    // 세션 토큰: 대소문자 영문 + 숫자, 64자
    private static final String TOKEN_CHARS =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final int TOKEN_LENGTH = 64;

    // SecureRandom은 Thread-safe하므로 싱글톤으로 사용합니다.
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final UserPort userPort;
    private final SessionPort sessionPort;
    private final PasswordVerifierPort passwordVerifierPort;
    private final UiAuthProperties authProperties;

    public LoginUseCase(
            final UserPort userPort,
            final SessionPort sessionPort,
            final PasswordVerifierPort passwordVerifierPort,
            final UiAuthProperties authProperties
    ) {
        this.userPort = userPort;
        this.sessionPort = sessionPort;
        this.passwordVerifierPort = passwordVerifierPort;
        this.authProperties = authProperties;
    }

    /**
     * 사용자 ID와 비밀번호로 로그인하여 세션 토큰을 발급합니다.
     *
     * @param userId      사용자가 입력한 로그인 ID (정규화 전 원문)
     * @param rawPassword 사용자가 입력한 원문 비밀번호
     * @return 발급된 인증 토큰 정보 (token, userPk, issuedAt, expiresAt)
     * @throws UiAuthenticationException 인증 실패 시 (사용자 없음, 비밀번호 불일치, 계정 비활성)
     */
    public AuthToken execute(final String userId, final String rawPassword) {
        // userId 정규화: 앞뒤 공백 제거 + 소문자 변환 (DB의 user_id_norm 컬럼과 일치)
        final String userIdNorm = userId.trim().toLowerCase();
        log.debug("로그인 시도. userIdNorm={}", userIdNorm);

        // 사용자 조회: 존재하지 않으면 인증 실패
        final TcUserInfo userInfo = userPort.findByUserIdNorm(userIdNorm)
                .orElseThrow(() -> {
                    // 사용자 미존재 여부를 외부에 노출하지 않기 위해 동일 메시지 사용
                    log.warn("로그인 실패 - 사용자 없음. userIdNorm={}", userIdNorm);
                    return new UiAuthenticationException("아이디 또는 비밀번호가 올바르지 않습니다.");
                });

        // 계정 상태 확인: ACTIVE가 아니면 로그인 불가
        if (userInfo.status() != UserStatus.ACTIVE) {
            log.warn("로그인 실패 - 비활성 계정. userIdNorm={}, status={}", userIdNorm, userInfo.status());
            throw new UiAuthenticationException("사용 불가능한 계정입니다.");
        }

        // 비밀번호 검증: BCrypt 비교 (PasswordVerifierPort가 BCrypt 구현 제공)
        if (!passwordVerifierPort.matches(rawPassword, userInfo.passwordHash())) {
            log.warn("로그인 실패 - 비밀번호 불일치. userIdNorm={}", userIdNorm);
            throw new UiAuthenticationException("아이디 또는 비밀번호가 올바르지 않습니다.");
        }

        // 세션 토큰 생성 및 저장
        final String token = generateToken();
        final OffsetDateTime now = OffsetDateTime.now();
        final OffsetDateTime expiresAt = now.plusHours(authProperties.sessionTtlHours());
        final long userPk = userInfo.userPk();

        final TcUiAuthSession session = new TcUiAuthSession(token, userPk, now, expiresAt, null, false);
        sessionPort.save(session);

        log.info("로그인 성공. userIdNorm={}, userPk={}, expiresAt={}", userIdNorm, userPk, expiresAt);
        return new AuthToken(token, userPk, now, expiresAt);
    }

    /**
     * SecureRandom 기반 64자 alphanumeric 세션 토큰을 생성합니다.
     *
     * <p>충돌 확률: 62^64 ≈ 10^114 (천문학적으로 낮음)</p>
     *
     * @return 무작위 64자 토큰 문자열
     */
    private String generateToken() {
        final StringBuilder sb = new StringBuilder(TOKEN_LENGTH);
        for (int i = 0; i < TOKEN_LENGTH; i++) {
            sb.append(TOKEN_CHARS.charAt(SECURE_RANDOM.nextInt(TOKEN_CHARS.length())));
        }
        return sb.toString();
    }
}

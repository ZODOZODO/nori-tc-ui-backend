package com.nori.tc.ui.adapters.web.security;

import com.nori.tc.db.domain.common.user.TcUiPermissionMatchType;
import com.nori.tc.db.domain.user.TcUiPermission;
import com.nori.tc.ui.core.port.db.UiApiPermissionPort;
import com.nori.tc.ui.domain.auth.UserPrincipal;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * 활성 API 권한 목록을 메모리에 캐시하는 컴포넌트입니다.
 *
 * <p>역할:</p>
 * <p>애플리케이션 기동 시 {@link UiApiPermissionPort}를 통해 tc_ui_permission 테이블의
 * 활성 API 권한을 전체 로드하여 메모리에 보관합니다.
 * {@link UiSecurityConfig}의 커스텀 {@code AuthorizationManager}가 매 HTTP 요청마다
 * 이 캐시를 참조하여 URL 인가 판단을 수행합니다.</p>
 *
 * <p>인가 판단 규칙(Closed by Default):</p>
 * <ul>
 *   <li>요청 URI에 해당하는 API 권한이 없는 경우 → 기본 차단</li>
 *   <li>요청 URI에 매칭되는 API 권한이 있는 경우 → 사용자가 해당 permCode 보유 시 허용</li>
 *   <li>matchType=PREFIX → requestUri.startsWith(permission.resource)</li>
 *   <li>matchType=EXACT  → requestUri.equals(permission.resource)</li>
 *   <li>httpMethod=null  → 모든 HTTP 메서드 허용</li>
 *   <li>httpMethod 지정  → 요청 메서드와 대소문자 무관 비교</li>
 * </ul>
 *
 * <p>기동 실패 대응(failsafe):</p>
 * <p>초기 권한 로드에 실패하면 {@code initializationFailed=true} 상태로 전환하고,
 * 권한 판단 요청을 전부 차단합니다. "권한 로드 실패 시 전체 허용" 문제를 방지합니다.</p>
 */
@Component
public class UiApiPermissionCache {

    private static final Logger log = LoggerFactory.getLogger(UiApiPermissionCache.class);

    private final UiApiPermissionPort apiPermissionPort;

    /**
     * volatile: 기동 후 단 1회 write가 발생하고 이후 read만 발생하므로
     * synchronized 없이 volatile만으로 가시성을 보장합니다.
     */
    private volatile List<TcUiPermission> cachedPermissions = List.of();

    /**
     * 초기화 실패 플래그입니다.
     *
     * <p>true 상태에서는 모든 보호 API를 차단합니다(failsafe).</p>
     */
    private volatile boolean initializationFailed;

    /**
     * 필수 의존성을 초기화합니다.
     *
     * @param apiPermissionPort API 권한 전체 조회 포트
     */
    public UiApiPermissionCache(final UiApiPermissionPort apiPermissionPort) {
        this.apiPermissionPort = Objects.requireNonNull(apiPermissionPort, "apiPermissionPort is null");
    }

    /**
     * 기동 시 API 권한 목록을 로드합니다.
     *
     * <p>DB 조회 실패 시 failsafe 모드로 전환하고 모든 API를 차단합니다.</p>
     */
    @PostConstruct
    public void loadPermissions() {
        // 초기 로딩은 실패 시 failsafe 모드로 전환해야 하므로 예외를 삼키지 않습니다.
        reloadPermissions(true);
    }

    /**
     * 권한 캐시를 주기적으로 갱신합니다.
     *
     * <p>운영 중 권한 정책(tc_ui_permission)이 변경되더라도 서버 재기동 없이
     * 반영되도록 fixedDelay 스케줄로 재로딩합니다.</p>
     */
    @Scheduled(fixedDelayString = "${tc.ui.backend.permission.refresh-interval-ms:300000}")
    public void refreshPermissions() {
        reloadPermissions(false);
    }

    /**
     * 권한 목록을 재로딩합니다.
     *
     * @param startup true면 초기 로딩 경로, false면 주기 갱신 경로
     */
    private void reloadPermissions(final boolean startup) {
        try {
            final List<TcUiPermission> loaded = apiPermissionPort.findAllActiveApiPermissions();
            this.cachedPermissions = loaded;
            this.initializationFailed = false;
            if (startup) {
                log.info("API 권한 캐시 로드 완료. 총 {}개 API 권한 활성.", loaded.size());
            } else {
                log.info("API 권한 캐시 갱신 완료. 총 {}개 API 권한 활성.", loaded.size());
            }
        } catch (Exception e) {
            if (startup) {
                this.cachedPermissions = List.of();
                this.initializationFailed = true;
                log.error("API 권한 캐시 로드 실패 - failsafe 모드 활성화(모든 보호 API 차단).", e);
            } else {
                // 주기 갱신 실패 시에는 기존 캐시를 유지하여 불필요한 권한 전체 차단 전환을 방지합니다.
                log.warn("API 권한 캐시 갱신 실패 - 기존 캐시 유지.", e);
            }
        }
    }

    /**
     * 요청 URI와 HTTP 메서드에 대해 사용자의 접근 권한을 판단합니다.
     *
     * <p>매칭되는 API 권한이 없으면 기본 차단합니다(closed by default).
     * 매칭되는 권한이 있으면 사용자의 permissionCodes 중 하나가 포함되어야 허용합니다.</p>
     *
     * @param principal  인증된 사용자 정보
     * @param httpMethod 요청 HTTP 메서드 (예: GET, POST)
     * @param requestUri 요청 URI (예: /api/eqp/EQP001)
     * @return 접근 허용이면 true, 거부면 false
     */
    public boolean isAuthorized(final UserPrincipal principal, final String httpMethod, final String requestUri) {
        if (initializationFailed) {
            log.warn("권한 캐시 초기화 실패 상태 - 요청 차단. uri={}, method={}, userPk={}",
                    requestUri, httpMethod, principal.userPk());
            return false;
        }

        final List<TcUiPermission> matching = cachedPermissions.stream()
                .filter(p -> matchUri(p, requestUri))
                .filter(p -> matchMethod(p, httpMethod))
                .toList();

        if (matching.isEmpty()) {
            // Closed by default: 매핑된 권한이 없으면 차단
            log.warn("API 권한 미등록 경로 차단. uri={}, method={}, userPk={}",
                    requestUri, httpMethod, principal.userPk());
            return false;
        }

        // 매칭된 권한 중 사용자가 보유한 permCode가 있으면 허용
        final boolean allowed = matching.stream()
                .anyMatch(p -> principal.hasPermission(p.permCode()));

        if (!allowed) {
            log.warn("API 권한 부족. uri={}, method={}, userPk={}, requiredPermCodes={}",
                    requestUri, httpMethod, principal.userPk(),
                    matching.stream().map(TcUiPermission::permCode).toList());
        } else {
            log.trace("API 권한 확인 완료 - 허용. uri={}, method={}, userPk={}",
                    requestUri, httpMethod, principal.userPk());
        }

        return allowed;
    }

    // -------------------------------------------------------------------------
    // 내부 매칭 로직
    // -------------------------------------------------------------------------

    /**
     * 권한의 matchType에 따라 요청 URI가 매칭되는지 확인합니다.
     *
     * @param perm       권한 항목
     * @param requestUri 요청 URI
     * @return 매칭되면 true
     */
    private boolean matchUri(final TcUiPermission perm, final String requestUri) {
        if (perm.resource() == null || perm.resource().isBlank()) {
            return false;
        }
        if (perm.matchType() == TcUiPermissionMatchType.PREFIX) {
            return requestUri.startsWith(perm.resource());
        }
        // EXACT 또는 기타: 완전 일치
        return requestUri.equals(perm.resource());
    }

    /**
     * 권한의 httpMethod 설정에 따라 요청 메서드가 매칭되는지 확인합니다.
     *
     * <p>httpMethod가 null 또는 공백이면 모든 메서드를 허용합니다.</p>
     *
     * @param perm       권한 항목
     * @param httpMethod 요청 HTTP 메서드
     * @return 매칭되면 true
     */
    private boolean matchMethod(final TcUiPermission perm, final String httpMethod) {
        if (perm.httpMethod() == null || perm.httpMethod().isBlank()) {
            // httpMethod 미설정 → 모든 HTTP 메서드 허용
            return true;
        }
        return perm.httpMethod().equalsIgnoreCase(httpMethod);
    }
}

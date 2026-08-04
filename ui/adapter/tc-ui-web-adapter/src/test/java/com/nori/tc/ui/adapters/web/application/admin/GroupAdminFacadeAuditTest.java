package com.nori.tc.ui.adapters.web.application.admin;

import com.nori.tc.ui.adapters.web.security.UiPrivilegeEscalationGuard;
import com.nori.tc.ui.core.port.db.AuditLogPort;
import com.nori.tc.ui.core.port.db.GroupCrudPort;
import com.nori.tc.ui.core.port.db.GroupPermissionMappingPort;
import com.nori.tc.ui.core.port.db.UserCrudPort;
import com.nori.tc.ui.core.port.db.UserGroupMappingPort;
import com.nori.tc.ui.domain.audit.UiAuditAction;
import com.nori.tc.ui.domain.audit.UiAuditEvent;
import com.nori.tc.ui.domain.auth.UserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

/**
 * W1-6 (AC-W1-6) — 권한 변경(그룹 권한 부여/회수) 시 감사 레코드 생성 검증.
 *
 * <p>{@link GroupAdminFacade#addPermissionMapping}/{@code removePermissionMapping} 호출 시
 * actor(SecurityContext 의 로그인 사용자)·작업·대상이 {@link AuditLogPort} 로 기록되는지 검증한다.</p>
 */
class GroupAdminFacadeAuditTest {

    private final RecordingAuditLogPort auditLog = new RecordingAuditLogPort();
    private final UiPrivilegeEscalationGuard escalationGuard = mock(UiPrivilegeEscalationGuard.class);
    private final GroupAdminFacade facade = new GroupAdminFacade(
            mock(GroupCrudPort.class),
            mock(GroupPermissionMappingPort.class),
            mock(UserGroupMappingPort.class),
            mock(UserCrudPort.class),
            mock(GroupAdminAssembler.class),
            auditLog,
            escalationGuard);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticateAs(final String userId) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new UserPrincipal(1L, userId, Set.of()),
                null,
                AuthorityUtils.createAuthorityList("ROLE_USER")));
    }

    @Test
    @DisplayName("권한 부여 시 PERMISSION_GRANT 감사 레코드(actor·대상)가 생성된다")
    void grantRecordsAuditEvent() {
        authenticateAs("admin");

        facade.addPermissionMapping(5L, 7L);

        assertEquals(1, auditLog.events.size());
        final UiAuditEvent event = auditLog.events.get(0);
        assertEquals(UiAuditAction.PERMISSION_GRANT, event.action());
        assertEquals("admin", event.actor());
        assertEquals("group:5/perm:7", event.target());
    }

    @Test
    @DisplayName("권한 회수 시 PERMISSION_REVOKE 감사 레코드가 생성된다")
    void revokeRecordsAuditEvent() {
        authenticateAs("admin");

        facade.removePermissionMapping(5L, 7L);

        assertEquals(1, auditLog.events.size());
        final UiAuditEvent event = auditLog.events.get(0);
        assertEquals(UiAuditAction.PERMISSION_REVOKE, event.action());
        assertEquals("admin", event.actor());
        assertEquals("group:5/perm:7", event.target());
    }

    /**
     * 권한 부여는 S08 E-2 가드가 인증을 요구하므로, actor fallback 은 가드 대상이 아닌 회수 경로로 검증한다.
     * (회수는 권한을 늘리지 않아 승격 경로가 아니다.)
     */
    @Test
    @DisplayName("인증 컨텍스트가 없으면 actor 는 SYSTEM 으로 기록된다")
    void unauthenticatedActorFallsBackToSystem() {
        facade.removePermissionMapping(2L, 3L);

        assertEquals(1, auditLog.events.size());
        assertEquals("SYSTEM", auditLog.events.get(0).actor());
    }

    private static final class RecordingAuditLogPort implements AuditLogPort {
        private final List<UiAuditEvent> events = new ArrayList<>();

        @Override
        public void record(final UiAuditEvent event) {
            events.add(event);
        }
    }
}

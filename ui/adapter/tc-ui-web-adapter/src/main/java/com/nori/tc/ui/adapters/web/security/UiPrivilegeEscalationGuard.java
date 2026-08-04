package com.nori.tc.ui.adapters.web.security;

import com.nori.tc.ui.core.exception.UiForbiddenException;
import com.nori.tc.ui.core.model.UiPageRequest;
import com.nori.tc.ui.core.port.db.GroupPermissionMappingPort;
import com.nori.tc.ui.core.port.db.PermissionCrudPort;
import com.nori.tc.ui.core.port.db.PermissionPort;
import com.nori.tc.ui.domain.auth.UserPrincipal;
import com.nori.tc.ui.domain.useradmin.UiUserGroupPermission;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * 권한 상승(privilege escalation) 조작을 서버측에서 거부합니다.
 *
 * <p>불변식 하나로 정리한다 — <b>actor 는 자신이 보유하지 않은 권한을 남에게 부여할 수도, 그 권한
 * 보유자를 탈취할 수도 없다.</b></p>
 *
 * <p>URL 단위 인가({@code UiApiPermissionCache})는 {@code GROUP_WRITE} / {@code USER_INFO_WRITE} 같은
 * PREFIX permCode 하나로 그 경로 아래 전 엔드포인트를 덮으므로, 그룹 CRUD 권한만 받은 사용자가
 * <em>권한 부여 API</em>·<em>그룹 편입 API</em>·<em>비밀번호 재설정 API</em> 까지 함께 얻는다.
 * 그 상태에서 서버측 가드가 없으면 설정 실수 1회로 즉시 ADMIN 승격이 가능하다(S08 E-2).</p>
 *
 * <p>회수(revoke)·매핑 해제는 권한을 늘리지 않으므로 대상이 아니다.</p>
 */
@Component
public class UiPrivilegeEscalationGuard {

    /** 권한 검사에 필요한 전량 조회 상한 — 그룹당 권한 매핑은 permCode 총수(수십)를 넘지 않는다. */
    private static final UiPageRequest ALL_PERMISSIONS = new UiPageRequest(0, 500);

    private final GroupPermissionMappingPort groupPermissionMappingPort;
    private final PermissionCrudPort permissionCrudPort;
    private final PermissionPort permissionPort;

    public UiPrivilegeEscalationGuard(
            final GroupPermissionMappingPort groupPermissionMappingPort,
            final PermissionCrudPort permissionCrudPort,
            final PermissionPort permissionPort
    ) {
        this.groupPermissionMappingPort =
                Objects.requireNonNull(groupPermissionMappingPort, "groupPermissionMappingPort is null");
        this.permissionCrudPort = Objects.requireNonNull(permissionCrudPort, "permissionCrudPort is null");
        this.permissionPort = Objects.requireNonNull(permissionPort, "permissionPort is null");
    }

    /**
     * 그룹에 권한을 부여하려는 조작을 검사합니다 — actor 가 그 permCode 를 보유해야 합니다.
     *
     * @param permId 부여 대상 권한 PK
     */
    public void requireGrantablePermission(final long permId) {
        final Set<String> actorPermissions = requireActorPermissions();
        final String targetPermCode = permissionCrudPort.findByPermId(permId)
                .map(permission -> permission.permCode())
                .orElseThrow(() -> new UiForbiddenException("존재하지 않는 권한은 부여할 수 없습니다. permId=" + permId));

        if (!actorPermissions.contains(targetPermCode)) {
            throw new UiForbiddenException(
                    "본인이 보유하지 않은 권한은 부여할 수 없습니다. permCode=" + targetPermCode);
        }
    }

    /**
     * 사용자를 그룹에 편입하려는 조작을 검사합니다 — 그룹 권한이 actor 권한의 부분집합이어야 합니다.
     *
     * <p>편입은 그룹의 permCode 전부를 대상 사용자에게 주는 것과 같으므로, 부여와 같은 규칙을 적용한다.
     * 이 검사가 없으면 {@code USER_INFO_WRITE} 보유자가 자신을 ADMIN 그룹에 넣어 승격할 수 있다.</p>
     *
     * @param groupId 편입 대상 그룹 PK
     */
    public void requireAssignableGroup(final long groupId) {
        final Set<String> actorPermissions = requireActorPermissions();
        final Set<String> groupPermissions = resolveGroupPermissionCodes(groupId);

        if (!actorPermissions.containsAll(groupPermissions)) {
            throw new UiForbiddenException(
                    "본인이 보유하지 않은 권한을 가진 그룹에는 사용자를 편입할 수 없습니다. groupId=" + groupId);
        }
    }

    /**
     * 다른 사용자의 비밀번호를 재설정하려는 조작을 검사합니다 — 대상 권한이 actor 권한의 부분집합이어야 합니다.
     *
     * <p>비밀번호 재설정은 대상 계정 탈취와 같으므로 대상이 가진 권한을 actor 가 모두 갖고 있어야 한다.
     * 이 검사가 없으면 {@code USER_INFO_WRITE} 보유자가 admin 계정 비밀번호를 재설정할 수 있다.</p>
     *
     * @param targetUserPk 재설정 대상 사용자 PK
     */
    public void requireResettablePassword(final long targetUserPk) {
        final UserPrincipal actor = requireActor();
        if (actor.userPk() == targetUserPk) {
            return;
        }

        final Set<String> targetPermissions = permissionPort.findPermissionCodesByUserPk(targetUserPk);
        if (!actor.permissionCodes().containsAll(targetPermissions)) {
            throw new UiForbiddenException(
                    "본인보다 넓은 권한을 가진 사용자의 비밀번호는 재설정할 수 없습니다. userPk=" + targetUserPk);
        }
    }

    private Set<String> resolveGroupPermissionCodes(final long groupId) {
        final Set<String> permissionCodes = new LinkedHashSet<>();
        for (final UiUserGroupPermission mapping
                : groupPermissionMappingPort.findAllByGroupId(groupId, ALL_PERMISSIONS).items()) {
            permissionCrudPort.findByPermId(mapping.permId())
                    .ifPresent(permission -> permissionCodes.add(permission.permCode()));
        }
        return permissionCodes;
    }

    private Set<String> requireActorPermissions() {
        return requireActor().permissionCodes();
    }

    /**
     * SecurityContext 의 로그인 사용자를 해석합니다.
     *
     * <p>부재는 거부한다 — 이 경로들은 URL 인가에서 이미 principal 을 요구하므로 실제로는 도달하지 않지만,
     * "호출자가 이미 검사했다" 는 가정 위에 권한 가드를 세우지 않는다.</p>
     */
    private static UserPrincipal requireActor() {
        final Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof UserPrincipal principal)) {
            throw new UiForbiddenException("권한 변경은 인증된 사용자만 수행할 수 있습니다.");
        }
        return principal;
    }
}

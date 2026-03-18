package com.nori.tc.ui.adapter.db;

import com.nori.tc.db.core.user.store.TcUiPermissionStore;
import com.nori.tc.db.core.user.store.TcUserGroupMemberStore;
import com.nori.tc.db.core.user.store.TcUserGroupPermissionStore;
import com.nori.tc.db.domain.user.TcUiPermission;
import com.nori.tc.db.domain.user.TcUserGroupMember;
import com.nori.tc.db.domain.user.TcUserGroupPermission;
import com.nori.tc.ui.core.port.db.PermissionPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@link PermissionPort}의 JPA 기반 구현 어댑터입니다.
 *
 * <p>역할:</p>
 * <p>사용자의 활성 권한 코드 집합을 조회합니다.
 * 3개 Store를 단계별로 조회하여 userPk → permCode 목록을 완성합니다.</p>
 *
 * <p>3단계 조회 흐름 (3-JOIN 시뮬레이션):</p>
 * <pre>
 *   1. TcUserGroupMemberStore.findAllByUserPk(userPk)
 *                            → groupId 목록
 *
 *   2. TcUserGroupPermissionStore.findAllByGroupIdIn(groupIds)
 *                               → permId 목록
 *
 *   3. TcUiPermissionStore.findAllActiveByPermIdIn(permIds)
 *                        → perm_code 목록 (Set&lt;String&gt;)
 * </pre>
 *
 * <p>단계별 빈 결과 처리:</p>
 * <ul>
 *   <li>1단계: 그룹 없음 → 빈 Set 즉시 반환 (2,3단계 DB 조회 생략)</li>
 *   <li>2단계: 권한 매핑 없음 → 빈 Set 즉시 반환 (3단계 DB 조회 생략)</li>
 *   <li>3단계: 활성 권한 없음 → 빈 Set 반환</li>
 * </ul>
 */
@Repository
public class JpaPermissionPort implements PermissionPort {

    private static final Logger log = LoggerFactory.getLogger(JpaPermissionPort.class);

    private final TcUserGroupMemberStore memberStore;
    private final TcUserGroupPermissionStore groupPermissionStore;
    private final TcUiPermissionStore permissionStore;

    /**
     * 의존성을 초기화합니다.
     *
     * @param memberStore          tc_user_group_member Store
     * @param groupPermissionStore tc_user_group_permission Store
     * @param permissionStore      tc_ui_permission Store
     */
    public JpaPermissionPort(
            final TcUserGroupMemberStore memberStore,
            final TcUserGroupPermissionStore groupPermissionStore,
            final TcUiPermissionStore permissionStore
    ) {
        this.memberStore = Objects.requireNonNull(memberStore, "memberStore is null");
        this.groupPermissionStore = Objects.requireNonNull(groupPermissionStore, "groupPermissionStore is null");
        this.permissionStore = Objects.requireNonNull(permissionStore, "permissionStore is null");
        log.info("JpaPermissionPort initialized. source=tc_user_group_member → tc_user_group_permission → tc_ui_permission");
    }

    /**
     * 사용자의 모든 활성 권한 코드를 조회합니다.
     *
     * <p>인증 필터({@code ValidateTokenUseCase})의 캐시 미스 시 호출됩니다.
     * 3단계 Store 조회로 권한을 로드하고, 각 단계에서 빈 결과면 즉시 반환하여 불필요한 쿼리를 방지합니다.</p>
     *
     * @param userPk 사용자 PK
     * @return 활성화된 권한 코드 집합 (perm_code, is_active=true 조건)
     */
    @Override
    public Set<String> findPermissionCodesByUserPk(final long userPk) {
        log.debug("권한 코드 조회 시작. userPk={}", userPk);

        // 1단계: userPk → groupId 목록 조회
        final List<TcUserGroupMember> members = memberStore.findAllByUserPk(userPk);
        if (members.isEmpty()) {
            log.debug("권한 코드 조회 완료 - 소속 그룹 없음. userPk={}, 권한 수=0", userPk);
            return Set.of();
        }

        final Set<Long> groupIds = members.stream()
                .map(TcUserGroupMember::groupId)
                .collect(Collectors.toSet());
        log.trace("그룹 ID 조회 완료. userPk={}, groupIds={}", userPk, groupIds);

        // 2단계: groupId 목록 → permId 목록 조회
        final List<TcUserGroupPermission> groupPerms = groupPermissionStore.findAllByGroupIdIn(groupIds);
        if (groupPerms.isEmpty()) {
            log.debug("권한 코드 조회 완료 - 그룹에 권한 매핑 없음. userPk={}, 권한 수=0", userPk);
            return Set.of();
        }

        final Set<Long> permIds = groupPerms.stream()
                .map(TcUserGroupPermission::permId)
                .collect(Collectors.toSet());
        log.trace("권한 ID 조회 완료. userPk={}, permIds={}", userPk, permIds);

        // 3단계: permId 목록 + isActive = true → permCode 목록 조회
        final List<TcUiPermission> permissions = permissionStore.findAllActiveByPermIdIn(permIds);

        final Set<String> permCodes = permissions.stream()
                .map(TcUiPermission::permCode)
                .collect(Collectors.toSet());

        log.debug("권한 코드 조회 완료. userPk={}, 그룹 수={}, 활성 권한 수={}",
                userPk, groupIds.size(), permCodes.size());
        return permCodes;
    }
}

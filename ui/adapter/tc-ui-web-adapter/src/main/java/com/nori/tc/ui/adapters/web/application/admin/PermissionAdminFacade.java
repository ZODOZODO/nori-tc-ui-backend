package com.nori.tc.ui.adapters.web.application.admin;

import com.nori.tc.ui.adapters.web.dto.request.PermissionUpsertRequest;
import com.nori.tc.ui.adapters.web.dto.response.PermissionInfoResponse;
import com.nori.tc.ui.core.model.PagedResponse;
import com.nori.tc.ui.core.model.UiPageRequest;
import com.nori.tc.ui.core.model.UiUserGroupPermissionUpsertCommand;
import com.nori.tc.ui.core.port.db.GroupCrudPort;
import com.nori.tc.ui.core.port.db.GroupPermissionMappingPort;
import com.nori.tc.ui.core.port.db.PermissionCrudPort;
import com.nori.tc.ui.domain.useradmin.UiPermission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;

/**
 * Permission 관리 use-case를 controller 밖으로 이동시키는 facade 입니다.
 */
@Component
public class PermissionAdminFacade {

    private static final Logger log = LoggerFactory.getLogger(PermissionAdminFacade.class);
    private static final String ADMIN_GROUP_CODE = "ADMIN";
    private static final String DEFAULT_GRANTED_BY = "SYSTEM";

    private final PermissionCrudPort permissionCrudPort;
    private final GroupCrudPort groupCrudPort;
    private final GroupPermissionMappingPort groupPermissionMappingPort;
    private final PermissionAdminAssembler assembler;

    public PermissionAdminFacade(
            final PermissionCrudPort permissionCrudPort,
            final GroupCrudPort groupCrudPort,
            final GroupPermissionMappingPort groupPermissionMappingPort,
            final PermissionAdminAssembler assembler
    ) {
        this.permissionCrudPort = Objects.requireNonNull(permissionCrudPort, "permissionCrudPort is null");
        this.groupCrudPort = Objects.requireNonNull(groupCrudPort, "groupCrudPort is null");
        this.groupPermissionMappingPort = Objects.requireNonNull(groupPermissionMappingPort, "groupPermissionMappingPort is null");
        this.assembler = Objects.requireNonNull(assembler, "assembler is null");
    }

    public PagedResponse<PermissionInfoResponse> list(final UiPageRequest pageRequest) {
        return assembler.toPermissionPage(permissionCrudPort.findAll(pageRequest));
    }

    public Optional<PermissionInfoResponse> findByPermId(final long permId) {
        return permissionCrudPort.findByPermId(permId)
                .map(assembler::toPermissionInfoResponse);
    }

    @Transactional
    public PermissionInfoResponse create(final PermissionUpsertRequest request) {
        final UiPermission saved = permissionCrudPort.upsert(assembler.toCreateCommand(request));
        ensureAdminPermissionMapping(saved);
        return assembler.toPermissionInfoResponse(saved);
    }

    @Transactional
    public Optional<PermissionInfoResponse> update(final long permId, final PermissionUpsertRequest request) {
        return permissionCrudPort.findByPermId(permId)
                .map(existing -> {
                    final UiPermission saved = permissionCrudPort.upsert(
                            assembler.toUpdateCommand(existing.permId(), request)
                    );
                    ensureAdminPermissionMapping(saved);
                    return assembler.toPermissionInfoResponse(saved);
                });
    }

    public void delete(final long permId) {
        permissionCrudPort.deleteByPermId(permId);
    }

    private void ensureAdminPermissionMapping(final UiPermission permission) {
        groupCrudPort.findByGroupCode(ADMIN_GROUP_CODE).ifPresentOrElse(
                adminGroup -> groupPermissionMappingPort.upsert(new UiUserGroupPermissionUpsertCommand(
                        adminGroup.groupId(),
                        permission.permId(),
                        OffsetDateTime.now(),
                        resolveGrantedBy(permission)
                )),
                () -> log.warn("ADMIN 그룹이 없어 권한 자동 매핑을 건너뜁니다. permId={}, permCode={}",
                        permission.permId(), permission.permCode())
        );
    }

    private static String resolveGrantedBy(final UiPermission permission) {
        return Optional.ofNullable(permission.updatedBy())
                .filter(value -> !value.isBlank())
                .or(() -> Optional.ofNullable(permission.createdBy()).filter(value -> !value.isBlank()))
                .orElse(DEFAULT_GRANTED_BY);
    }
}

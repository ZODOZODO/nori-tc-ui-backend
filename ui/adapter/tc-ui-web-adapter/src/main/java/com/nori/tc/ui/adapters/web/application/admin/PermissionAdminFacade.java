package com.nori.tc.ui.adapters.web.application.admin;

import com.nori.tc.ui.adapters.web.dto.request.PermissionUpsertRequest;
import com.nori.tc.ui.adapters.web.dto.response.PermissionInfoResponse;
import com.nori.tc.ui.core.model.PagedResponse;
import com.nori.tc.ui.core.model.UiPageRequest;
import com.nori.tc.ui.core.port.db.PermissionCrudPort;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;

/**
 * Permission 관리 use-case를 controller 밖으로 이동시키는 facade 입니다.
 */
@Component
public class PermissionAdminFacade {

    private final PermissionCrudPort permissionCrudPort;
    private final PermissionAdminAssembler assembler;

    public PermissionAdminFacade(
            final PermissionCrudPort permissionCrudPort,
            final PermissionAdminAssembler assembler
    ) {
        this.permissionCrudPort = Objects.requireNonNull(permissionCrudPort, "permissionCrudPort is null");
        this.assembler = Objects.requireNonNull(assembler, "assembler is null");
    }

    public PagedResponse<PermissionInfoResponse> list(final UiPageRequest pageRequest) {
        return assembler.toPermissionPage(permissionCrudPort.findAll(pageRequest));
    }

    public Optional<PermissionInfoResponse> findByPermId(final long permId) {
        return permissionCrudPort.findByPermId(permId)
                .map(assembler::toPermissionInfoResponse);
    }

    public PermissionInfoResponse create(final PermissionUpsertRequest request) {
        return assembler.toPermissionInfoResponse(
                permissionCrudPort.upsert(assembler.toCreateCommand(request))
        );
    }

    public Optional<PermissionInfoResponse> update(final long permId, final PermissionUpsertRequest request) {
        return permissionCrudPort.findByPermId(permId)
                .map(existing -> assembler.toPermissionInfoResponse(
                        permissionCrudPort.upsert(assembler.toUpdateCommand(existing.permId(), request))
                ));
    }

    public void delete(final long permId) {
        permissionCrudPort.deleteByPermId(permId);
    }
}

package com.nori.tc.ui.adapters.web.application.admin;

import com.nori.tc.ui.adapters.web.dto.request.PermissionUpsertRequest;
import com.nori.tc.ui.adapters.web.dto.response.PermissionInfoResponse;
import com.nori.tc.ui.core.model.PagedResponse;
import com.nori.tc.ui.core.model.UiPermissionUpsertCommand;
import com.nori.tc.ui.domain.useradmin.UiPermission;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Permission 관리 요청/응답 변환을 담당합니다.
 */
@Component
public class PermissionAdminAssembler {

    public UiPermissionUpsertCommand toCreateCommand(final PermissionUpsertRequest request) {
        return toCommand(null, request);
    }

    public UiPermissionUpsertCommand toUpdateCommand(final long permId, final PermissionUpsertRequest request) {
        return toCommand(permId, request);
    }

    public PagedResponse<PermissionInfoResponse> toPermissionPage(final PagedResponse<UiPermission> page) {
        final List<PermissionInfoResponse> items = page.items().stream()
                .map(this::toPermissionInfoResponse)
                .toList();
        return PagedResponse.of(items, page.offset(), page.limit(), page.count());
    }

    public PermissionInfoResponse toPermissionInfoResponse(final UiPermission permission) {
        return new PermissionInfoResponse(
                permission.permId(),
                permission.permCode(),
                permission.permName(),
                permission.resourceType(),
                permission.matchType(),
                permission.resource(),
                permission.httpMethod(),
                permission.description(),
                permission.isActive(),
                permission.createdAt(),
                permission.updatedAt(),
                permission.createdBy(),
                permission.updatedBy()
        );
    }

    private UiPermissionUpsertCommand toCommand(
            final Long permId,
            final PermissionUpsertRequest request
    ) {
        return new UiPermissionUpsertCommand(
                permId,
                request.permCode(),
                request.permName(),
                request.resourceType(),
                request.matchType(),
                request.resource(),
                normalizeHttpMethod(request.httpMethod()).orElse(null),
                request.description(),
                request.isActive() == null || request.isActive(),
                request.createdBy(),
                request.updatedBy()
        );
    }

    private static Optional<String> normalizeHttpMethod(final String httpMethod) {
        if (httpMethod == null || httpMethod.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(httpMethod.trim().toUpperCase(Locale.ROOT));
    }
}

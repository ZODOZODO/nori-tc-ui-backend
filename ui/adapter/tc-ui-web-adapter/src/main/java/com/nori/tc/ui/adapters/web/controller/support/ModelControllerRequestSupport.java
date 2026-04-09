package com.nori.tc.ui.adapters.web.controller.support;

import com.nori.tc.ui.adapters.web.dto.request.ModelDetailSaveRequest;
import com.nori.tc.ui.core.exception.UiBadRequestException;
import com.nori.tc.ui.domain.auth.UserPrincipal;
import com.nori.tc.ui.domain.model.UiModel;
import org.slf4j.Logger;
import org.springframework.security.core.Authentication;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * ModelController 요청 전처리와 입력 정규화 보조 유틸리티입니다.
 */
public final class ModelControllerRequestSupport {

    private static final String EDIT_MODEL_VERSION = "EDIT";
    private static final String DEFAULT_MDF_NAME = "MDF";
    private static final int SECS_WORKFLOW_FILTER_VALUE_INDEX = 4;
    private static final int SECS_ACTION_DATA_INDEX_VALUE_INDEX = 6;

    private ModelControllerRequestSupport() {
    }

    /**
     * workflow 상세 저장 시 row 단위 JSON 계약을 사전 검증합니다.
     */
    public static String validateWorkflowJsonRows(
            final Logger log,
            final String detailNode,
            final List<ModelDetailSaveRequest.ModelDetailSaveRowItem> rows,
            final boolean isSocketWorkflow
    ) {
        if (!"workflow".equals(detailNode) || rows == null || rows.isEmpty()) {
            return null;
        }

        // SOCKET workflow도 저장 전에는 7개 canonical values로 확장되므로 SECS 인덱스를 사용합니다.
        final int filterIndex = SECS_WORKFLOW_FILTER_VALUE_INDEX;
        final int dataIndexIndex = SECS_ACTION_DATA_INDEX_VALUE_INDEX;

        for (int index = 0; index < rows.size(); index++) {
            final ModelDetailSaveRequest.ModelDetailSaveRowItem row = rows.get(index);

            final String workflowFilter = valueAt(row.values(), filterIndex);
            try {
                ModelDetailWorkflowJsonSupport.validateWorkflowFilter(workflowFilter);
            } catch (IllegalArgumentException exception) {
                final String message = "workflow " + (index + 1) + "행의 workflow_filter가 올바르지 않습니다. "
                        + exception.getMessage();
                log.warn("workflow_filter 저장 검증 실패. detailNode={}, rowIndex={}, rowId={}, socketWorkflow={}, reason={}",
                        detailNode, index, row.id(), isSocketWorkflow, exception.getMessage());
                return message;
            }

            final String actionDataIndex = valueAt(row.values(), dataIndexIndex);
            try {
                ModelDetailWorkflowJsonSupport.validateActionDataIndex(actionDataIndex);
            } catch (IllegalArgumentException exception) {
                final String message = "workflow " + (index + 1) + "행의 action_data_index가 올바르지 않습니다. "
                        + exception.getMessage();
                log.warn("action_data_index 저장 검증 실패. detailNode={}, rowIndex={}, rowId={}, socketWorkflow={}, reason={}",
                        detailNode, index, row.id(), isSocketWorkflow, exception.getMessage());
                return message;
            }
        }
        return null;
    }

    /**
     * 모델이 SOCKET 통신 인터페이스를 사용하는지 확인합니다.
     */
    public static boolean isSocketModel(final UiModel model) {
        return model != null
                && model.commInterface() != null
                && "SOCKET".equalsIgnoreCase(model.commInterface().name());
    }

    /**
     * 상세 저장/업로드는 EDIT version에서만 허용합니다.
     */
    public static boolean isEditVersion(final UiModel model) {
        return model != null && EDIT_MODEL_VERSION.equalsIgnoreCase(nullToEmpty(model.modelVersion()));
    }

    /**
     * SOCKET 모델의 workflow values(5개)를 DB 저장 계층이 기대하는 7개 형식으로 확장합니다.
     */
    public static List<ModelDetailSaveRequest.ModelDetailSaveRowItem> expandSocketWorkflowValues(
            final List<ModelDetailSaveRequest.ModelDetailSaveRowItem> rows
    ) {
        if (rows == null) {
            return null;
        }
        return rows.stream()
                .map(row -> {
                    final List<String> original = row.values() == null ? List.of() : row.values();
                    final List<String> expanded = List.of(
                            getOrEmpty(original, 0),
                            getOrEmpty(original, 1),
                            "",
                            "",
                            getOrEmpty(original, 2),
                            getOrEmpty(original, 3),
                            getOrEmpty(original, 4)
                    );
                    return new ModelDetailSaveRequest.ModelDetailSaveRowItem(row.id(), expanded);
                })
                .toList();
    }

    /**
     * SOCKET 모델의 dcop item values(6개)를 DB 저장 계층이 기대하는 7개 형식으로 확장합니다.
     */
    public static List<ModelDetailSaveRequest.ModelDetailSaveRowItem> expandSocketDcopItemValues(
            final List<ModelDetailSaveRequest.ModelDetailSaveRowItem> rows
    ) {
        if (rows == null) {
            return null;
        }
        return rows.stream()
                .map(row -> {
                    final List<String> original = row.values() == null ? List.of() : row.values();
                    final List<String> expanded = List.of(
                            getOrEmpty(original, 0),
                            getOrEmpty(original, 1),
                            "",
                            getOrEmpty(original, 2),
                            getOrEmpty(original, 3),
                            getOrEmpty(original, 4),
                            getOrEmpty(original, 5)
                    );
                    return new ModelDetailSaveRequest.ModelDetailSaveRowItem(row.id(), expanded);
                })
                .toList();
    }

    /**
     * detailNode path 값을 공통 규칙으로 정규화합니다.
     */
    public static String normalizeDetailNode(final String detailNode) {
        return detailNode == null ? "" : detailNode.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * multipart 업로드 바이트를 읽고 입력 오류를 정규화합니다.
     */
    public static byte[] readUploadedBytes(final MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new UiBadRequestException("업로드할 MDF 파일이 비어 있습니다.");
        }

        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new UiBadRequestException("업로드한 MDF 파일을 읽을 수 없습니다.", e);
        }
    }

    /**
     * 업로드 시 사용할 MDF 이름을 결정합니다.
     */
    public static String resolveUploadMdfName(
            final String requestedMdfName,
            final String originalFilename,
            final String existingMdfName
    ) {
        final String explicitName = normalizeOptionalText(requestedMdfName);
        if (explicitName != null) {
            return explicitName;
        }

        final String fileBasedName = extractBaseName(originalFilename);
        if (fileBasedName != null) {
            return fileBasedName;
        }

        final String normalizedExistingName = normalizeOptionalText(existingMdfName);
        if (normalizedExistingName != null) {
            return normalizedExistingName;
        }

        return DEFAULT_MDF_NAME;
    }

    /**
     * 인증 정보에서 현재 사용자 ID를 추출합니다.
     */
    public static String resolveCurrentUser(final Authentication authentication) {
        if (authentication == null) {
            return "SYSTEM";
        }
        if (authentication.getPrincipal() instanceof UserPrincipal principal
                && principal.userId() != null
                && !principal.userId().isBlank()) {
            return principal.userId();
        }
        if (authentication.getName() == null || authentication.getName().isBlank()) {
            return "SYSTEM";
        }
        return authentication.getName();
    }

    private static String valueAt(final List<String> values, final int index) {
        if (values == null || index < 0 || index >= values.size()) {
            return null;
        }
        return values.get(index);
    }

    private static String getOrEmpty(final List<String> list, final int index) {
        if (list == null || index < 0 || index >= list.size()) {
            return "";
        }
        final String value = list.get(index);
        return value == null ? "" : value;
    }

    private static String normalizeOptionalText(final String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static String extractBaseName(final String filename) {
        final String normalizedFilename = normalizeOptionalText(filename);
        if (normalizedFilename == null) {
            return null;
        }

        final int lastSlash = Math.max(normalizedFilename.lastIndexOf('/'), normalizedFilename.lastIndexOf('\\'));
        final String simpleName = lastSlash >= 0 ? normalizedFilename.substring(lastSlash + 1) : normalizedFilename;
        final int extensionIndex = simpleName.lastIndexOf('.');
        if (extensionIndex <= 0) {
            return simpleName;
        }
        return simpleName.substring(0, extensionIndex);
    }

    private static String nullToEmpty(final String value) {
        return value == null ? "" : value;
    }
}

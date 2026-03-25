package com.nori.tc.ui.adapters.web.controller.support;

/**
 * workflow JSON 입력 정규화를 담당합니다.
 */
final class ModelDetailWorkflowJsonNormalizer {

    private ModelDetailWorkflowJsonNormalizer() {
    }

    static String normalizeOptionalText(final String value) {
        if (value == null) {
            return null;
        }
        final String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    static String normalizeFirstLine(final String value) {
        if (value == null || value.isBlank()) {
            return "";
        }

        final String[] lines = value.split("\\R");
        for (String line : lines) {
            final String normalized = collapseWhitespace(line);
            if (!normalized.isEmpty()) {
                return normalized;
            }
        }
        return collapseWhitespace(value);
    }

    static String collapseWhitespace(final String value) {
        if (value == null) {
            return "";
        }
        return value.trim().replaceAll("\\s+", " ");
    }
}

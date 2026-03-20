package com.nori.tc.ui.core.deploy;

import java.util.Objects;

/**
 * deploy API 응답용 메시지입니다.
 */
public record DeployResultMessage(
        String title,
        String message,
        DeployResultSeverity severity
) {

    public DeployResultMessage {
        title = requireText("title", title);
        message = requireText("message", message);
        severity = Objects.requireNonNull(severity, "severity is null");
    }

    private static String requireText(final String fieldName, final String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}

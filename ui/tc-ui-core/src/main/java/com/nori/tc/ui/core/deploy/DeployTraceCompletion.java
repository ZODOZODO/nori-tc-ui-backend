package com.nori.tc.ui.core.deploy;

import com.nori.tc.ui.core.model.UiCommandReply;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * traceId 기준 reply 집계 완료 결과입니다.
 */
public record DeployTraceCompletion(
        String traceId,
        Set<String> expectedSources,
        List<UiCommandReply> replies
) {

    public DeployTraceCompletion {
        traceId = requireText("traceId", traceId);
        expectedSources = Set.copyOf(Objects.requireNonNull(expectedSources, "expectedSources is null"));
        replies = List.copyOf(Objects.requireNonNull(replies, "replies is null"));
    }

    public boolean success() {
        return replies.stream().allMatch(UiCommandReply::isSuccess);
    }

    public String firstErrorCode() {
        return replies.stream()
                .filter(UiCommandReply::isFailed)
                .map(UiCommandReply::errorCode)
                .filter(code -> code != null && !code.isBlank())
                .findFirst()
                .orElse(null);
    }

    public String firstErrorMessage() {
        return replies.stream()
                .filter(UiCommandReply::isFailed)
                .map(UiCommandReply::errorMsg)
                .filter(message -> message != null && !message.isBlank())
                .findFirst()
                .orElse(null);
    }

    private static String requireText(final String fieldName, final String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}

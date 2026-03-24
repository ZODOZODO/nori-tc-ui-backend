package com.nori.tc.ui.domain.model;

import com.nori.tc.ui.domain.common.model.UiModelStatus;
import com.nori.tc.ui.domain.common.model.UiProtocolType;

import java.time.OffsetDateTime;

/**
 * UI Backend 내부에서 사용하는 모델 스냅샷입니다.
 */
public record UiModel(
        long modelVersionKey,
        long modelKey,
        String modelName,
        String parentModel,
        String modelVersion,
        UiProtocolType commInterface,
        UiModelStatus status,
        String description,
        String maker,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        String createdBy,
        String updatedBy
) {
}

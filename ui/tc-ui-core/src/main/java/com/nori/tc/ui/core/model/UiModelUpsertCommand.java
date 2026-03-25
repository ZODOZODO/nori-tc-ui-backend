package com.nori.tc.ui.core.model;

import com.nori.tc.ui.domain.common.model.UiModelStatus;
import com.nori.tc.ui.domain.common.model.UiProtocolType;

/**
 * 모델 저장/수정용 UI Core 명령입니다.
 */
public record UiModelUpsertCommand(
        Long modelVersionKey,
        String modelName,
        String parentModel,
        String modelVersion,
        UiProtocolType commInterface,
        UiModelStatus status,
        String description,
        String maker,
        String createdBy,
        String updatedBy
) {
}

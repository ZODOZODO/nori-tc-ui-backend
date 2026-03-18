package com.nori.tc.ui.adapters.web.dto.response;

import com.nori.tc.db.domain.common.model.ModelStatus;
import com.nori.tc.db.domain.common.model.ProtocolType;

import java.time.OffsetDateTime;

/**
 * 모델 조회 응답 DTO입니다.
 *
 * @param modelVersionKey 모델 버전 키
 * @param modelKey 모델 키
 * @param modelName 모델 이름
 * @param parentModel 부모 모델 이름
 * @param modelVersion 모델 버전
 * @param commInterface 통신 인터페이스
 * @param status 모델 상태
 * @param description 모델 버전 설명
 * @param maker 제조사
 * @param createdAt 생성 시각
 * @param updatedAt 수정 시각
 * @param createdBy 생성자
 * @param updatedBy 수정자
 */
public record ModelInfoResponse(
        long modelVersionKey,
        long modelKey,
        String modelName,
        String parentModel,
        String modelVersion,
        ProtocolType commInterface,
        ModelStatus status,
        String description,
        String maker,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        String createdBy,
        String updatedBy
) {
}

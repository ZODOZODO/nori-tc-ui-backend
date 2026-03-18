package com.nori.tc.ui.adapters.web.dto.response;

import com.nori.tc.db.domain.common.model.ProtocolType;

import java.time.OffsetDateTime;

/**
 * 설비 조회 응답 DTO입니다.
 *
 * <p>대상 엔드포인트:</p>
 * <ul>
 *   <li>GET /api/eqp</li>
 *   <li>GET /api/eqp/{eqpId}</li>
 * </ul>
 *
 * @param eqpKey 설비 PK
 * @param eqpId 설비 비즈니스 ID
 * @param commInterface 통신 인터페이스 타입
 * @param commMode 통신 모드
 * @param isDev 개발 장비 여부
 * @param routePartition Gateway 라우팅 파티션
 * @param eqpIp 설비 IP
 * @param eqpPort 설비 포트
 * @param modelVersionKey 연결 모델 버전 키
 * @param enabled 사용 여부
 * @param createdAt 생성 시각
 * @param updatedAt 수정 시각
 * @param createdBy 생성자
 * @param updatedBy 수정자
 */
public record EqpInfoResponse(
        Long eqpKey,
        String eqpId,
        ProtocolType commInterface,
        String commMode,
        boolean isDev,
        Integer routePartition,
        String eqpIp,
        int eqpPort,
        long modelVersionKey,
        boolean enabled,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        String createdBy,
        String updatedBy
) {
}

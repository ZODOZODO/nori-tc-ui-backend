package com.nori.tc.ui.core.commlog;

/**
 * 설비별 통신 로그 조회 결과 DTO입니다.
 *
 * <p>Redis에 저장된 {@code RedisCommLogEntry}를 UI 레이어로 전달할 때 사용합니다.
 * tc-ui-core는 tc-db-domain에 의존할 수 없으므로 별도 DTO로 분리합니다.</p>
 */
public record CommLogEntry(
        String eqpId,
        long timestampMs,
        String direction,
        String source,
        String messageName,
        String payload
) {
}

package com.nori.tc.ui.core.port.redis;

import com.nori.tc.ui.core.commlog.CommLogEntry;

import java.util.List;

/**
 * 설비별 통신 로그를 Redis에서 조회하는 포트입니다.
 *
 * <p>구현체:</p>
 * <ul>
 *   <li>tc-ui-redis-adapter의 CommLogRedisQueryAdapter</li>
 * </ul>
 */
public interface CommLogQueryPort {

    /**
     * 설비의 최근 1시간 설비 통신 로그를 오름차순으로 반환합니다.
     *
     * @param eqpId 설비 식별자
     * @return 시간순 정렬된 설비 통신 로그 목록 (비어 있을 수 있음)
     */
    List<CommLogEntry> findEqpLogs(String eqpId);

    /**
     * 설비의 최근 1시간 MES 통신 로그를 오름차순으로 반환합니다.
     *
     * @param eqpId 설비 식별자
     * @return 시간순 정렬된 MES 통신 로그 목록 (비어 있을 수 있음)
     */
    List<CommLogEntry> findMesLogs(String eqpId);
}

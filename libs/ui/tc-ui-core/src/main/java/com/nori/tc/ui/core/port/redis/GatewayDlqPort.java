package com.nori.tc.ui.core.port.redis;

import java.util.List;

/**
 * Gateway Redis DLQ(Dead Letter Queue) 조회·삭제 포트입니다.
 *
 * <p>역할:</p>
 * <p>Gateway Redis(6379)에 저장된 DLQ 항목을 조회하고 삭제합니다.
 * DLQ 항목 쓰기는 tc-comm-gateway-redis-adapter의 RedisDlqPublisher가 담당합니다.</p>
 *
 * <p>DLQ 키 패턴:</p>
 * <pre>tc:comm:gateway:dlq:{dlqId}</pre>
 *
 * <p>구현체:</p>
 * <p>tc-ui-redis-adapter의 GatewayDlqRedisService가 이 인터페이스를 구현합니다.</p>
 *
 * <p>반환 타입 {@code List<?>}:</p>
 * <p>구현체는 Gateway 고유의 {@code RedisDlqEntry} 타입을 반환합니다.
 * 웹 어댑터는 Jackson 직렬화를 통해 타입에 무관하게 JSON으로 변환합니다.
 * 반환 타입을 특정 어댑터 타입에 종속시키지 않기 위해 와일드카드를 사용합니다.</p>
 */
public interface GatewayDlqPort {

    /**
     * Gateway DLQ에 저장된 모든 항목을 조회합니다.
     *
     * @return DLQ 항목 목록 (비어 있을 수 있음, 오류 시 빈 리스트)
     */
    List<?> listAllDlq();

    /**
     * 지정한 dlqId에 해당하는 Gateway DLQ 항목을 삭제합니다.
     *
     * @param dlqId 삭제할 DLQ 항목 ID
     * @return 삭제 성공 여부 (키가 존재하지 않으면 false)
     */
    boolean deleteDlq(String dlqId);
}

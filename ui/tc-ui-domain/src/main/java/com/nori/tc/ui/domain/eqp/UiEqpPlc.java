package com.nori.tc.ui.domain.eqp;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * UI Backend 내부에서 사용하는 PLC 상세 설정입니다.
 *
 * <p>tc_eqp_plc 1행에 대응하며, IP/포트는 tc_eqp 공통 컬럼을 재사용합니다(SECS/SOCKET 동일).
 * {@code plcProtocol}/{@code transport} 는 socketProtocolType 선례처럼 String 으로 운반하고,
 * 공통 enum({@code PlcProtocol}/{@code PlcTransport}) 변환은 영속 경계(JpaEqpCrudPort)에서 수행합니다.
 * {@code enabledPollGroups} 는 DB varchar JSON 을 codec 으로 디코드한 List 입니다.</p>
 */
public record UiEqpPlc(
        long eqpKey,
        String plcProtocol,
        String transport,
        int unitId,
        int pollTimeoutMs,
        int reconnectMaxRetries,
        List<String> enabledPollGroups,
        long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {

    public UiEqpPlc {
        enabledPollGroups = enabledPollGroups == null ? List.of() : List.copyOf(enabledPollGroups);
    }
}

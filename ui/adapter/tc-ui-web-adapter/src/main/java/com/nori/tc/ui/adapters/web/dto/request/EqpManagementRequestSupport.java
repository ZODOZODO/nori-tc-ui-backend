package com.nori.tc.ui.adapters.web.dto.request;

import com.nori.tc.db.domain.common.eqp.LogLevel;

/**
 * EQP 관리 요청 DTO에서 공통으로 사용하는 중첩 설정 모델 모음입니다.
 */
public final class EqpManagementRequestSupport {

    private EqpManagementRequestSupport() {
        // 정적 중첩 record만 사용합니다.
    }

    /**
     * 로그 정책 요청 DTO입니다.
     *
     * @param logLevel 로그 레벨
     * @param logRetentionDays 로그 보관 일수
     * @param logPath 로그 경로
     */
    public record LogSettingsRequest(
            LogLevel logLevel,
            Integer logRetentionDays,
            String logPath
    ) {
    }

    /**
     * SECS 전용 설정 요청 DTO입니다.
     *
     * @param deviceId device id
     * @param t3Timeout t3 timeout
     * @param t5Timeout t5 timeout
     * @param t6Timeout t6 timeout
     * @param t7Timeout t7 timeout
     * @param t8Timeout t8 timeout
     * @param linkTestEnabled link test 사용 여부
     * @param linkTestInterval link test 주기
     * @param maxMsgBytes 최대 메시지 바이트
     */
    public record HsmsSettingsRequest(
            Integer deviceId,
            Integer t3Timeout,
            Integer t5Timeout,
            Integer t6Timeout,
            Integer t7Timeout,
            Integer t8Timeout,
            Boolean linkTestEnabled,
            Integer linkTestInterval,
            Long maxMsgBytes
    ) {
    }

    /**
     * SOCKET 전용 설정 요청 DTO입니다.
     *
     * @param socketProtocolType 소켓 프로토콜 타입
     * @param charset 문자셋
     * @param heartbeatEnabled heartbeat 사용 여부
     * @param heartbeatInterval heartbeat 주기
     * @param readTimeout read timeout
     * @param writeTimeout write timeout
     * @param maxFrameSizeBytes 최대 frame size
     * @param keepAliveEnabled keep alive 사용 여부
     */
    public record SocketSettingsRequest(
            String socketProtocolType,
            String charset,
            Boolean heartbeatEnabled,
            Integer heartbeatInterval,
            Integer readTimeout,
            Integer writeTimeout,
            Integer maxFrameSizeBytes,
            Boolean keepAliveEnabled
    ) {
    }
}

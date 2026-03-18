package com.nori.tc.ui.core.eqp;

import com.nori.tc.db.domain.common.eqp.LogLevel;
import com.nori.tc.db.domain.common.model.ProtocolType;

/**
 * EQP 관리 생성/수정 명령 모델입니다.
 *
 * <p>웹 DTO와 DB 저장 포트 사이에서 사용할 기술 중립 명령 객체만 정의합니다.</p>
 */
public final class EqpManagementCommand {

    private EqpManagementCommand() {
        // 정적 중첩 record만 사용합니다.
    }

    /**
     * EQP 로그 정책 입력입니다.
     *
     * @param logLevel 로그 레벨
     * @param logRetentionDays 로그 보관 일수
     * @param logPath 로그 저장 경로
     */
    public record LogSettings(
            LogLevel logLevel,
            Integer logRetentionDays,
            String logPath
    ) {
    }

    /**
     * SECS 전용 설정 입력입니다.
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
    public record HsmsSettings(
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
     * SOCKET 전용 설정 입력입니다.
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
    public record SocketSettings(
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

    /**
     * EQP 생성 명령입니다.
     *
     * @param actor 요청 사용자
     * @param eqpId eqp id
     * @param interfaceType 통신 인터페이스
     * @param commMode 통신 모드
     * @param isDev 개발 장비 여부
     * @param routePartition route partition
     * @param eqpIp 설비 ip
     * @param eqpPort 설비 port
     * @param modelVersionKey 연결 model version key
     * @param appliedParamVersion 현재 적용 파라미터 버전
     * @param gatewayJarFileName gateway jar 파일명
     * @param businessJarFileName business jar 파일명
     * @param logSettings 로그 정책
     * @param hsmsSettings SECS 설정
     * @param socketSettings SOCKET 설정
     */
    public record Create(
            String actor,
            String eqpId,
            ProtocolType interfaceType,
            String commMode,
            boolean isDev,
            Integer routePartition,
            String eqpIp,
            Integer eqpPort,
            long modelVersionKey,
            String appliedParamVersion,
            String gatewayJarFileName,
            String businessJarFileName,
            LogSettings logSettings,
            HsmsSettings hsmsSettings,
            SocketSettings socketSettings
    ) {
    }

    /**
     * EQP 수정 명령입니다.
     *
     * @param actor 요청 사용자
     * @param commMode 통신 모드
     * @param isDev 개발 장비 여부
     * @param routePartition route partition
     * @param eqpIp 설비 ip
     * @param eqpPort 설비 port
     * @param modelVersionKey 연결 model version key
     * @param appliedParamVersion 현재 적용 파라미터 버전
     * @param gatewayJarFileName gateway jar 파일명
     * @param businessJarFileName business jar 파일명
     * @param logSettings 로그 정책
     * @param hsmsSettings SECS 설정
     * @param socketSettings SOCKET 설정
     */
    public record Update(
            String actor,
            String commMode,
            boolean isDev,
            Integer routePartition,
            String eqpIp,
            Integer eqpPort,
            long modelVersionKey,
            String appliedParamVersion,
            String gatewayJarFileName,
            String businessJarFileName,
            LogSettings logSettings,
            HsmsSettings hsmsSettings,
            SocketSettings socketSettings
    ) {
    }
}

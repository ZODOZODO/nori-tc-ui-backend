package com.nori.tc.ui.adapters.web.dto.request;

import com.nori.tc.ui.domain.common.model.UiProtocolType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * EQP 생성 요청 DTO입니다.
 *
 * <p>T2 설계 기준에 따라 공통 필드 + 프로토콜별 상세 설정을 함께 전달합니다.</p>
 *
 * @param eqpId 설비 ID
 * @param lineKey 설비 소속 라인 키(tc_line.line_key, 필수)
 * @param interfaceType 통신 인터페이스 타입
 * @param commMode 통신 모드
 * @param isDev 개발 장비 여부
 * @param routePartition route partition
 * @param eqpIp 설비 IP
 * @param eqpPort 설비 port
 * @param modelVersionKey 연결 model version key
 * @param gatewayJarRef gateway jar 참조 키
 * @param businessJarRef business jar 참조 키
 * @param logSettings 로그 정책
 * @param hsmsSettings SECS 설정
 * @param socketSettings SOCKET 설정
 */
public record EqpCreateRequest(

        @NotBlank(message = "eqpId는 필수입니다.")
        String eqpId,

        @NotNull(message = "lineKey는 필수입니다.")
        @Positive(message = "lineKey는 1 이상이어야 합니다.")
        Long lineKey,

        @NotNull(message = "interfaceType은 필수입니다.")
        UiProtocolType interfaceType,

        @NotBlank(message = "commMode는 필수입니다.")
        String commMode,

        @NotNull(message = "isDev는 필수입니다.")
        Boolean isDev,

        @NotNull(message = "routePartition은 필수입니다.")
        @Min(value = 0, message = "routePartition은 0 이상이어야 합니다.")
        Integer routePartition,

        @NotBlank(message = "eqpIp는 필수입니다.")
        String eqpIp,

        @NotNull(message = "eqpPort는 필수입니다.")
        @Positive(message = "eqpPort는 1 이상이어야 합니다.")
        Integer eqpPort,

        @NotNull(message = "modelVersionKey는 필수입니다.")
        @Positive(message = "modelVersionKey는 1 이상이어야 합니다.")
        Long modelVersionKey,

        @Positive(message = "gatewayJarRef는 1 이상이어야 합니다.")
        Long gatewayJarRef,

        @Positive(message = "businessJarRef는 1 이상이어야 합니다.")
        Long businessJarRef,

        @Valid
        EqpManagementRequestSupport.LogSettingsRequest logSettings,

        @Valid
        EqpManagementRequestSupport.HsmsSettingsRequest hsmsSettings,

        @Valid
        EqpManagementRequestSupport.SocketSettingsRequest socketSettings
) {
}

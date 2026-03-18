package com.nori.tc.ui.adapters.web.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * EQP 수정 요청 DTO입니다.
 *
 * <p>수정 가능 필드만 노출하며, eqpId와 commInterface는 경로/기존 스냅샷으로 고정합니다.</p>
 *
 * @param commMode 통신 모드
 * @param isDev 개발 장비 여부
 * @param routePartition route partition
 * @param eqpIp 설비 IP
 * @param eqpPort 설비 port
 * @param modelVersionKey 연결 model version key
 * @param appliedParamVersion 적용 파라미터 버전
 * @param gatewayJarFileName gateway jar 파일명
 * @param businessJarFileName business jar 파일명
 * @param logSettings 로그 정책
 * @param hsmsSettings SECS 설정
 * @param socketSettings SOCKET 설정
 */
public record EqpUpdateRequest(

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

        @Size(max = 100, message = "appliedParamVersion은 100자 이하여야 합니다.")
        String appliedParamVersion,

        String gatewayJarFileName,

        String businessJarFileName,

        @Valid
        EqpManagementRequestSupport.LogSettingsRequest logSettings,

        @Valid
        EqpManagementRequestSupport.HsmsSettingsRequest hsmsSettings,

        @Valid
        EqpManagementRequestSupport.SocketSettingsRequest socketSettings
) {
}

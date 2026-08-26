package com.nori.tc.ui.adapters.web.dto.response;

import java.util.List;

/**
 * Business Pod 별 partition 할당 응답 DTO입니다.
 *
 * @param host Pod 의 접속 호스트(Kubernetes 에서는 Pod IP). 알 수 없으면 null.
 * @param memberIds 이 Pod 에 속한 consumer group 멤버 식별자 목록
 * @param ownedPartitions 이 Pod 이 담당하는 설비 이벤트 토픽 partition 목록(오름차순)
 */
public record BusinessAppAssignmentResponse(
        String host,
        List<String> memberIds,
        List<Integer> ownedPartitions
) {
}

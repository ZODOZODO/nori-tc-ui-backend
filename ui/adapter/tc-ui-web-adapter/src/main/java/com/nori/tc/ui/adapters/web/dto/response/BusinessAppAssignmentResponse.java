package com.nori.tc.ui.adapters.web.dto.response;

import java.util.List;

/**
 * Business 프로세스별 partition 할당 응답 DTO입니다.
 *
 * @param memberId consumer group 멤버 식별자
 * @param host 멤버가 접속한 호스트. 알 수 없으면 null.
 * @param ownedPartitions 이 멤버가 담당하는 설비 이벤트 토픽 partition 목록(오름차순)
 */
public record BusinessAppAssignmentResponse(
        String memberId,
        String host,
        List<Integer> ownedPartitions
) {
}

package com.nori.tc.ui.core.port.messaging;

import java.util.List;

/**
 * 살아 있는 Business 프로세스와 각 프로세스가 담당하는 route partition 을 조회하는 포트입니다.
 *
 * <p>Business 는 설비 이벤트 토픽을 consumer group 으로 나눠 소비하므로, "지금 몇 개의 business
 * 프로세스가 떠 있고 각각 어느 partition 을 맡고 있는가"의 정답은 consumer group 의 멤버 할당입니다.
 * UI 설비 화면은 이 결과로 설비를 business_app 단위로 분류합니다 — Gateway 를 shard lease 로
 * 분류하는 것({@code GatewayShardLeasePort})과 같은 축입니다.</p>
 *
 * <p>replica 수(예: Deployment replicas)를 쓰지 않는 이유: 기동 중이거나 group 에서 빠진 Pod 는
 * 실제로 아무 partition 도 처리하지 않는데, replica 수로 세면 그 Pod 앞으로 설비가 배정된 것처럼
 * 보입니다. 처리 주체의 진실은 group 할당입니다.</p>
 */
public interface BusinessAppAssignmentPort {

    /**
     * Business consumer group 의 현재 멤버별 partition 할당을 조회합니다.
     *
     * @return 담당 partition 최솟값 오름차순으로 정렬된 멤버 목록.
     *         group 이 없거나(미기동) 멤버가 없으면 빈 목록.
     */
    List<BusinessAppAssignmentView> findAllAssignments();

    /**
     * 단일 Business 프로세스(consumer group 멤버)의 할당 현황입니다.
     *
     * @param memberId consumer group 멤버 식별자(재조인마다 바뀔 수 있음 — 표시용이 아니라 식별용)
     * @param host 멤버가 접속한 호스트. 알 수 없으면 null.
     * @param ownedPartitions 이 멤버가 담당하는 설비 이벤트 토픽의 partition 목록(오름차순)
     */
    record BusinessAppAssignmentView(
            String memberId,
            String host,
            List<Integer> ownedPartitions
    ) {
    }
}

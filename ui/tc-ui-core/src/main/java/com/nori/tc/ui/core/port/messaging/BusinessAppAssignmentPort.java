package com.nori.tc.ui.core.port.messaging;

import java.util.List;

/**
 * 살아 있는 Business 프로세스와 각 프로세스가 담당하는 route partition 을 조회하는 포트입니다.
 *
 * <p>Business 는 설비 이벤트 토픽을 consumer group 으로 나눠 소비하므로, "지금 어느 partition 을 누가
 * 맡고 있는가"의 정답은 consumer group 의 멤버 할당입니다. UI 설비 화면은 이 결과로 설비를
 * business_app 단위로 분류합니다 — Gateway 를 shard lease 로 분류하는 것
 * ({@code GatewayShardLeasePort})과 같은 축입니다.</p>
 *
 * <p><b>단위는 멤버가 아니라 Pod 입니다.</b> Business Pod 하나는 토픽마다 listener 컨테이너를 따로
 * 띄우므로 consumer group 멤버가 Pod 수보다 많습니다(라이브 실측: Pod 2 대 · 멤버 8 개 · 토픽 4 종).
 * 멤버 하나를 business_app 하나로 세면 담당 partition 이 없는 다른 토픽의 멤버까지 별개 프로세스로
 * 보이므로, 같은 host 의 멤버를 Pod 하나로 합칩니다.</p>
 *
 * <p>replica 수(예: Deployment replicas)를 쓰지 않는 이유: 기동 중이거나 group 에서 빠진 Pod 는
 * 실제로 아무 partition 도 처리하지 않는데, replica 수로 세면 그 Pod 앞으로 설비가 배정된 것처럼
 * 보입니다. 처리 주체의 진실은 group 할당입니다.</p>
 */
public interface BusinessAppAssignmentPort {

    /**
     * Business consumer group 의 현재 Pod 별 partition 할당을 조회합니다.
     *
     * @return 담당 partition 최솟값 오름차순으로 정렬된 Pod 목록.
     *         group 이 없거나(미기동) 멤버가 없으면 빈 목록.
     */
    List<BusinessAppAssignmentView> findAllAssignments();

    /**
     * 단일 Business Pod 의 할당 현황입니다.
     *
     * @param host Pod 의 접속 호스트(Kubernetes 에서는 Pod IP). 알 수 없으면 null.
     * @param memberIds 이 Pod 에 속한 consumer group 멤버 식별자 목록(재조인마다 바뀜 — 식별용)
     * @param ownedPartitions 이 Pod 이 담당하는 설비 이벤트 토픽의 partition 목록(오름차순)
     */
    record BusinessAppAssignmentView(
            String host,
            List<String> memberIds,
            List<Integer> ownedPartitions
    ) {
    }
}

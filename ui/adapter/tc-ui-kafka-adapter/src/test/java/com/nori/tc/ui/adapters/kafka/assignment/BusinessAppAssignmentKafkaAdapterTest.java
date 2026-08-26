package com.nori.tc.ui.adapters.kafka.assignment;

import com.nori.tc.ui.core.port.messaging.BusinessAppAssignmentPort.BusinessAppAssignmentView;
import org.apache.kafka.clients.admin.MemberAssignment;
import org.apache.kafka.clients.admin.MemberDescription;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessAppAssignmentKafkaAdapterTest {

    private static final String TOPIC = "tc.eqp.events";
    private static final String MES_TOPIC = "dev.tc-evt";
    private static final String UI_TOPIC = "tc.ui.events.business";

    private static MemberDescription member(final String memberId, final String host, final TopicPartition... partitions) {
        return new MemberDescription(memberId, "client-" + memberId, host, new MemberAssignment(Set.of(partitions)));
    }

    @Test
    void 담당_partition_최솟값_순으로_정렬한다() {
        // Kafka 는 멤버 순서를 보장하지 않는다 — 정렬이 없으면 조회할 때마다 business_app 번호가 뒤바뀐다.
        final List<BusinessAppAssignmentView> assignments = BusinessAppAssignmentKafkaAdapter.toAssignments(
                List.of(
                        member("m-b", "10.244.1.62", new TopicPartition(TOPIC, 4), new TopicPartition(TOPIC, 3)),
                        member("m-a", "10.244.2.80", new TopicPartition(TOPIC, 1), new TopicPartition(TOPIC, 0))
                ),
                TOPIC);

        assertThat(assignments).hasSize(2);
        assertThat(assignments.get(0).host()).isEqualTo("10.244.2.80");
        assertThat(assignments.get(0).memberIds()).containsExactly("m-a");
        assertThat(assignments.get(0).ownedPartitions()).containsExactly(0, 1);
        assertThat(assignments.get(1).host()).isEqualTo("10.244.1.62");
        assertThat(assignments.get(1).ownedPartitions()).containsExactly(3, 4);
    }

    @Test
    void 같은_host_의_멤버는_Pod_하나로_합친다() {
        /*
         * 라이브 실측 구조 — Pod 2 대가 토픽 4 종을 각각 listener 로 구독해 멤버가 8 개다.
         * 멤버를 그대로 세면 business_app 이 8 개로 보이고 그중 6 개가 "담당 없음" 이 된다.
         */
        final List<BusinessAppAssignmentView> assignments = BusinessAppAssignmentKafkaAdapter.toAssignments(
                List.of(
                        member("pod-a-eqp", "10.244.2.80", new TopicPartition(TOPIC, 0), new TopicPartition(TOPIC, 1)),
                        member("pod-a-mes", "10.244.2.80", new TopicPartition(MES_TOPIC, 0)),
                        member("pod-a-ui", "10.244.2.80", new TopicPartition(UI_TOPIC, 0)),
                        member("pod-b-eqp", "10.244.1.62", new TopicPartition(TOPIC, 3)),
                        member("pod-b-mes", "10.244.1.62", new TopicPartition(MES_TOPIC, 3))
                ),
                TOPIC);

        assertThat(assignments).hasSize(2);
        assertThat(assignments.get(0).host()).isEqualTo("10.244.2.80");
        assertThat(assignments.get(0).memberIds()).containsExactly("pod-a-eqp", "pod-a-mes", "pod-a-ui");
        assertThat(assignments.get(0).ownedPartitions()).containsExactly(0, 1);
        assertThat(assignments.get(1).host()).isEqualTo("10.244.1.62");
        assertThat(assignments.get(1).memberIds()).containsExactly("pod-b-eqp", "pod-b-mes");
        assertThat(assignments.get(1).ownedPartitions()).containsExactly(3);
    }

    @Test
    void 다른_토픽의_할당은_제외한다() {
        // business 는 설비 이벤트 외 토픽도 함께 구독한다. route partition 축은 설비 이벤트 토픽뿐이다.
        final List<BusinessAppAssignmentView> assignments = BusinessAppAssignmentKafkaAdapter.toAssignments(
                List.of(member("m-a", "10.244.2.80",
                        new TopicPartition(TOPIC, 2),
                        new TopicPartition(UI_TOPIC, 0))),
                TOPIC);

        assertThat(assignments).hasSize(1);
        assertThat(assignments.get(0).ownedPartitions()).containsExactly(2);
    }

    @Test
    void 담당_partition_이_없는_Pod_은_뒤로_보낸다() {
        final List<BusinessAppAssignmentView> assignments = BusinessAppAssignmentKafkaAdapter.toAssignments(
                List.of(
                        member("m-idle", "10.244.3.1"),
                        member("m-a", "10.244.2.80", new TopicPartition(TOPIC, 5))
                ),
                TOPIC);

        assertThat(assignments.stream().map(BusinessAppAssignmentView::host))
                .containsExactly("10.244.2.80", "10.244.3.1");
        assertThat(assignments.get(1).ownedPartitions()).isEmpty();
    }

    @Test
    void host_를_모르는_멤버는_합치지_않는다() {
        // 모른다는 사실을 임의 병합으로 감추지 않는다 — 각자 별개 Pod 으로 남는다.
        final List<BusinessAppAssignmentView> assignments = BusinessAppAssignmentKafkaAdapter.toAssignments(
                List.of(
                        member("m-x", "", new TopicPartition(TOPIC, 0)),
                        member("m-y", "  ", new TopicPartition(TOPIC, 1))
                ),
                TOPIC);

        assertThat(assignments).hasSize(2);
        assertThat(assignments).allSatisfy(assignment -> assertThat(assignment.host()).isNull());
        assertThat(assignments.stream().map(BusinessAppAssignmentView::memberIds).flatMap(List::stream))
                .containsExactly("m-x", "m-y");
    }

    @Test
    void 브로커가_준_host_의_앞_슬래시를_떼고_Pod_IP_로_내려준다() {
        /*
         * 라이브 실측(kafka-consumer-groups.sh --describe --members): HOST 열이 /10.244.2.80 이다.
         * 브로커가 InetAddress#toString 형식으로 기록하기 때문이다. 슬래시를 그대로 두면 화면에
         * 노출되고, Downward API 의 POD_IP(=10.244.2.80) 와 문자열이 달라 Pod 조인 키로 쓸 수 없다.
         */
        final List<BusinessAppAssignmentView> assignments = BusinessAppAssignmentKafkaAdapter.toAssignments(
                List.of(
                        member("m-eqp", "/10.244.2.80", new TopicPartition(TOPIC, 0)),
                        member("m-mes", "/10.244.2.80", new TopicPartition(MES_TOPIC, 0)),
                        member("m-b", "k8s-node04/10.244.1.62", new TopicPartition(TOPIC, 3))
                ),
                TOPIC);

        assertThat(assignments).hasSize(2);
        assertThat(assignments.get(0).host()).isEqualTo("10.244.2.80");
        assertThat(assignments.get(0).memberIds()).containsExactly("m-eqp", "m-mes");
        // 이름이 함께 실린 형태도 같은 Pod IP 로 정규화된다.
        assertThat(assignments.get(1).host()).isEqualTo("10.244.1.62");
    }

    @Test
    void 멤버가_없으면_빈_목록이다() {
        assertThat(BusinessAppAssignmentKafkaAdapter.toAssignments(List.of(), TOPIC)).isEmpty();
    }
}

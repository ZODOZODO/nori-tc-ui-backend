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
        assertThat(assignments.get(0).memberId()).isEqualTo("m-a");
        assertThat(assignments.get(0).host()).isEqualTo("10.244.2.80");
        assertThat(assignments.get(0).ownedPartitions()).containsExactly(0, 1);
        assertThat(assignments.get(1).memberId()).isEqualTo("m-b");
        assertThat(assignments.get(1).ownedPartitions()).containsExactly(3, 4);
    }

    @Test
    void 다른_토픽의_할당은_제외한다() {
        // business 는 설비 이벤트 외 토픽도 함께 구독한다. route partition 축은 설비 이벤트 토픽뿐이다.
        final List<BusinessAppAssignmentView> assignments = BusinessAppAssignmentKafkaAdapter.toAssignments(
                List.of(member("m-a", "10.244.2.80",
                        new TopicPartition(TOPIC, 2),
                        new TopicPartition("tc.ui.events.business", 0))),
                TOPIC);

        assertThat(assignments).hasSize(1);
        assertThat(assignments.get(0).ownedPartitions()).containsExactly(2);
    }

    @Test
    void 담당_partition_이_없는_멤버는_뒤로_보낸다() {
        final List<BusinessAppAssignmentView> assignments = BusinessAppAssignmentKafkaAdapter.toAssignments(
                List.of(
                        member("m-idle", "10.244.3.1"),
                        member("m-a", "10.244.2.80", new TopicPartition(TOPIC, 5))
                ),
                TOPIC);

        assertThat(assignments.stream().map(BusinessAppAssignmentView::memberId))
                .containsExactly("m-a", "m-idle");
        assertThat(assignments.get(1).ownedPartitions()).isEmpty();
    }

    @Test
    void 멤버가_없으면_빈_목록이다() {
        assertThat(BusinessAppAssignmentKafkaAdapter.toAssignments(List.of(), TOPIC)).isEmpty();
    }
}

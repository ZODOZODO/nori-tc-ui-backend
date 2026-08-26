package com.nori.tc.ui.adapters.kafka.assignment;

import com.nori.tc.ui.adapters.kafka.config.UiBusinessConsumerProperties;
import com.nori.tc.ui.core.port.messaging.BusinessAppAssignmentPort;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.MemberDescription;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.GroupIdNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Business consumer group 의 멤버별 partition 할당을 Kafka AdminClient 로 조회하는 어댑터입니다.
 *
 * <p>AdminClient 는 호출마다 만들고 닫습니다 — 이 조회는 설비 화면 진입/스코프 전환에서만 일어나는
 * 온디맨드 경로(프론트 staleTime 60s, 폴링 없음)라, 상주 연결을 들고 그 수명을 관리할 이유가 없습니다.</p>
 */
@Component
@EnableConfigurationProperties(UiBusinessConsumerProperties.class)
public class BusinessAppAssignmentKafkaAdapter implements BusinessAppAssignmentPort {

    private static final Logger log = LoggerFactory.getLogger(BusinessAppAssignmentKafkaAdapter.class);

    /**
     * 담당 partition 이 없는 멤버(리밸런스 직후 등)의 정렬 키.
     *
     * <p>목록 뒤로 몰아 business_app 번호가 실제 담당 범위 순서를 따르게 한다.</p>
     */
    private static final int NO_PARTITION_ORDER_KEY = Integer.MAX_VALUE;

    /**
     * 담당 partition 최솟값 → memberId 순.
     *
     * <p>Kafka 는 멤버 순서를 보장하지 않으므로 정렬하지 않으면 조회할 때마다 business_app1 과
     * business_app2 가 뒤바뀐다. memberId 는 재조인마다 바뀌어 정렬 기준으로 쓸 수 없으므로
     * 담당 범위를 1 차 기준으로 두고, memberId 는 동률일 때만 쓴다.</p>
     */
    private static final Comparator<BusinessAppAssignmentView> ASSIGNMENT_ORDER =
            Comparator.<BusinessAppAssignmentView>comparingInt(view -> view.ownedPartitions().isEmpty()
                            ? NO_PARTITION_ORDER_KEY
                            : view.ownedPartitions().get(0))
                    .thenComparing(BusinessAppAssignmentView::memberId);

    private final KafkaAdmin kafkaAdmin;
    private final UiBusinessConsumerProperties properties;

    /**
     * 필수 의존성을 주입받습니다.
     *
     * @param kafkaAdmin AdminClient 설정(bootstrap.servers / 보안 설정)의 출처
     * @param properties 조회 대상 group / topic 설정
     */
    public BusinessAppAssignmentKafkaAdapter(
            final KafkaAdmin kafkaAdmin,
            final UiBusinessConsumerProperties properties
    ) {
        this.kafkaAdmin = Objects.requireNonNull(kafkaAdmin, "kafkaAdmin is null");
        this.properties = Objects.requireNonNull(properties, "properties is null");
    }

    @Override
    public List<BusinessAppAssignmentView> findAllAssignments() {
        final String groupId = properties.getGroupId();
        final String topic = properties.getEqpEventsTopic();

        try (Admin admin = Admin.create(new HashMap<>(kafkaAdmin.getConfigurationProperties()))) {
            final ConsumerGroupDescription description = admin
                    .describeConsumerGroups(List.of(groupId))
                    .describedGroups()
                    .get(groupId)
                    .get(properties.getAdminTimeoutMs(), TimeUnit.MILLISECONDS);
            return toAssignments(description.members(), topic);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "business consumer group 조회가 중단되었습니다. groupId=" + groupId, interrupted);
        } catch (ExecutionException executionFailure) {
            // group 미존재는 인프라 실패가 아니라 "떠 있는 프로세스 0" 이다 — 화면을 깨지 않고 빈 목록을 준다.
            if (executionFailure.getCause() instanceof GroupIdNotFoundException) {
                log.warn("business consumer group 이 없습니다(미기동으로 간주). groupId={}", groupId);
                return List.of();
            }
            log.error("business consumer group 조회 실패. groupId={}, topic={}", groupId, topic, executionFailure);
            throw new IllegalStateException("business consumer group 조회 실패. groupId=" + groupId, executionFailure);
        } catch (TimeoutException timeout) {
            log.error("business consumer group 조회 타임아웃. groupId={}, timeoutMs={}",
                    groupId, properties.getAdminTimeoutMs(), timeout);
            throw new IllegalStateException("business consumer group 조회 타임아웃. groupId=" + groupId, timeout);
        }
    }

    /**
     * consumer group 멤버를 설비 이벤트 토픽 기준 할당 목록으로 옮깁니다.
     *
     * @param members consumer group 멤버
     * @param topic route partition 축이 되는 설비 이벤트 토픽
     * @return {@link #ASSIGNMENT_ORDER} 로 정렬된 할당 목록
     */
    static List<BusinessAppAssignmentView> toAssignments(
            final Collection<MemberDescription> members,
            final String topic
    ) {
        return members.stream()
                .map(member -> new BusinessAppAssignmentView(
                        member.consumerId(),
                        blankToNull(member.host()),
                        member.assignment().topicPartitions().stream()
                                .filter(topicPartition -> topic.equals(topicPartition.topic()))
                                .map(TopicPartition::partition)
                                .sorted()
                                .toList()
                ))
                .sorted(ASSIGNMENT_ORDER)
                .toList();
    }

    private static String blankToNull(final String value) {
        return value == null || value.isBlank() ? null : value;
    }
}

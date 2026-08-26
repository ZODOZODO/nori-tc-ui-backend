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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
     * 담당 partition 최솟값 → host → 첫 memberId 순.
     *
     * <p>Kafka 는 멤버 순서를 보장하지 않으므로 정렬하지 않으면 조회할 때마다 business_app1 과
     * business_app2 가 뒤바뀐다. memberId 는 재조인마다 바뀌어 정렬 기준으로 쓸 수 없으므로
     * 담당 범위를 1 차 기준으로 두고, 동률이면 Pod 수명 동안 안정한 host 를 쓴다. host 를 알 수 없는
     * 멤버끼리의 동률은 memberId 로 갈라 순서를 결정적으로 만든다.</p>
     */
    private static final Comparator<BusinessAppAssignmentView> ASSIGNMENT_ORDER =
            Comparator.<BusinessAppAssignmentView>comparingInt(view -> view.ownedPartitions().isEmpty()
                            ? NO_PARTITION_ORDER_KEY
                            : view.ownedPartitions().get(0))
                    .thenComparing(BusinessAppAssignmentView::host,
                            Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(view -> view.memberIds().isEmpty() ? "" : view.memberIds().get(0));

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
     * consumer group 멤버를 설비 이벤트 토픽 기준 <b>Pod 별</b> 할당 목록으로 옮깁니다.
     *
     * <p>Pod 하나가 토픽마다 listener 컨테이너를 따로 띄우므로 멤버 수는 Pod 수의 배수입니다. 같은
     * host 의 멤버를 하나로 합쳐 Pod 축을 만들고, 담당 partition 은 설비 이벤트 토픽 것만 합집합으로
     * 모읍니다 — 다른 토픽만 맡은 멤버는 이 축에서 담당 partition 이 없는 것으로 흡수됩니다.</p>
     *
     * <p>host 병합의 전제는 <b>Pod 하나가 IP 하나</b>라는 Kubernetes Pod 네트워킹입니다. host 네트워크로
     * 같은 IP 에 프로세스를 여럿 띄우는 구성에서는 서로 다른 프로세스가 한 Pod 으로 합쳐집니다.</p>
     *
     * @param members consumer group 멤버
     * @param topic route partition 축이 되는 설비 이벤트 토픽
     * @return {@link #ASSIGNMENT_ORDER} 로 정렬된 Pod 별 할당 목록
     */
    static List<BusinessAppAssignmentView> toAssignments(
            final Collection<MemberDescription> members,
            final String topic
    ) {
        final Map<String, List<MemberDescription>> membersByPod = new LinkedHashMap<>();
        for (final MemberDescription member : members) {
            membersByPod.computeIfAbsent(podKeyOf(member), key -> new ArrayList<>()).add(member);
        }
        return membersByPod.values().stream()
                .map(podMembers -> toPodAssignment(podMembers, topic))
                .sorted(ASSIGNMENT_ORDER)
                .toList();
    }

    /**
     * 멤버를 어느 Pod 으로 묶을지 결정하는 key 를 만듭니다.
     *
     * <p>host 를 알 수 없으면 합칠 근거가 없으므로 그 멤버만 별개 Pod 으로 둡니다 — 모른다는 사실을
     * 임의 병합으로 감추지 않습니다.</p>
     *
     * @param member consumer group 멤버
     * @return Pod 묶음 key
     */
    private static String podKeyOf(final MemberDescription member) {
        final String host = hostAddressOf(member);
        return host != null ? "host:" + host : "member:" + member.consumerId();
    }

    /**
     * 멤버 host 를 Pod IP 로 정규화합니다.
     *
     * <p>브로커는 멤버의 host 를 {@code InetAddress#toString()} 형식으로 기록하므로 값에
     * {@code /10.244.2.80} 처럼 <b>앞에 {@code /} 가 붙습니다</b>(라이브 실측 —
     * {@code kafka-consumer-groups.sh --describe --members} 의 HOST 열). 이름이 함께 실리면
     * {@code node/10.244.2.80} 형태가 되므로 마지막 {@code /} 뒤를 주소로 봅니다.</p>
     *
     * <p>정규화하지 않으면 화면에 슬래시가 그대로 노출되고, Downward API 가 주는 Pod IP
     * ({@code POD_IP=10.244.2.80}) 와 문자열이 달라 Pod 축 조인 키로도 쓸 수 없습니다.</p>
     *
     * @param member consumer group 멤버
     * @return Pod IP. 알 수 없으면 null
     */
    private static String hostAddressOf(final MemberDescription member) {
        final String host = blankToNull(member.host());
        if (host == null) {
            return null;
        }
        final int lastSeparator = host.lastIndexOf('/');
        return blankToNull(lastSeparator < 0 ? host : host.substring(lastSeparator + 1));
    }

    /**
     * 같은 Pod 의 멤버들을 할당 하나로 합칩니다.
     *
     * @param podMembers 같은 Pod 에 속한 멤버 목록(비어 있지 않음)
     * @param topic route partition 축이 되는 설비 이벤트 토픽
     * @return Pod 할당
     */
    private static BusinessAppAssignmentView toPodAssignment(
            final List<MemberDescription> podMembers,
            final String topic
    ) {
        return new BusinessAppAssignmentView(
                hostAddressOf(podMembers.get(0)),
                podMembers.stream()
                        .map(MemberDescription::consumerId)
                        .sorted()
                        .toList(),
                podMembers.stream()
                        .flatMap(member -> member.assignment().topicPartitions().stream())
                        .filter(topicPartition -> topic.equals(topicPartition.topic()))
                        .map(TopicPartition::partition)
                        .distinct()
                        .sorted()
                        .toList()
        );
    }

    private static String blankToNull(final String value) {
        return value == null || value.isBlank() ? null : value;
    }
}

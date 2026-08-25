package com.nori.tc.ui.core.coordination;

import com.nori.tc.ui.core.model.UiCommandEventType;
import com.nori.tc.ui.core.port.db.EqpQueryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * operation 의 영향 리소스 집합을 산출합니다(§7.3 / §11.2 표 / §18.1).
 *
 * <p>두 규칙을 함께 강제합니다.</p>
 * <ol>
 *   <li><b>폐쇄</b> — model parameter 와 Business JAR 는 {@code modelVersionKey} 공유 상태이므로 그
 *       version 을 참조하는 모든 설비를 포함합니다. 일부만 잠그면 나머지 설비가 옛 plugin/parameter 로
 *       처리를 계속합니다.</li>
 *   <li><b>정렬</b> — 결과를 lexicographic 으로 정렬합니다. 여러 리소스를 잠그는 순서가 operation 마다
 *       다르면 서로를 기다리는 교착이 생깁니다.</li>
 * </ol>
 *
 * <p>Business reconciler 가 prepare 단계에서 두 조건을 다시 검증해 위반을 거부하므로(§11.2), 여기서
 * 규칙을 어기면 operation 이 PASS 되지 않습니다.</p>
 */
public final class UiResourceKeyPlanner {

    private static final Logger log = LoggerFactory.getLogger(UiResourceKeyPlanner.class);

    /** 설비 리소스 타입 접두사입니다. */
    public static final String RESOURCE_TYPE_EQUIPMENT = "equipment";

    private final EqpQueryPort eqpQueryPort;

    /**
     * planner 를 만듭니다.
     *
     * @param eqpQueryPort 설비 조회 포트
     */
    public UiResourceKeyPlanner(final EqpQueryPort eqpQueryPort) {
        this.eqpQueryPort = Objects.requireNonNull(eqpQueryPort, "eqpQueryPort is null");
    }

    /**
     * 영향 리소스 키를 정렬된 형태로 산출합니다.
     *
     * @param eventType 요청 이벤트 타입
     * @param eqpId 직접 대상 설비
     * @param affectedModelVersionKeys 이 변경이 건드리는 modelVersionKey(현재/목표 모두)
     * @return lexicographic 정렬된 {@code equipment:{eqpId}} 목록
     * @throws IllegalStateException 영향 집합을 확정할 수 없을 때
     */
    public List<String> plan(
            final UiCommandEventType eventType,
            final String eqpId,
            final Set<Long> affectedModelVersionKeys
    ) {
        Objects.requireNonNull(eventType, "eventType is null");
        Objects.requireNonNull(eqpId, "eqpId is null");

        final Set<String> eqpIds = new LinkedHashSet<>();
        eqpIds.add(eqpId);

        if (sharesModelState(eventType)) {
            if (affectedModelVersionKeys == null || affectedModelVersionKeys.isEmpty()) {
                throw new IllegalStateException(
                        "model 공유 상태를 바꾸는 요청인데 modelVersionKey 를 확정하지 못했습니다 — 영향 집합을 "
                                + "닫을 수 없어 접수하지 않습니다. eventType=" + eventType + ", eqpId=" + eqpId);
            }
            for (final Long modelVersionKey : affectedModelVersionKeys) {
                if (modelVersionKey == null) {
                    continue;
                }
                final List<String> shared = eqpQueryPort.findEqpIdsByModelVersionKey(modelVersionKey);
                eqpIds.addAll(shared);
                if (log.isDebugEnabled()) {
                    log.debug("UI_RESOURCE_CLOSURE. eventType={}, modelVersionKey={}, sharedEqpCount={}",
                            eventType, modelVersionKey, shared.size());
                }
            }
        }

        final TreeSet<String> sorted = new TreeSet<>();
        for (final String affected : eqpIds) {
            sorted.add(RESOURCE_TYPE_EQUIPMENT + ":" + affected);
        }
        return List.copyOf(sorted);
    }

    /**
     * 이 이벤트가 {@code modelVersionKey} 공유 상태를 바꾸는지 반환합니다(§11.2 표).
     *
     * @param eventType 요청 이벤트 타입
     * @return 공유 상태 변경이면 true
     */
    public static boolean sharesModelState(final UiCommandEventType eventType) {
        return switch (eventType) {
            case EQP_MODEL_UPDATE, EQP_BUSINESS_JARFILE_UPDATE, EQP_GATEWAY_JARFILE_UPDATE -> true;
            default -> false;
        };
    }

    /**
     * resourceKey 에서 설비 id 를 꺼냅니다.
     *
     * @param resourceKey {@code equipment:{eqpId}} 형식 키
     * @return 설비 id
     */
    public static String eqpIdOf(final String resourceKey) {
        final int separator = Objects.requireNonNull(resourceKey, "resourceKey is null").indexOf(':');
        if (separator < 0) {
            throw new IllegalArgumentException("malformed resourceKey: " + resourceKey);
        }
        return resourceKey.substring(separator + 1);
    }
}

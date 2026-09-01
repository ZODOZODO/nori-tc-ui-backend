package com.nori.tc.ui.adapters.redis.repair;

import com.nori.tc.ui.core.eqp.EqpRepairAction;
import com.nori.tc.ui.core.eqp.EqpRepairTask;
import com.nori.tc.ui.core.port.db.EqpRepairTaskPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * EQP repair task를 Redis Hash에 durable 하게 저장하는 어댑터입니다.
 *
 * <p><b>이 키에 TTL 을 걸지 마십시오.</b> 같은 네임스페이스의 다른 키(async 결과, deploy live/trace,
 * comm-log)는 전부 TTL 이나 트리밍을 갖지만 이 키만 예외인 것은 의도된 것입니다 — 항목 하나가
 * "운영자가 아직 처리하지 않은 복구 작업"이고, 시간이 지났다고 사라지면 미처리 작업이 조용히
 * 사라집니다.</p>
 *
 * <p>대신 항목 수는 이렇게 유한합니다: field 키는
 * {@code EqpRepairTaskService#buildTaskId} 가 만드는 {@code eqpId:commandType:repairAction} 로
 * 결정적이므로, 같은 실패가 몇 번 반복돼도 field 하나로 수렴합니다. 즉 상한은 발생 횟수가 아니라
 * (설비 수 x commandType 수 x {@link EqpRepairAction} 6종) 입니다.</p>
 *
 * <p>남은 한계는 <b>회수 경로가 없다</b>는 점입니다 — 운영자가 복구를 끝내도 항목을 지우는 API 가
 * 없어 백로그에 계속 남습니다. 이를 없애려면 TTL 이 아니라 "해결 처리(remove)" 경로를 만들어야
 * 합니다.</p>
 */
@Service
@Primary
public class RedisEqpRepairTaskStore implements EqpRepairTaskPort {

    private static final Logger log = LoggerFactory.getLogger(RedisEqpRepairTaskStore.class);

    private static final String REPAIR_TASK_HASH_KEY = "tc:ui:backend:eqp-repair:tasks";

    private final RedisTemplate<String, Object> businessRedisTemplate;

    public RedisEqpRepairTaskStore(
            @Qualifier("businessRedisTemplate") final RedisTemplate<String, Object> businessRedisTemplate
    ) {
        this.businessRedisTemplate = Objects.requireNonNull(businessRedisTemplate,
                "businessRedisTemplate is null");
    }

    @Override
    public void save(final EqpRepairTask repairTask) {
        Objects.requireNonNull(repairTask, "repairTask is null");
        try {
            businessRedisTemplate.opsForHash().put(REPAIR_TASK_HASH_KEY, repairTask.taskId(), repairTask);
        } catch (Exception redisException) {
            log.error("EQP repair task 저장 실패. taskId={}, eqpId={}, repairAction={}",
                    repairTask.taskId(), repairTask.eqpId(), repairTask.repairAction(), redisException);
            throw redisException;
        }
    }

    @Override
    public List<EqpRepairTask> findAll() {
        try {
            final HashOperations<String, Object, Object> hashOperations = businessRedisTemplate.opsForHash();
            return hashOperations.values(REPAIR_TASK_HASH_KEY).stream()
                    .map(this::toRepairTask)
                    .sorted(Comparator.comparing(EqpRepairTask::createdAt))
                    .toList();
        } catch (Exception redisException) {
            log.error("EQP repair task 조회 실패.", redisException);
            throw redisException;
        }
    }

    private EqpRepairTask toRepairTask(final Object raw) {
        if (raw instanceof EqpRepairTask repairTask) {
            return repairTask;
        }
        if (raw instanceof Map<?, ?> map) {
            return new EqpRepairTask(
                    asRequiredText(map.get("taskId"), "taskId"),
                    asRequiredText(map.get("eqpId"), "eqpId"),
                    asRequiredText(map.get("commandType"), "commandType"),
                    EqpRepairAction.valueOf(asRequiredText(map.get("repairAction"), "repairAction")),
                    asNullableText(map.get("errorCode")),
                    asNullableText(map.get("errorMessage")),
                    OffsetDateTime.parse(asRequiredText(map.get("createdAt"), "createdAt"))
            );
        }
        throw new IllegalStateException("지원하지 않는 EQP repair task 타입입니다. type=" + raw.getClass().getName());
    }

    private static String asRequiredText(final Object raw, final String fieldName) {
        final String value = asNullableText(raw);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(fieldName + " is required");
        }
        return value;
    }

    private static String asNullableText(final Object raw) {
        if (raw == null) {
            return null;
        }
        final String text = raw.toString();
        return text.isBlank() ? null : text;
    }
}

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

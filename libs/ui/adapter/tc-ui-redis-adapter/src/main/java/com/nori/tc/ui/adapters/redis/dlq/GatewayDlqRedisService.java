package com.nori.tc.ui.adapters.redis.dlq;

import com.nori.tc.db.domain.redis.dlq.RedisDlqEntry;
import com.nori.tc.db.domain.redis.quarantine.RedisQuarantineEntry;
import com.nori.tc.ui.core.port.redis.GatewayDlqPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Gateway Redis(6379)에 저장된 DLQ 및 Quarantine 항목을 조회·삭제하는 서비스입니다.
 *
 * <p>이 서비스는 읽기(Read) 및 삭제(Delete) 전용입니다.
 * DLQ 항목 쓰기는 tc-comm-gateway-redis-adapter의 RedisDlqPublisher가 담당합니다.</p>
 *
 * <p>키 패턴:</p>
 * <ul>
 *   <li>DLQ: {@code tc:comm:gateway:dlq:{dlqId}}</li>
 *   <li>Quarantine: {@code tc:comm:gateway:quarantine:{equipmentId}}</li>
 * </ul>
 *
 * <p>역직렬화:</p>
 * <p>항목은 {@link java.io.Serializable} 기반 JDK 직렬화로 저장되어 있습니다.
 * {@link RedisDlqEntry}, {@link RedisQuarantineEntry}는 {@code tc-db-domain}에 위치하여
 * Gateway 어댑터(쓰기)와 UI 어댑터(읽기)가 동일 클래스를 공유합니다.</p>
 */
@Service
public class GatewayDlqRedisService implements GatewayDlqPort {

    private static final Logger log = LoggerFactory.getLogger(GatewayDlqRedisService.class);

    /** Gateway DLQ 키 접두사 */
    private static final String DLQ_KEY_PREFIX = "tc:comm:gateway:dlq:";

    /** Gateway Quarantine 키 접두사 */
    private static final String QUARANTINE_KEY_PREFIX = "tc:comm:gateway:quarantine:";

    /**
     * SCAN 명령 한 번에 반환할 힌트 개수.
     * Redis 서버가 반드시 이 수를 지킬 의무는 없지만, 커서 반복 횟수를 줄이는 데 도움됩니다.
     */
    private static final int SCAN_COUNT_HINT = 100;

    private final RedisTemplate<String, Object> gatewayRedisTemplate;

    public GatewayDlqRedisService(
            @Qualifier("gatewayRedisTemplate") final RedisTemplate<String, Object> gatewayRedisTemplate
    ) {
        this.gatewayRedisTemplate = Objects.requireNonNull(gatewayRedisTemplate,
                "gatewayRedisTemplate 은 null 이 될 수 없습니다.");
    }

    // -------------------------------------------------------------------------
    // DLQ 조회 / 삭제
    // -------------------------------------------------------------------------

    /**
     * Gateway DLQ에 저장된 모든 항목을 조회합니다.
     *
     * <p>SCAN 커서 방식으로 키를 순회하므로, KEYS 명령과 달리
     * 대용량 데이터셋에서도 Redis를 차단하지 않습니다.</p>
     *
     * @return DLQ 항목 목록. 항목이 없거나 오류 발생 시 빈 리스트 반환
     */
    public List<RedisDlqEntry> listAllDlq() {
        if (log.isDebugEnabled()) {
            log.debug("Gateway DLQ 전체 조회 시작. keyPrefix={}", DLQ_KEY_PREFIX);
        }

        final List<String> keys = scanKeys(DLQ_KEY_PREFIX + "*");
        if (keys.isEmpty()) {
            log.debug("Gateway DLQ 항목 없음.");
            return Collections.emptyList();
        }

        final List<RedisDlqEntry> entries = new ArrayList<>(keys.size());
        for (final String key : keys) {
            try {
                final Object raw = gatewayRedisTemplate.opsForValue().get(key);
                if (raw instanceof RedisDlqEntry entry) {
                    entries.add(entry);
                } else if (raw != null) {
                    // 저장된 타입이 예상과 다를 경우 — 직렬화 형식 불일치 가능성
                    log.warn("Gateway DLQ 키 타입 불일치. key={}, actualType={}",
                            key, raw.getClass().getName());
                }
            } catch (Exception e) {
                // 개별 키 역직렬화 실패는 전체 조회를 중단하지 않고 해당 항목만 건너뜁니다.
                log.warn("Gateway DLQ 항목 역직렬화 실패. key={}", key, e);
            }
        }

        log.info("Gateway DLQ 조회 완료. totalKeys={}, loadedEntries={}",
                keys.size(), entries.size());
        return Collections.unmodifiableList(entries);
    }

    /**
     * 지정한 dlqId에 해당하는 Gateway DLQ 항목을 삭제합니다.
     *
     * @param dlqId 삭제할 DLQ 항목 ID
     * @return 삭제 성공 여부 (키가 존재하지 않으면 false)
     */
    public boolean deleteDlq(final String dlqId) {
        Objects.requireNonNull(dlqId, "dlqId 는 null 이 될 수 없습니다.");

        final String key = DLQ_KEY_PREFIX + dlqId;
        final Boolean deleted = gatewayRedisTemplate.delete(key);
        final boolean result = Boolean.TRUE.equals(deleted);

        if (result) {
            log.info("Gateway DLQ 항목 삭제 완료. dlqId={}, key={}", dlqId, key);
        } else {
            // 키가 없는 경우: 이미 삭제되었거나 잘못된 ID
            log.warn("Gateway DLQ 항목 삭제 대상 없음. dlqId={}, key={}", dlqId, key);
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Quarantine 조회
    // -------------------------------------------------------------------------

    /**
     * Gateway Quarantine에 저장된 모든 항목을 조회합니다.
     *
     * <p>Quarantine 항목은 설비가 격리(차단) 상태임을 나타냅니다.
     * UI에서 격리 해제 결정을 위한 현황 조회 목적으로 사용합니다.</p>
     *
     * @return Quarantine 항목 목록. 항목이 없거나 오류 발생 시 빈 리스트 반환
     */
    public List<RedisQuarantineEntry> listAllQuarantine() {
        if (log.isDebugEnabled()) {
            log.debug("Gateway Quarantine 전체 조회 시작. keyPrefix={}", QUARANTINE_KEY_PREFIX);
        }

        final List<String> keys = scanKeys(QUARANTINE_KEY_PREFIX + "*");
        if (keys.isEmpty()) {
            log.debug("Gateway Quarantine 항목 없음.");
            return Collections.emptyList();
        }

        final List<RedisQuarantineEntry> entries = new ArrayList<>(keys.size());
        for (final String key : keys) {
            try {
                final Object raw = gatewayRedisTemplate.opsForValue().get(key);
                if (raw instanceof RedisQuarantineEntry entry) {
                    entries.add(entry);
                } else if (raw != null) {
                    log.warn("Gateway Quarantine 키 타입 불일치. key={}, actualType={}",
                            key, raw.getClass().getName());
                }
            } catch (Exception e) {
                log.warn("Gateway Quarantine 항목 역직렬화 실패. key={}", key, e);
            }
        }

        log.info("Gateway Quarantine 조회 완료. totalKeys={}, loadedEntries={}",
                keys.size(), entries.size());
        return Collections.unmodifiableList(entries);
    }

    // -------------------------------------------------------------------------
    // 내부 유틸
    // -------------------------------------------------------------------------

    /**
     * 지정한 패턴과 일치하는 Redis 키 목록을 SCAN 커서 방식으로 수집합니다.
     *
     * <p>KEYS 명령은 Redis 를 블로킹하여 대규모 운영 환경에서 응답 지연을 유발합니다.
     * SCAN 커서 방식은 반복적으로 일부 키를 반환하므로 서버 부하를 분산합니다.</p>
     *
     * @param pattern Redis SCAN match 패턴 (예: {@code tc:comm:gateway:dlq:*})
     * @return 일치하는 키 목록 (오류 발생 시 빈 리스트)
     */
    private List<String> scanKeys(final String pattern) {
        try {
            return gatewayRedisTemplate.execute((RedisCallback<List<String>>) connection -> {
                final List<String> result = new ArrayList<>();
                final ScanOptions options = ScanOptions.scanOptions()
                        .match(pattern)
                        .count(SCAN_COUNT_HINT)
                        .build();

                // try-with-resources로 커서 자동 해제 (close()의 억제 예외는 DataAccessException이 감쌈)
                try (var cursor = connection.keyCommands().scan(options)) {
                    while (cursor.hasNext()) {
                        result.add(new String(cursor.next(), StandardCharsets.UTF_8));
                    }
                }
                return result;
            });
        } catch (DataAccessException e) {
            log.error("Gateway Redis SCAN 실행 실패. pattern={}", pattern, e);
            return Collections.emptyList();
        }
    }
}

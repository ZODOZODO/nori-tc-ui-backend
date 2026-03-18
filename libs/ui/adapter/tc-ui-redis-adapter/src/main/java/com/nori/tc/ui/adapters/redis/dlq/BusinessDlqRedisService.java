package com.nori.tc.ui.adapters.redis.dlq;

import com.nori.tc.db.domain.redis.dlq.RedisBusinessDlqEntry;
import com.nori.tc.ui.core.port.redis.BusinessDlqPort;
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
 * Business Redis(6380)에 저장된 DLQ 항목을 조회·삭제하는 서비스입니다.
 *
 * <p>이 서비스는 읽기(Read) 및 삭제(Delete) 전용입니다.
 * DLQ 항목 쓰기는 tc-business-redis-adapter의 RedisBusinessDlqPublisher가 담당합니다.</p>
 *
 * <p>키 패턴:</p>
 * <ul>
 *   <li>DLQ: {@code tc:business:core:dlq:{dlqId}}</li>
 * </ul>
 *
 * <p>역직렬화:</p>
 * <p>항목은 {@link java.io.Serializable} 기반 JDK 직렬화로 저장되어 있습니다.
 * {@link RedisBusinessDlqEntry}는 {@code tc-db-domain}에 위치하여
 * Business 어댑터(쓰기)와 UI 어댑터(읽기)가 동일 클래스를 공유합니다.</p>
 */
@Service
public class BusinessDlqRedisService implements BusinessDlqPort {

    private static final Logger log = LoggerFactory.getLogger(BusinessDlqRedisService.class);

    /** Business DLQ 키 접두사 */
    private static final String DLQ_KEY_PREFIX = "tc:business:core:dlq:";

    /**
     * SCAN 명령 한 번에 반환할 힌트 개수.
     */
    private static final int SCAN_COUNT_HINT = 100;

    private final RedisTemplate<String, Object> businessRedisTemplate;

    public BusinessDlqRedisService(
            @Qualifier("businessRedisTemplate") final RedisTemplate<String, Object> businessRedisTemplate
    ) {
        this.businessRedisTemplate = Objects.requireNonNull(businessRedisTemplate,
                "businessRedisTemplate 은 null 이 될 수 없습니다.");
    }

    // -------------------------------------------------------------------------
    // DLQ 조회 / 삭제
    // -------------------------------------------------------------------------

    /**
     * Business DLQ에 저장된 모든 항목을 조회합니다.
     *
     * <p>SCAN 커서 방식으로 키를 순회하므로, KEYS 명령과 달리
     * 대용량 데이터셋에서도 Redis를 차단하지 않습니다.</p>
     *
     * @return DLQ 항목 목록. 항목이 없거나 오류 발생 시 빈 리스트 반환
     */
    public List<RedisBusinessDlqEntry> listAllDlq() {
        if (log.isDebugEnabled()) {
            log.debug("Business DLQ 전체 조회 시작. keyPrefix={}", DLQ_KEY_PREFIX);
        }

        final List<String> keys = scanKeys(DLQ_KEY_PREFIX + "*");
        if (keys.isEmpty()) {
            log.debug("Business DLQ 항목 없음.");
            return Collections.emptyList();
        }

        final List<RedisBusinessDlqEntry> entries = new ArrayList<>(keys.size());
        for (final String key : keys) {
            try {
                final Object raw = businessRedisTemplate.opsForValue().get(key);
                if (raw instanceof RedisBusinessDlqEntry entry) {
                    entries.add(entry);
                } else if (raw != null) {
                    // 저장된 타입이 예상과 다를 경우 — 직렬화 형식 불일치 가능성
                    log.warn("Business DLQ 키 타입 불일치. key={}, actualType={}",
                            key, raw.getClass().getName());
                }
            } catch (Exception e) {
                // 개별 키 역직렬화 실패는 전체 조회를 중단하지 않고 해당 항목만 건너뜁니다.
                log.warn("Business DLQ 항목 역직렬화 실패. key={}", key, e);
            }
        }

        log.info("Business DLQ 조회 완료. totalKeys={}, loadedEntries={}",
                keys.size(), entries.size());
        return Collections.unmodifiableList(entries);
    }

    /**
     * 지정한 dlqId에 해당하는 Business DLQ 항목을 삭제합니다.
     *
     * @param dlqId 삭제할 DLQ 항목 ID
     * @return 삭제 성공 여부 (키가 존재하지 않으면 false)
     */
    public boolean deleteDlq(final String dlqId) {
        Objects.requireNonNull(dlqId, "dlqId 는 null 이 될 수 없습니다.");

        final String key = DLQ_KEY_PREFIX + dlqId;
        final Boolean deleted = businessRedisTemplate.delete(key);
        final boolean result = Boolean.TRUE.equals(deleted);

        if (result) {
            log.info("Business DLQ 항목 삭제 완료. dlqId={}, key={}", dlqId, key);
        } else {
            // 키가 없는 경우: 이미 삭제되었거나 잘못된 ID
            log.warn("Business DLQ 항목 삭제 대상 없음. dlqId={}, key={}", dlqId, key);
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // 내부 유틸
    // -------------------------------------------------------------------------

    /**
     * 지정한 패턴과 일치하는 Redis 키 목록을 SCAN 커서 방식으로 수집합니다.
     *
     * @param pattern Redis SCAN match 패턴 (예: {@code tc:business:core:dlq:*})
     * @return 일치하는 키 목록 (오류 발생 시 빈 리스트)
     */
    private List<String> scanKeys(final String pattern) {
        try {
            return businessRedisTemplate.execute((RedisCallback<List<String>>) connection -> {
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
            log.error("Business Redis SCAN 실행 실패. pattern={}", pattern, e);
            return Collections.emptyList();
        }
    }
}

package com.nori.tc.ui.adapter.db;

import com.nori.tc.db.core.eqp.store.TcEqpStore;
import com.nori.tc.ui.core.coordination.port.UiFinalizedRevisionProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.OptionalLong;

/**
 * {@link UiFinalizedRevisionProvider} 의 DB 구현입니다(SPEC §13.7).
 *
 * <p>값의 실체는 {@code tc_eqp.last_finalized_revision}(Flyway V7)이며, startup overlay 의 원장
 * 후퇴 검증(§11.11 Business / §12.3 Gateway)이 이 값을 기준으로 삼습니다 — Redis 를 오래된
 * AOF/백업으로 복원하면 committed revision 이 이 값보다 낮아지고, 그 overlay 를 적용하면 이미
 * 확정된 DB 상태가 과거로 되돌아갑니다.</p>
 *
 * <p>기록은 단조 증가입니다(§18.2). 조건부 UPDATE 한 문장으로 수행하므로 읽고 쓰는 사이에 다른
 * executor 가 기록한 더 큰 revision 을 되돌리지 않습니다.</p>
 */
@Component
public class UiFinalizedRevisionDbAdapter implements UiFinalizedRevisionProvider {

    private static final Logger log = LoggerFactory.getLogger(UiFinalizedRevisionDbAdapter.class);

    private final TcEqpStore eqpStore;

    /**
     * 의존성을 초기화합니다.
     *
     * @param eqpStore tc_eqp 저장소 포트
     */
    public UiFinalizedRevisionDbAdapter(final TcEqpStore eqpStore) {
        this.eqpStore = Objects.requireNonNull(eqpStore, "eqpStore is null");
        log.info("UI_LEDGER_REGRESSION_CHECK_ARMED. source=tc_eqp.last_finalized_revision");
    }

    @Override
    public OptionalLong lastFinalizedRevision(final String eqpId) {
        if (eqpId == null || eqpId.isBlank()) {
            return OptionalLong.empty();
        }
        return eqpStore.findLastFinalizedRevision(eqpId);
    }

    @Override
    public boolean recordFinalizedRevision(final String eqpId, final long revision) {
        if (eqpId == null || eqpId.isBlank() || revision <= 0L) {
            return false;
        }
        if (eqpStore.updateLastFinalizedRevisionIfGreater(eqpId, revision) > 0) {
            return true;
        }
        /*
         * 0 행은 두 가지다 — (a) 이미 같거나 큰 revision 이 기록돼 있다(executor crash 후 반복 확정,
         * §13.7 의 "반복해도 같은 결과"), (b) 설비 행이 없다. 전자는 성공이고 후자는 실패이므로
         * 현재 값을 되읽어 구분한다. 무조건 true 로 접으면 삭제된 설비의 확정이 성공으로 보고된다.
         */
        final OptionalLong current = eqpStore.findLastFinalizedRevision(eqpId);
        return current.isPresent() && current.getAsLong() >= revision;
    }
}

package com.nori.tc.ui.adapter.db;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.nori.tc.db.core.common.PageRequest;
import com.nori.tc.db.core.eqp.store.TcEqpParamStore;
import com.nori.tc.db.core.eqp.store.TcEqpPortStatusStore;
import com.nori.tc.db.core.eqp.store.TcEqpStateHistStore;
import com.nori.tc.db.core.eqp.store.TcEqpStateStore;
import com.nori.tc.db.core.eqp.store.TcEqpStore;
import com.nori.tc.db.core.model.store.TcModelParamStore;
import com.nori.tc.db.domain.eqp.TcEqp;
import com.nori.tc.db.domain.eqp.TcEqpParam;
import com.nori.tc.db.domain.eqp.TcEqpPortStatus;
import com.nori.tc.db.domain.eqp.TcEqpState;
import com.nori.tc.db.domain.eqp.TcEqpStateHist;
import com.nori.tc.db.domain.model.TcModelParam;
import com.nori.tc.ui.adapter.db.mapper.UiEqpDbMapper;
import com.nori.tc.ui.adapter.db.mapper.UiModelDbMapper;
import com.nori.tc.ui.core.eqp.LiveMapEqpPrefetchRow;
import com.nori.tc.ui.core.eqp.LiveMapPrefetchSnapshot;
import com.nori.tc.ui.core.port.db.EqpQueryPort.EqpRuntimeStateView;
import com.nori.tc.ui.core.port.db.LiveMapPrefetchPort;
import com.nori.tc.ui.domain.eqp.UiEqp;
import com.nori.tc.ui.domain.eqp.UiEqpParam;
import com.nori.tc.ui.domain.eqp.UiEqpPortStatus;
import com.nori.tc.ui.domain.model.UiModelParam;

/**
 * {@link LiveMapPrefetchPort} 의 JPA 어댑터.
 *
 * <p>한 번의 prefetch() 호출 안에 6 sub-query 로 live-map 화면 데이터를 모두 가져온다.
 * EQP 1000 개 미만 가정 (SPEC Constraints) 으로 단순 IN 절 한 번에 처리. EQP 가 늘어나면
 * chunk 처리 가능.</p>
 */
@Component
public class JpaLiveMapPrefetchPort implements LiveMapPrefetchPort {

    private static final Logger log = LoggerFactory.getLogger(JpaLiveMapPrefetchPort.class);

    private static final int EQP_FIND_ALL_LIMIT = 1_000;
    private static final String PROCESS_TYPE_PARAM_NAME = "PROCESS_TYPE";
    private static final String WAFER_MODE_PARAM_NAME = "WAFER_MODE";

    private final TcEqpStore eqpStore;
    private final TcEqpStateStore eqpStateStore;
    private final TcEqpStateHistStore eqpStateHistStore;
    private final TcEqpParamStore eqpParamStore;
    private final TcEqpPortStatusStore eqpPortStatusStore;
    private final TcModelParamStore modelParamStore;

    public JpaLiveMapPrefetchPort(
            final TcEqpStore eqpStore,
            final TcEqpStateStore eqpStateStore,
            final TcEqpStateHistStore eqpStateHistStore,
            final TcEqpParamStore eqpParamStore,
            final TcEqpPortStatusStore eqpPortStatusStore,
            final TcModelParamStore modelParamStore
    ) {
        this.eqpStore = Objects.requireNonNull(eqpStore, "eqpStore is null");
        this.eqpStateStore = Objects.requireNonNull(eqpStateStore, "eqpStateStore is null");
        this.eqpStateHistStore = Objects.requireNonNull(eqpStateHistStore, "eqpStateHistStore is null");
        this.eqpParamStore = Objects.requireNonNull(eqpParamStore, "eqpParamStore is null");
        this.eqpPortStatusStore = Objects.requireNonNull(eqpPortStatusStore, "eqpPortStatusStore is null");
        this.modelParamStore = Objects.requireNonNull(modelParamStore, "modelParamStore is null");
    }

    @Override
    public LiveMapPrefetchSnapshot prefetch() {
        // 1. tc_eqp findAll → enabled 만 남김
        final List<UiEqp> eqps = eqpStore.findAll(PageRequest.of(0, EQP_FIND_ALL_LIMIT)).stream()
                .map(UiEqpDbMapper::toDomain)
                .filter(Objects::nonNull)
                .filter(UiEqp::enabled)
                .toList();

        if (eqps.isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug("live-map prefetch: enabled EQP 없음 → 빈 snapshot 반환");
            }
            return new LiveMapPrefetchSnapshot(List.of(), Map.of());
        }

        final List<Long> eqpKeys = eqps.stream()
                .map(UiEqp::eqpKey)
                .filter(Objects::nonNull)
                .toList();

        // 2~5. eqp_key 기반 4 batch query 병렬 의미 없음 (트랜잭션 단일) — 순차 호출
        final Map<Long, TcEqpState> stateByKey = eqpStateStore.findAllByEqpKeyIn(eqpKeys).stream()
                .collect(Collectors.toMap(
                        TcEqpState::eqpKey,
                        s -> s,
                        (a, b) -> a,
                        LinkedHashMap::new
                ));

        final Map<Long, TcEqpStateHist> latestConnByKey = eqpStateHistStore.findLatestConnStateByEqpKeyIn(eqpKeys).stream()
                .collect(Collectors.toMap(
                        TcEqpStateHist::eqpKey,
                        h -> h,
                        (a, b) -> a,
                        LinkedHashMap::new
                ));

        final Map<Long, List<UiEqpParam>> paramsByKey = groupParamsByEqpKey(
                eqpParamStore.findAllByEqpKeyIn(eqpKeys),
                eqps
        );

        final Map<Long, List<UiEqpPortStatus>> portStatusesByKey = eqpPortStatusStore.findAllByEqpKeyIn(eqpKeys).stream()
                .map(UiEqpDbMapper::toDomain)
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(
                        UiEqpPortStatus::eqpKey,
                        LinkedHashMap::new,
                        Collectors.toList()
                ));

        // 6. distinct modelVersionKey → tc_model_param IN
        final Set<Long> modelVersionKeys = eqps.stream()
                .map(UiEqp::modelVersionKey)
                .filter(key -> key > 0L)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        final Map<Long, List<UiModelParam>> modelParamsByVersionKey = modelVersionKeys.isEmpty()
                ? Map.of()
                : modelParamStore.findAllByModelVersionKeyIn(modelVersionKeys).stream()
                        .map(UiModelDbMapper::toDomain)
                        .filter(Objects::nonNull)
                        .collect(Collectors.groupingBy(
                                UiModelParam::modelVersionKey,
                                LinkedHashMap::new,
                                Collectors.toList()
                        ));

        // row 조립
        final List<LiveMapEqpPrefetchRow> rows = new ArrayList<>(eqps.size());
        for (final UiEqp eqp : eqps) {
            final Long eqpKey = eqp.eqpKey();
            final List<UiEqpParam> params = eqpKey == null
                    ? List.of()
                    : paramsByKey.getOrDefault(eqpKey, List.of());
            final List<UiEqpPortStatus> portStatuses = eqpKey == null
                    ? List.of()
                    : portStatusesByKey.getOrDefault(eqpKey, List.of());
            final EqpRuntimeStateView runtimeState = buildRuntimeState(
                    eqpKey == null ? null : stateByKey.get(eqpKey),
                    eqpKey == null ? null : latestConnByKey.get(eqpKey)
            );
            rows.add(new LiveMapEqpPrefetchRow(eqp, params, portStatuses, runtimeState));
        }

        if (log.isDebugEnabled()) {
            log.debug("live-map prefetch 완료. eqpCount={}, paramRows={}, portRows={}, modelParamGroups={}",
                    rows.size(),
                    paramsByKey.values().stream().mapToInt(List::size).sum(),
                    portStatusesByKey.values().stream().mapToInt(List::size).sum(),
                    modelParamsByVersionKey.size());
        }
        return new LiveMapPrefetchSnapshot(List.copyOf(rows), modelParamsByVersionKey);
    }

    /**
     * tc_eqp_param 결과를 eqp_key 기준으로 그룹화하고 PROCESS_TYPE / WAFER_MODE 만 남긴다.
     *
     * <p>over-fetch 방지를 위해 응답 schema 와 service 로직(LiveMapQueryService) 이 실제 사용하는
     * 두 파라미터만 필터링한다. 추가 파라미터가 필요하면 이 필터를 확장한다.</p>
     */
    private Map<Long, List<UiEqpParam>> groupParamsByEqpKey(
            final List<TcEqpParam> rawParams,
            final List<UiEqp> eqps
    ) {
        if (rawParams.isEmpty()) {
            return Map.of();
        }
        // appliedParamVersion 기반 필터링은 service 단계에서 이미 수행하므로 어댑터에서는 paramName 만 거른다.
        final Set<String> wantedNames = Set.of(PROCESS_TYPE_PARAM_NAME, WAFER_MODE_PARAM_NAME);
        final Map<Long, List<UiEqpParam>> grouped = new LinkedHashMap<>();
        for (final TcEqpParam raw : rawParams) {
            if (raw.paramName() == null) {
                continue;
            }
            if (!wantedNames.contains(raw.paramName().toUpperCase(Locale.ROOT))) {
                continue;
            }
            final UiEqpParam ui = UiEqpDbMapper.toDomain(raw);
            if (ui == null) {
                continue;
            }
            grouped.computeIfAbsent(ui.eqpKey(), k -> new ArrayList<>()).add(ui);
        }
        return grouped;
    }

    /**
     * tc_eqp_state + tc_eqp_state_hist 최신 CONN 을 합성해 화면용 runtime state 를 만든다.
     */
    private EqpRuntimeStateView buildRuntimeState(final TcEqpState state, final TcEqpStateHist latestConn) {
        final String controlState = state == null || state.controlState() == null
                ? null
                : state.controlState().name();
        final String eqpState = state == null || state.eqpState() == null
                ? null
                : state.eqpState().name();
        final String connectionState = latestConn == null || latestConn.toState() == null
                ? null
                : trimToNull(latestConn.toState());
        return new EqpRuntimeStateView(controlState, eqpState, connectionState);
    }

    private static String trimToNull(final String value) {
        if (value == null) {
            return null;
        }
        final String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}

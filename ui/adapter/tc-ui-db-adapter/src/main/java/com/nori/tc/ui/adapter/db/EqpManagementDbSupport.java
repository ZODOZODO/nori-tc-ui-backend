package com.nori.tc.ui.adapter.db;

import com.nori.tc.db.core.common.PageRequest;
import com.nori.tc.db.core.eqp.store.TcEqpHsmsStore;
import com.nori.tc.db.core.eqp.store.TcEqpLogStore;
import com.nori.tc.db.core.eqp.store.TcEqpParamStore;
import com.nori.tc.db.core.eqp.store.TcEqpParamVersionStore;
import com.nori.tc.db.core.eqp.store.TcEqpPortStatusStore;
import com.nori.tc.db.core.eqp.store.TcEqpSocketProtocolTypeStore;
import com.nori.tc.db.core.eqp.store.TcEqpSocketStore;
import com.nori.tc.db.core.eqp.store.TcEqpStateHistStore;
import com.nori.tc.db.core.eqp.store.TcEqpStateStore;
import com.nori.tc.db.core.eqp.store.TcEqpStore;
import com.nori.tc.db.core.jar.store.TcJarBusinessStore;
import com.nori.tc.db.core.jar.store.TcJarGatewayStore;
import com.nori.tc.db.core.model.store.TcModelStore;
import com.nori.tc.db.domain.common.eqp.EqpStateType;
import com.nori.tc.db.domain.eqp.TcEqp;
import com.nori.tc.db.domain.eqp.TcEqpHsms;
import com.nori.tc.db.domain.eqp.TcEqpLog;
import com.nori.tc.db.domain.eqp.TcEqpParam;
import com.nori.tc.db.domain.eqp.TcEqpParamVersion;
import com.nori.tc.db.domain.eqp.TcEqpPortStatus;
import com.nori.tc.db.domain.eqp.TcEqpSocket;
import com.nori.tc.db.domain.eqp.TcEqpSocketProtocolType;
import com.nori.tc.db.domain.eqp.TcEqpState;
import com.nori.tc.db.domain.eqp.TcEqpStateHist;
import com.nori.tc.db.domain.jar.TcJarBusiness;
import com.nori.tc.db.domain.jar.TcJarGateway;
import com.nori.tc.db.domain.model.TcModel;
import com.nori.tc.ui.core.eqp.EqpManagementSnapshot;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * EQP 관리 DB 어댑터 공통 조회/스캔 헬퍼입니다.
 *
 * <p>현재 tc-db-core store 계약에는 distinct/latest 조회가 없으므로,
 * 본 헬퍼가 페이지 스캔으로 필요한 데이터를 조합합니다.</p>
 */
@Component
class EqpManagementDbSupport {

    private static final int SCAN_PAGE_LIMIT = 500;
    private static final int STATE_HISTORY_PAGE_LIMIT = 100;

    private final TcEqpStore eqpStore;
    private final TcEqpHsmsStore eqpHsmsStore;
    private final TcEqpSocketStore eqpSocketStore;
    private final TcEqpLogStore eqpLogStore;
    private final TcEqpStateStore eqpStateStore;
    private final TcEqpStateHistStore eqpStateHistStore;
    private final TcEqpPortStatusStore eqpPortStatusStore;
    private final TcEqpParamStore eqpParamStore;
    private final TcEqpParamVersionStore eqpParamVersionStore;
    private final TcJarGatewayStore jarGatewayStore;
    private final TcJarBusinessStore jarBusinessStore;
    private final TcModelStore modelStore;
    private final TcEqpSocketProtocolTypeStore socketProtocolTypeStore;

    EqpManagementDbSupport(
            final TcEqpStore eqpStore,
            final TcEqpHsmsStore eqpHsmsStore,
            final TcEqpSocketStore eqpSocketStore,
            final TcEqpLogStore eqpLogStore,
            final TcEqpStateStore eqpStateStore,
            final TcEqpStateHistStore eqpStateHistStore,
            final TcEqpPortStatusStore eqpPortStatusStore,
            final TcEqpParamStore eqpParamStore,
            final TcEqpParamVersionStore eqpParamVersionStore,
            final TcJarGatewayStore jarGatewayStore,
            final TcJarBusinessStore jarBusinessStore,
            final TcModelStore modelStore,
            final TcEqpSocketProtocolTypeStore socketProtocolTypeStore
    ) {
        this.eqpStore = Objects.requireNonNull(eqpStore, "eqpStore is null");
        this.eqpHsmsStore = Objects.requireNonNull(eqpHsmsStore, "eqpHsmsStore is null");
        this.eqpSocketStore = Objects.requireNonNull(eqpSocketStore, "eqpSocketStore is null");
        this.eqpLogStore = Objects.requireNonNull(eqpLogStore, "eqpLogStore is null");
        this.eqpStateStore = Objects.requireNonNull(eqpStateStore, "eqpStateStore is null");
        this.eqpStateHistStore = Objects.requireNonNull(eqpStateHistStore, "eqpStateHistStore is null");
        this.eqpPortStatusStore = Objects.requireNonNull(eqpPortStatusStore, "eqpPortStatusStore is null");
        this.eqpParamStore = Objects.requireNonNull(eqpParamStore, "eqpParamStore is null");
        this.eqpParamVersionStore = Objects.requireNonNull(eqpParamVersionStore, "eqpParamVersionStore is null");
        this.jarGatewayStore = Objects.requireNonNull(jarGatewayStore, "jarGatewayStore is null");
        this.jarBusinessStore = Objects.requireNonNull(jarBusinessStore, "jarBusinessStore is null");
        this.modelStore = Objects.requireNonNull(modelStore, "modelStore is null");
        this.socketProtocolTypeStore = Objects.requireNonNull(socketProtocolTypeStore, "socketProtocolTypeStore is null");
    }

    Optional<EqpManagementSnapshot> loadSnapshotByEqpId(final String eqpId) {
        final Optional<TcEqp> eqpOptional = eqpStore.findByEqpId(eqpId);
        if (eqpOptional.isEmpty()) {
            return Optional.empty();
        }

        final TcEqp eqp = eqpOptional.get();
        final long eqpKey = eqp.eqpKey();

        final TcModel model = modelStore.findByModelVersionKey(eqp.modelVersionKey()).orElse(null);
        final TcEqpHsms hsms = eqpHsmsStore.findByEqpKey(eqpKey).orElse(null);
        final TcEqpSocket socket = eqpSocketStore.findByEqpKey(eqpKey).orElse(null);
        final TcEqpLog logPolicy = eqpLogStore.findByEqpKey(eqpKey).orElse(null);
        final TcEqpState runtimeState = eqpStateStore.findByEqpKey(eqpKey).orElse(null);
        final String connectionState = resolveLatestConnectionState(eqpKey);
        final List<TcEqpPortStatus> portStatuses = loadAllPortStatuses(eqpKey);
        final List<TcEqpParam> params = loadAllParams(eqpKey);
        final List<TcEqpParamVersion> paramVersionMetas = loadAllParamVersionMetas(eqpKey);
        final TcJarGateway gatewayJar = jarGatewayStore.findByEqpKey(eqpKey).orElse(null);
        final TcJarBusiness businessJar = jarBusinessStore.findByEqpKey(eqpKey).orElse(null);

        return Optional.of(new EqpManagementSnapshot(
                eqp,
                model,
                hsms,
                socket,
                logPolicy,
                runtimeState,
                connectionState,
                portStatuses,
                params,
                paramVersionMetas,
                gatewayJar,
                businessJar
        ));
    }

    List<TcEqp> loadAllEqps() {
        return scanAllPages(eqpStore::findAll);
    }

    List<TcModel> loadAllModels() {
        return scanAllPages(modelStore::findAll);
    }

    List<TcEqpSocketProtocolType> loadAllSocketProtocolTypes() {
        return scanAllPages(socketProtocolTypeStore::findAll);
    }

    List<TcEqpPortStatus> loadAllPortStatuses(final long eqpKey) {
        return scanAllPages(pageRequest -> eqpPortStatusStore.findAllByEqpKey(eqpKey, pageRequest));
    }

    List<TcEqpParam> loadAllParams(final long eqpKey) {
        return scanAllPages(pageRequest -> eqpParamStore.findAllByEqpKey(eqpKey, pageRequest)).stream()
                .sorted(Comparator
                        .comparing(TcEqpParam::paramVersion, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(TcEqpParam::paramName, Comparator.nullsLast(String::compareToIgnoreCase)))
                .toList();
    }

    List<TcEqpParamVersion> loadAllParamVersionMetas(final long eqpKey) {
        return scanAllPages(pageRequest -> eqpParamVersionStore.findAllByEqpKey(eqpKey, pageRequest)).stream()
                .sorted(Comparator.comparing(TcEqpParamVersion::paramVersion, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    Optional<TcJarGateway> findLatestGatewayJarByFileName(final String jarFileName) {
        return loadAllEqps().stream()
                .map(TcEqp::eqpKey)
                .filter(Objects::nonNull)
                .map(jarGatewayStore::findByEqpKey)
                .flatMap(Optional::stream)
                .filter(jar -> sameText(jar.jarFileName(), jarFileName))
                .max(Comparator.comparing(TcJarGateway::updatedAt, Comparator.nullsLast(Comparator.naturalOrder())));
    }

    Optional<TcJarBusiness> findLatestBusinessJarByFileName(final String jarFileName) {
        return loadAllEqps().stream()
                .map(TcEqp::eqpKey)
                .filter(Objects::nonNull)
                .map(jarBusinessStore::findByEqpKey)
                .flatMap(Optional::stream)
                .filter(jar -> sameText(jar.jarFileName(), jarFileName))
                .max(Comparator.comparing(TcJarBusiness::updatedAt, Comparator.nullsLast(Comparator.naturalOrder())));
    }

    private String resolveLatestConnectionState(final long eqpKey) {
        final List<TcEqpStateHist> history = eqpStateHistStore.findAllByEqpKey(
                eqpKey,
                PageRequest.of(0, STATE_HISTORY_PAGE_LIMIT)
        );

        return history.stream()
                .filter(item -> item.stateType() == EqpStateType.CONN)
                .map(TcEqpStateHist::toState)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    private boolean sameText(final String left, final String right) {
        if (left == null || right == null) {
            return false;
        }
        return left.trim().equals(right.trim());
    }

    private <T> List<T> scanAllPages(final UiDbPagedCountSupport.PageFetcher<T> pageFetcher) {
        final List<T> items = new ArrayList<>();
        int offset = 0;

        while (true) {
            final List<T> page = pageFetcher.fetch(PageRequest.of(offset, SCAN_PAGE_LIMIT));
            if (page.isEmpty()) {
                break;
            }

            items.addAll(page);
            if (page.size() < SCAN_PAGE_LIMIT) {
                break;
            }
            offset += SCAN_PAGE_LIMIT;
        }

        return items;
    }
}

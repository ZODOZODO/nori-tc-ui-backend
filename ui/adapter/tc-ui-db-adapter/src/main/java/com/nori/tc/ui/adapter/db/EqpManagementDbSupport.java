package com.nori.tc.ui.adapter.db;

import com.nori.tc.common.shard.ShardGroupResolver;
import com.nori.tc.db.core.common.PageRequest;
import com.nori.tc.db.core.eqp.store.TcEqpHsmsStore;
import com.nori.tc.db.core.eqp.store.TcEqpLogStore;
import com.nori.tc.db.core.eqp.store.TcEqpParamStore;
import com.nori.tc.db.core.eqp.store.TcEqpParamVersionStore;
import com.nori.tc.db.core.eqp.store.TcEqpPlcStore;
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
import com.nori.tc.db.domain.eqp.TcEqpPlc;
import com.nori.tc.db.domain.eqp.TcEqpSocket;
import com.nori.tc.db.domain.eqp.TcEqpSocketProtocolType;
import com.nori.tc.db.domain.eqp.TcEqpState;
import com.nori.tc.db.domain.eqp.TcEqpStateHist;
import com.nori.tc.db.domain.model.TcModel;
import com.nori.tc.ui.adapter.db.mapper.UiEqpDbMapper;
import com.nori.tc.ui.adapter.db.mapper.UiModelDbMapper;
import com.nori.tc.ui.core.eqp.EqpManagementSnapshot;
import com.nori.tc.ui.domain.common.model.UiProtocolType;
import com.nori.tc.ui.domain.eqp.UiEqp;
import com.nori.tc.ui.domain.eqp.UiEqpParam;
import com.nori.tc.ui.domain.eqp.UiEqpParamVersion;
import com.nori.tc.ui.domain.eqp.UiEqpPortStatus;
import com.nori.tc.ui.domain.jar.UiBusinessJar;
import com.nori.tc.ui.domain.jar.UiGatewayJar;
import com.nori.tc.ui.domain.model.UiModel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Locale;

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
    private final TcEqpPlcStore eqpPlcStore;
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
    private final ShardGroupResolver shardGroupResolver;

    EqpManagementDbSupport(
            final TcEqpStore eqpStore,
            final TcEqpHsmsStore eqpHsmsStore,
            final TcEqpSocketStore eqpSocketStore,
            final TcEqpPlcStore eqpPlcStore,
            final TcEqpLogStore eqpLogStore,
            final TcEqpStateStore eqpStateStore,
            final TcEqpStateHistStore eqpStateHistStore,
            final TcEqpPortStatusStore eqpPortStatusStore,
            final TcEqpParamStore eqpParamStore,
            final TcEqpParamVersionStore eqpParamVersionStore,
            final TcJarGatewayStore jarGatewayStore,
            final TcJarBusinessStore jarBusinessStore,
            final TcModelStore modelStore,
            final TcEqpSocketProtocolTypeStore socketProtocolTypeStore,
            final ShardGroupResolver shardGroupResolver
    ) {
        this.eqpStore = Objects.requireNonNull(eqpStore, "eqpStore is null");
        this.eqpHsmsStore = Objects.requireNonNull(eqpHsmsStore, "eqpHsmsStore is null");
        this.eqpSocketStore = Objects.requireNonNull(eqpSocketStore, "eqpSocketStore is null");
        this.eqpPlcStore = Objects.requireNonNull(eqpPlcStore, "eqpPlcStore is null");
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
        this.shardGroupResolver = Objects.requireNonNull(shardGroupResolver, "shardGroupResolver is null");
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
        final TcEqpPlc plc = eqpPlcStore.findByEqpKey(eqpKey).orElse(null);
        final TcEqpLog logPolicy = eqpLogStore.findByEqpKey(eqpKey).orElse(null);
        final TcEqpState runtimeState = eqpStateStore.findByEqpKey(eqpKey).orElse(null);
        final String connectionState = resolveLatestConnectionState(eqpKey);
        final List<UiEqpPortStatus> portStatuses = loadAllPortStatuses(eqpKey);
        final List<UiEqpParam> params = loadAllParams(eqpKey);
        final List<UiEqpParamVersion> paramVersionMetas = loadAllParamVersionMetas(eqpKey);
        final UiGatewayJar gatewayJar = eqp.gatewayJarKey() == null
                ? null
                : jarGatewayStore.findByGatewayJarKey(eqp.gatewayJarKey()).map(UiEqpDbMapper::toDomain).orElse(null);
        final UiBusinessJar businessJar = eqp.businessJarKey() == null
                ? null
                : jarBusinessStore.findByBusinessJarKey(eqp.businessJarKey()).map(UiEqpDbMapper::toDomain).orElse(null);

        return Optional.of(new EqpManagementSnapshot(
                UiEqpDbMapper.toDomain(eqp),
                UiModelDbMapper.toDomain(model),
                UiEqpDbMapper.toDomain(hsms),
                UiEqpDbMapper.toDomain(socket),
                UiEqpDbMapper.toDomain(plc),
                UiEqpDbMapper.toDomain(logPolicy),
                UiEqpDbMapper.toDomain(runtimeState),
                connectionState,
                portStatuses,
                params,
                paramVersionMetas,
                gatewayJar,
                businessJar
        ));
    }

    List<UiEqp> loadAllEqps() {
        return scanAllPages(eqpStore::findAll).stream()
                .map(UiEqpDbMapper::toDomain)
                .toList();
    }

    List<UiGatewayJar> loadAllGatewayJars() {
        return scanAllPages(jarGatewayStore::findAll).stream()
                .map(UiEqpDbMapper::toDomain)
                .toList();
    }

    List<UiBusinessJar> loadAllBusinessJars() {
        return scanAllPages(jarBusinessStore::findAll).stream()
                .map(UiEqpDbMapper::toDomain)
                .toList();
    }

    List<UiModel> loadAllModels() {
        return scanAllPages(modelStore::findAll).stream()
                .map(UiModelDbMapper::toDomain)
                .toList();
    }

    Optional<String> findPassiveListenerGroupRoutePartitionConflict(
            final UiProtocolType interfaceType,
            final String currentEqpId,
            final String eqpIp,
            final int eqpPort,
            final int routePartition,
            final String socketProtocolType
    ) {
        if (interfaceType == null || !isPassiveRoutePartitionValidationTarget(interfaceType, eqpIp, eqpPort)) {
            return Optional.empty();
        }

        final String normalizedCurrentEqpId = normalizeText(currentEqpId);
        final String normalizedEqpIp = normalizeText(eqpIp);
        final String normalizedSocketProtocolType = normalizeText(socketProtocolType);

        return scanAllPages(eqpStore::findAll).stream()
                .filter(peer -> peer != null)
                .map(UiEqpDbMapper::toDomain)
                .filter(peer -> !Objects.equals(normalizeText(peer.eqpId()), normalizedCurrentEqpId))
                .filter(peer -> peer.commInterface() == interfaceType)
                .filter(peer -> sameText(peer.commMode(), "PASSIVE"))
                .filter(peer -> sameText(peer.eqpIp(), normalizedEqpIp))
                .filter(peer -> peer.eqpPort() == eqpPort)
                .filter(peer -> peer.routePartition() != null
                        && !shardGroupResolver.inSameShardGroup(routePartition, peer.routePartition()))
                .filter(peer -> samePassiveListenerGroup(interfaceType, peer.eqpKey(), normalizedSocketProtocolType))
                .map(UiEqp::eqpId)
                .findFirst();
    }

    private boolean samePassiveListenerGroup(
            final UiProtocolType interfaceType,
            final Long peerEqpKey,
            final String normalizedSocketProtocolType
    ) {
        if (peerEqpKey == null) {
            return false;
        }

        if (interfaceType == UiProtocolType.SECS) {
            return true;
        }

        final Optional<TcEqpSocket> peerSocket = eqpSocketStore.findByEqpKey(peerEqpKey);
        if (peerSocket.isEmpty()) {
            // 기존 반쪽 데이터가 남아 있으면 동일 listener-group의 정상 저장이 불가능하므로 선검증 단계에서 차단합니다.
            return true;
        }

        return normalizeText(peerSocket.get().socketProtocolType()).equals(normalizedSocketProtocolType);
    }

    private boolean isPassiveRoutePartitionValidationTarget(
            final UiProtocolType interfaceType,
            final String eqpIp,
            final int eqpPort
    ) {
        return interfaceType != null
                && eqpIp != null
                && !eqpIp.isBlank()
                && eqpPort > 0;
    }

    private boolean sameText(final String left, final String right) {
        return normalizeText(left).equals(normalizeText(right));
    }

    private String normalizeText(final String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    List<TcEqpSocketProtocolType> loadAllSocketProtocolTypes() {
        return scanAllPages(socketProtocolTypeStore::findAll);
    }

    List<UiEqpPortStatus> loadAllPortStatuses(final long eqpKey) {
        return scanAllPages(pageRequest -> eqpPortStatusStore.findAllByEqpKey(eqpKey, pageRequest)).stream()
                .map(UiEqpDbMapper::toDomain)
                .toList();
    }

    List<UiEqpParam> loadAllParams(final long eqpKey) {
        return scanAllPages(pageRequest -> eqpParamStore.findAllByEqpKey(eqpKey, pageRequest)).stream()
                .sorted(Comparator
                        .comparing(TcEqpParam::paramVersion, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(TcEqpParam::paramName, Comparator.nullsLast(String::compareToIgnoreCase)))
                .map(UiEqpDbMapper::toDomain)
                .toList();
    }

    List<UiEqpParamVersion> loadAllParamVersionMetas(final long eqpKey) {
        return scanAllPages(pageRequest -> eqpParamVersionStore.findAllByEqpKey(eqpKey, pageRequest)).stream()
                .sorted(Comparator.comparing(TcEqpParamVersion::paramVersion, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(UiEqpDbMapper::toDomain)
                .toList();
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

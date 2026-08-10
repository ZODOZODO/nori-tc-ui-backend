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
import com.nori.tc.db.domain.eqp.TcEqpPortStatus;
import com.nori.tc.db.domain.eqp.TcEqpSocket;
import com.nori.tc.db.domain.eqp.TcEqpSocketProtocolType;
import com.nori.tc.db.domain.eqp.TcEqpState;
import com.nori.tc.db.domain.eqp.TcEqpStateHist;
import com.nori.tc.db.domain.jar.TcJarBusiness;
import com.nori.tc.db.domain.jar.TcJarGateway;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Locale;
import java.util.Set;
import java.util.function.ToLongFunction;

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

    /**
     * eqpId 목록 기준 배치 스냅샷 조립 — 단건 {@link #loadSnapshotByEqpId(String)} 의 N+1 을 제거합니다.
     *
     * <p>단건과 <b>동일한 매퍼/정렬</b>을 재사용해 스냅샷 13 필드 전부를 동일하게 채웁니다.
     * 설비 수와 무관하게 질의 횟수가 고정이므로, 설비가 늘어도 왕복이 늘지 않습니다.</p>
     *
     * <p>hsms/socket/plc/log/state/portStatus/param/paramVersion 은 store 의 {@code findAllByEqpKeyIn}
     * 배치를, eqp/model/jar 는 전체 스캔을, connectionState 는
     * {@code findLatestConnStateByEqpKeyIn} 을 사용합니다.</p>
     *
     * <p>connectionState 는 단건이 최근 100 행만 훑는 것과 달리 DB 에서 설비별 최신 CONN 1 행을
     * 직접 고르므로, 이력이 100 행을 넘는 설비에서는 배치 쪽이 정확합니다. CONN 이력의
     * {@code to_state} 는 기록 시점에 항상 채워지므로(gateway {@code GatewayEquipmentStatePersistenceService}
     * 의 CONN append 3 곳 모두 리터럴 지정) 단건의 null 건너뛰기와 결과가 갈리지 않습니다.</p>
     *
     * @param eqpIds 조회 대상 설비 ID 목록
     * @return eqpId → 스냅샷 (미존재 설비는 부재)
     */
    Map<String, EqpManagementSnapshot> loadSnapshotsByEqpIds(final List<String> eqpIds) {
        if (eqpIds == null || eqpIds.isEmpty()) {
            return Map.of();
        }

        final Set<String> wantedEqpIds = new HashSet<>(eqpIds);
        final List<TcEqp> eqps = scanAllPages(eqpStore::findAll).stream()
                .filter(eqp -> eqp != null && wantedEqpIds.contains(eqp.eqpId()))
                .toList();
        if (eqps.isEmpty()) {
            return Map.of();
        }

        final List<Long> eqpKeys = eqps.stream().map(TcEqp::eqpKey).toList();

        final Map<Long, TcModel> modelByVersionKey = new HashMap<>();
        for (final TcModel model : scanAllPages(modelStore::findAll)) {
            if (model != null) {
                modelByVersionKey.putIfAbsent(model.modelVersionKey(), model);
            }
        }

        final Map<Long, TcEqpHsms> hsmsByKey = indexByEqpKey(eqpHsmsStore.findAllByEqpKeyIn(eqpKeys), TcEqpHsms::eqpKey);
        final Map<Long, TcEqpSocket> socketByKey = indexByEqpKey(eqpSocketStore.findAllByEqpKeyIn(eqpKeys), TcEqpSocket::eqpKey);
        final Map<Long, TcEqpPlc> plcByKey = indexByEqpKey(eqpPlcStore.findAllByEqpKeyIn(eqpKeys), TcEqpPlc::eqpKey);
        final Map<Long, TcEqpLog> logByKey = indexByEqpKey(eqpLogStore.findAllByEqpKeyIn(eqpKeys), TcEqpLog::eqpKey);
        final Map<Long, TcEqpState> stateByKey = indexByEqpKey(eqpStateStore.findAllByEqpKeyIn(eqpKeys), TcEqpState::eqpKey);

        // portStatus: key 별 group 후 단건 loadAllPortStatuses 와 동일 정렬/매핑 적용.
        // 배치 store 는 정렬을 보장하지 않지만 단건은 portId ASC 로 읽으므로, 정렬하지 않으면
        // 같은 설비의 포트 순서가 두 API 사이에서 달라진다(2026-08-10 라이브 비교에서 1,2,3,4 vs 2,3,4,1).
        final Map<Long, List<TcEqpPortStatus>> rawPortStatusesByKey = new LinkedHashMap<>();
        for (final TcEqpPortStatus row : eqpPortStatusStore.findAllByEqpKeyIn(eqpKeys)) {
            if (row != null) {
                rawPortStatusesByKey.computeIfAbsent(row.eqpKey(), key -> new ArrayList<>()).add(row);
            }
        }

        // param: key 별 group 후 단건 loadAllParams 와 동일 정렬/매핑 적용.
        final Map<Long, List<TcEqpParam>> rawParamsByKey = new LinkedHashMap<>();
        for (final TcEqpParam raw : eqpParamStore.findAllByEqpKeyIn(eqpKeys)) {
            if (raw != null) {
                rawParamsByKey.computeIfAbsent(raw.eqpKey(), key -> new ArrayList<>()).add(raw);
            }
        }

        // paramVersion: key 별 group 후 단건 loadAllParamVersionMetas 와 동일 정렬/매핑 적용.
        final Map<Long, List<TcEqpParamVersion>> rawParamVersionsByKey = new LinkedHashMap<>();
        for (final TcEqpParamVersion raw : eqpParamVersionStore.findAllByEqpKeyIn(eqpKeys)) {
            if (raw != null) {
                rawParamVersionsByKey.computeIfAbsent(raw.eqpKey(), key -> new ArrayList<>()).add(raw);
            }
        }

        final Map<Long, String> connectionStateByKey = new HashMap<>();
        for (final TcEqpStateHist hist : eqpStateHistStore.findLatestConnStateByEqpKeyIn(eqpKeys)) {
            if (hist != null && hist.toState() != null) {
                connectionStateByKey.putIfAbsent(hist.eqpKey(), hist.toState());
            }
        }

        final Map<Long, TcJarGateway> gatewayJarByKey = new HashMap<>();
        for (final TcJarGateway jar : scanAllPages(jarGatewayStore::findAll)) {
            if (jar != null) {
                gatewayJarByKey.putIfAbsent(jar.gatewayJarKey(), jar);
            }
        }
        final Map<Long, TcJarBusiness> businessJarByKey = new HashMap<>();
        for (final TcJarBusiness jar : scanAllPages(jarBusinessStore::findAll)) {
            if (jar != null) {
                businessJarByKey.putIfAbsent(jar.businessJarKey(), jar);
            }
        }

        final Map<String, EqpManagementSnapshot> result = new LinkedHashMap<>();
        for (final TcEqp eqp : eqps) {
            final long eqpKey = eqp.eqpKey();
            result.put(eqp.eqpId(), new EqpManagementSnapshot(
                    UiEqpDbMapper.toDomain(eqp),
                    UiModelDbMapper.toDomain(modelByVersionKey.get(eqp.modelVersionKey())),
                    UiEqpDbMapper.toDomain(hsmsByKey.get(eqpKey)),
                    UiEqpDbMapper.toDomain(socketByKey.get(eqpKey)),
                    UiEqpDbMapper.toDomain(plcByKey.get(eqpKey)),
                    UiEqpDbMapper.toDomain(logByKey.get(eqpKey)),
                    UiEqpDbMapper.toDomain(stateByKey.get(eqpKey)),
                    connectionStateByKey.get(eqpKey),
                    sortAndMapPortStatuses(rawPortStatusesByKey.getOrDefault(eqpKey, List.of())),
                    sortAndMapParams(rawParamsByKey.getOrDefault(eqpKey, List.of())),
                    sortAndMapParamVersions(rawParamVersionsByKey.getOrDefault(eqpKey, List.of())),
                    eqp.gatewayJarKey() == null
                            ? null
                            : UiEqpDbMapper.toDomain(gatewayJarByKey.get(eqp.gatewayJarKey())),
                    eqp.businessJarKey() == null
                            ? null
                            : UiEqpDbMapper.toDomain(businessJarByKey.get(eqp.businessJarKey()))
            ));
        }
        return result;
    }

    private static <T> Map<Long, T> indexByEqpKey(final List<T> rows, final ToLongFunction<T> eqpKeyExtractor) {
        final Map<Long, T> map = new HashMap<>();
        for (final T row : rows) {
            if (row != null) {
                map.putIfAbsent(eqpKeyExtractor.applyAsLong(row), row);
            }
        }
        return map;
    }

    private static List<UiEqpParam> sortAndMapParams(final List<TcEqpParam> rawParams) {
        return rawParams.stream()
                .sorted(Comparator
                        .comparing(TcEqpParam::paramVersion, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(TcEqpParam::paramName, Comparator.nullsLast(String::compareToIgnoreCase)))
                .map(UiEqpDbMapper::toDomain)
                .toList();
    }

    /** 단건은 portId ASC 로 읽으므로(JPA {@code cb.asc(portId)}) 문자열 자연 순서로 맞춥니다. */
    private static List<UiEqpPortStatus> sortAndMapPortStatuses(final List<TcEqpPortStatus> rawPortStatuses) {
        return rawPortStatuses.stream()
                .sorted(Comparator.comparing(TcEqpPortStatus::portId, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(UiEqpDbMapper::toDomain)
                .filter(Objects::nonNull)
                .toList();
    }

    private static List<UiEqpParamVersion> sortAndMapParamVersions(final List<TcEqpParamVersion> rawParamVersions) {
        return rawParamVersions.stream()
                .sorted(Comparator.comparing(TcEqpParamVersion::paramVersion, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(UiEqpDbMapper::toDomain)
                .toList();
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

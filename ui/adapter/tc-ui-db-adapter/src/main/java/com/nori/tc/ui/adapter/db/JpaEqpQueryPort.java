package com.nori.tc.ui.adapter.db;

import com.nori.tc.db.core.common.PageRequest;
import com.nori.tc.db.core.eqp.store.TcEqpParamStore;
import com.nori.tc.db.core.eqp.store.TcEqpParamVersionStore;
import com.nori.tc.db.core.eqp.store.TcEqpStateHistStore;
import com.nori.tc.db.core.eqp.store.TcEqpStateStore;
import com.nori.tc.db.core.eqp.store.TcEqpStore;
import com.nori.tc.db.domain.common.eqp.EqpStateType;
import com.nori.tc.db.domain.eqp.TcEqp;
import com.nori.tc.db.domain.eqp.TcEqpParam;
import com.nori.tc.db.domain.eqp.TcEqpParamVersion;
import com.nori.tc.db.domain.eqp.TcEqpState;
import com.nori.tc.ui.core.exception.UiBadRequestException;
import com.nori.tc.ui.core.model.PagedResponse;
import com.nori.tc.ui.core.port.db.EqpQueryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * {@link EqpQueryPort}의 DB Store 기반 구현체입니다.
 *
 * <p>역할:</p>
 * <ul>
 *   <li>{@code GET /api/eqp} 목록 조회</li>
 *   <li>{@code GET /api/eqp/{eqpId}} 단건 조회</li>
 * </ul>
 *
 * <p>구현 원칙:</p>
 * <ul>
 *   <li>조회 SSOT는 {@code tc_eqp}이며, {@link TcEqpStore}만 사용합니다.</li>
 *   <li>입력값 검증 실패는 {@link UiBadRequestException}(400 대응)로 변환합니다.</li>
 * </ul>
 */
@Repository
public class JpaEqpQueryPort implements EqpQueryPort {

    private static final Logger log = LoggerFactory.getLogger(JpaEqpQueryPort.class);
    private static final int PARAM_VERSION_PAGE_LIMIT = 500;
    private static final int PARAM_VERSION_SCAN_MAX_ROWS = 10_000;
    private static final int CONNECTION_STATE_HISTORY_LIMIT = 100;
    // EDIT 버전은 내부 체크아웃 잠금용이므로 드롭다운 목록에 노출하지 않는다.
    private static final String EDIT_VERSION = "EDIT";

    private final TcEqpStore eqpStore;
    private final TcEqpParamStore eqpParamStore;
    private final TcEqpParamVersionStore eqpParamVersionStore;
    private final TcEqpStateStore eqpStateStore;
    private final TcEqpStateHistStore eqpStateHistStore;

    /**
     * 필수 의존성을 초기화합니다.
     *
     * @param eqpStore tc_eqp Store 포트
     * @param eqpParamStore tc_eqp_param Store 포트
     * @param eqpStateStore tc_eqp_state Store 포트
     * @param eqpStateHistStore tc_eqp_state_hist Store 포트
     */
    public JpaEqpQueryPort(
            final TcEqpStore eqpStore,
            final TcEqpParamStore eqpParamStore,
            final TcEqpParamVersionStore eqpParamVersionStore,
            final TcEqpStateStore eqpStateStore,
            final TcEqpStateHistStore eqpStateHistStore
    ) {
        this.eqpStore = Objects.requireNonNull(eqpStore, "eqpStore is null");
        this.eqpParamStore = Objects.requireNonNull(eqpParamStore, "eqpParamStore is null");
        this.eqpParamVersionStore = Objects.requireNonNull(eqpParamVersionStore, "eqpParamVersionStore is null");
        this.eqpStateStore = Objects.requireNonNull(eqpStateStore, "eqpStateStore is null");
        this.eqpStateHistStore = Objects.requireNonNull(eqpStateHistStore, "eqpStateHistStore is null");
        log.info("JpaEqpQueryPort initialized. source=tc_eqp");
    }

    /**
     * 설비 목록을 페이지 단위로 조회합니다.
     *
     * <p>count 계산은 다음 규칙을 따릅니다.</p>
     * <ol>
     *   <li>현재 페이지가 마지막이면(offset + pageSize)로 즉시 계산</li>
     *   <li>마지막 여부를 확정할 수 없으면 별도 count 스캔 수행</li>
     * </ol>
     *
     * @param pageRequest offset/limit 페이지 요청(없으면 기본 페이지 사용)
     * @return 목록 + count를 포함한 페이지 응답
     */
    @Override
    public PagedResponse<TcEqp> findAll(final PageRequest pageRequest) {
        final PageRequest effectivePage = (pageRequest == null) ? PageRequest.defaultPage() : pageRequest;

        if (log.isDebugEnabled()) {
            log.debug("설비 목록 조회 시작. offset={}, limit={}",
                    effectivePage.offset(), effectivePage.limit());
        }

        try {
            final List<TcEqp> items = eqpStore.findAll(effectivePage);
            final long totalCount = UiDbPagedCountSupport.resolveTotalCount(
                    items,
                    effectivePage,
                    UiDbPagedCountSupport.DEFAULT_COUNT_SCAN_LIMIT,
                    eqpStore::findAll,
                    log,
                    "eqp"
            );

            if (log.isDebugEnabled()) {
                log.debug("설비 목록 조회 완료. offset={}, limit={}, pageSize={}, totalCount={}",
                        effectivePage.offset(), effectivePage.limit(), items.size(), totalCount);
            }

            return PagedResponse.of(items, effectivePage.offset(), effectivePage.limit(), totalCount);
        } catch (RuntimeException e) {
            if (UiDbAdapterExceptionSupport.isBadRequest(e)) {
                log.warn("설비 목록 조회 요청 거부 - 잘못된 입력. offset={}, limit={}, reason={}",
                        effectivePage.offset(), effectivePage.limit(),
                        UiDbAdapterExceptionSupport.resolveMessage(e, "invalid page request"));
                throw new UiBadRequestException("설비 목록 조회 입력이 올바르지 않습니다.", e);
            }
            log.error("설비 목록 조회 실패. offset={}, limit={}",
                    effectivePage.offset(), effectivePage.limit(), e);
            throw e;
        }
    }

    /**
     * 설비 ID(eqpId)로 단건을 조회합니다.
     *
     * @param eqpId 설비 비즈니스 키
     * @return 조회 결과(없으면 빈 Optional)
     */
    @Override
    public Optional<TcEqp> findByEqpId(final String eqpId) {
        if (eqpId == null || eqpId.isBlank()) {
            log.warn("설비 단건 조회 요청 거부 - eqpId가 비어 있습니다.");
            throw new UiBadRequestException("eqpId는 비어 있을 수 없습니다.");
        }

        if (log.isDebugEnabled()) {
            log.debug("설비 단건 조회 시작. eqpId={}", eqpId);
        }

        try {
            final Optional<TcEqp> result = eqpStore.findByEqpId(eqpId);
            if (log.isDebugEnabled()) {
                log.debug("설비 단건 조회 완료. eqpId={}, found={}", eqpId, result.isPresent());
            }
            return result;
        } catch (RuntimeException e) {
            if (UiDbAdapterExceptionSupport.isBadRequest(e)) {
                log.warn("설비 단건 조회 요청 거부 - 잘못된 입력. eqpId={}, reason={}",
                        eqpId, UiDbAdapterExceptionSupport.resolveMessage(e, "invalid eqpId"));
                throw new UiBadRequestException("설비 조회 입력이 올바르지 않습니다.", e);
            }
            log.error("설비 단건 조회 실패. eqpId={}", eqpId, e);
            throw e;
        }
    }

    /**
     * 설비 ID(eqpId)에 매핑된 설비 파라미터 버전 목록을 조회합니다.
     *
     * <p>조회 절차:</p>
     * <ol>
     *   <li>eqpId로 tc_eqp를 조회해 eqpKey를 확보</li>
     *   <li>tc_eqp_param_version을 먼저 스캔해 버전 메타데이터를 수집</li>
     *   <li>legacy 호환을 위해 tc_eqp_param도 페이지 단위로 스캔하며 누락된 paramVersion을 보강</li>
     *   <li>'EDIT' 버전은 체크아웃 잠금용이므로 결과에서 제외</li>
     *   <li>중복 제거 후 내림차순 정렬하여 반환</li>
     * </ol>
     *
     * <p>주의: 비정상적으로 큰 데이터셋에서 과도한 스캔을 방지하기 위해
     * {@link #PARAM_VERSION_SCAN_MAX_ROWS} 상한을 둡니다.</p>
     *
     * @param eqpId 설비 비즈니스 키
     * @return 버전 목록(설비 미존재/버전 미존재 시 빈 목록, EDIT 버전 제외)
     */
    @Override
    public List<String> findParamVersionsByEqpId(final String eqpId) {
        if (eqpId == null || eqpId.isBlank()) {
            log.warn("설비 파라미터 버전 조회 요청 거부 - eqpId가 비어 있습니다.");
            throw new UiBadRequestException("eqpId는 비어 있을 수 없습니다.");
        }

        try {
            final Optional<TcEqp> optionalEqp = eqpStore.findByEqpId(eqpId);
            if (optionalEqp.isEmpty()) {
                if (log.isDebugEnabled()) {
                    log.debug("설비 파라미터 버전 조회 대상이 없습니다. eqpId={}", eqpId);
                }
                return List.of();
            }

            final long eqpKey = optionalEqp.get().eqpKey();
            final Set<String> versionSet = new HashSet<>();
            int versionMetaOffset = 0;
            int offset = 0;

            while (versionMetaOffset < PARAM_VERSION_SCAN_MAX_ROWS) {
                final PageRequest pageRequest = PageRequest.of(versionMetaOffset, PARAM_VERSION_PAGE_LIMIT);
                final List<TcEqpParamVersion> pageMetas = eqpParamVersionStore.findAllByEqpKey(eqpKey, pageRequest);
                final List<String> pageVersions = pageMetas.stream()
                        .map(meta -> meta.paramVersion() == null ? null : meta.paramVersion().trim())
                        .filter(version -> version != null && !version.isBlank() && !EDIT_VERSION.equals(version))
                        .toList();

                versionSet.addAll(pageVersions);

                if (pageMetas.size() < PARAM_VERSION_PAGE_LIMIT) {
                    break;
                }

                versionMetaOffset += PARAM_VERSION_PAGE_LIMIT;
            }

            while (offset < PARAM_VERSION_SCAN_MAX_ROWS) {
                final PageRequest pageRequest = PageRequest.of(offset, PARAM_VERSION_PAGE_LIMIT);
                final var pageParams = eqpParamStore.findAllByEqpKey(eqpKey, pageRequest);
                final List<String> pageVersions = pageParams.stream()
                        .map(param -> param.paramVersion() == null ? null : param.paramVersion().trim())
                        // EDIT 버전은 내부 잠금용이므로 드롭다운 목록에서 제외한다.
                        .filter(version -> version != null && !version.isBlank() && !EDIT_VERSION.equals(version))
                        .toList();

                versionSet.addAll(pageVersions);

                if (pageParams.size() < PARAM_VERSION_PAGE_LIMIT) {
                    break;
                }

                offset += PARAM_VERSION_PAGE_LIMIT;
            }

            if (offset >= PARAM_VERSION_SCAN_MAX_ROWS) {
                log.warn("설비 파라미터 버전 조회 스캔 상한 도달. eqpId={}, maxRows={}", eqpId, PARAM_VERSION_SCAN_MAX_ROWS);
            }

            final List<String> versions = versionSet.stream()
                    .sorted(Comparator.reverseOrder())
                    .toList();

            if (log.isDebugEnabled()) {
                log.debug("설비 파라미터 버전 조회 완료. eqpId={}, versionCount={}", eqpId, versions.size());
            }

            return versions;
        } catch (RuntimeException e) {
            if (UiDbAdapterExceptionSupport.isBadRequest(e)) {
                log.warn("설비 파라미터 버전 조회 요청 거부 - 잘못된 입력. eqpId={}, reason={}",
                        eqpId, UiDbAdapterExceptionSupport.resolveMessage(e, "invalid eqpId"));
                throw new UiBadRequestException("설비 파라미터 버전 조회 입력이 올바르지 않습니다.", e);
            }

            log.error("설비 파라미터 버전 조회 실패. eqpId={}", eqpId, e);
            throw e;
        }
    }

    /**
     * 설비 ID(eqpId)와 버전으로 파라미터 목록을 조회합니다.
     *
     * @param eqpId 설비 비즈니스 키
     * @param version 파라미터 버전 (예: "v1.0", "EDIT")
     * @return 파라미터 목록(설비 미존재 또는 해당 버전 없으면 빈 목록)
     */
    @Override
    public List<TcEqpParam> findParamsByEqpIdAndVersion(final String eqpId, final String version) {
        if (eqpId == null || eqpId.isBlank()) {
            throw new UiBadRequestException("eqpId는 비어 있을 수 없습니다.");
        }
        if (version == null || version.isBlank()) {
            throw new UiBadRequestException("version은 비어 있을 수 없습니다.");
        }

        try {
            final Optional<TcEqp> optionalEqp = eqpStore.findByEqpId(eqpId);
            if (optionalEqp.isEmpty()) {
                if (log.isDebugEnabled()) {
                    log.debug("설비 파라미터 조회 대상이 없습니다. eqpId={}", eqpId);
                }
                return List.of();
            }

            final long eqpKey = optionalEqp.get().eqpKey();
            final List<TcEqpParam> params = eqpParamStore.findAllByEqpKeyAndVersion(eqpKey, version);

            if (log.isDebugEnabled()) {
                log.debug("설비 파라미터 조회 완료. eqpId={}, version={}, count={}", eqpId, version, params.size());
            }

            return params;
        } catch (RuntimeException e) {
            if (UiDbAdapterExceptionSupport.isBadRequest(e)) {
                throw new UiBadRequestException("설비 파라미터 조회 입력이 올바르지 않습니다.", e);
            }
            log.error("설비 파라미터 조회 실패. eqpId={}, version={}", eqpId, version, e);
            throw e;
        }
    }

    /**
     * 설비 ID(eqpId)에 매핑된 체크아웃 상태를 조회합니다.
     *
     * <p>EDIT 버전 파라미터가 존재하면 체크아웃 중이며, 첫 번째 EDIT 행의 created_by가 체크아웃한 사용자입니다.</p>
     *
     * @param eqpId 설비 비즈니스 키
     * @return 설비 존재 시 체크아웃 상태, 설비 미존재 시 empty
     */
    @Override
    public Optional<EqpCheckoutStateView> findCheckoutStateByEqpId(final String eqpId) {
        if (eqpId == null || eqpId.isBlank()) {
            throw new UiBadRequestException("eqpId는 비어 있을 수 없습니다.");
        }

        try {
            final Optional<TcEqp> optionalEqp = eqpStore.findByEqpId(eqpId);
            if (optionalEqp.isEmpty()) {
                if (log.isDebugEnabled()) {
                    log.debug("설비 체크아웃 상태 조회 대상이 없습니다. eqpId={}", eqpId);
                }
                return Optional.empty();
            }

            final long eqpKey = optionalEqp.get().eqpKey();
            final boolean checkedOut = eqpParamStore.existsByEqpKeyAndVersion(eqpKey, EDIT_VERSION);

            if (!checkedOut) {
                return Optional.of(new EqpCheckoutStateView(false, null));
            }

            // EDIT 버전의 첫 번째 파라미터에서 체크아웃한 사용자 ID를 확인한다.
            final List<TcEqpParam> editParams = eqpParamStore.findAllByEqpKeyAndVersion(eqpKey, EDIT_VERSION);
            final String checkedOutBy = editParams.stream()
                    .map(TcEqpParam::createdBy)
                    .filter(by -> by != null && !by.isBlank())
                    .findFirst()
                    .orElse(null);

            if (log.isDebugEnabled()) {
                log.debug("설비 체크아웃 상태 조회 완료. eqpId={}, checkedOut={}, checkedOutBy={}", eqpId, true, checkedOutBy);
            }

            return Optional.of(new EqpCheckoutStateView(true, checkedOutBy));
        } catch (RuntimeException e) {
            if (UiDbAdapterExceptionSupport.isBadRequest(e)) {
                throw new UiBadRequestException("설비 체크아웃 상태 조회 입력이 올바르지 않습니다.", e);
            }
            log.error("설비 체크아웃 상태 조회 실패. eqpId={}", eqpId, e);
            throw e;
        }
    }

    /**
     * 설비 ID(eqpId)에 매핑된 런타임 상태 정보를 조회합니다.
     *
     * <p>조회 소스:</p>
     * <ul>
     *   <li>현재 제어/설비 상태: tc_eqp_state</li>
     *   <li>최신 연결 상태: tc_eqp_state_hist(state_type=CONN)의 최신 이력 to_state</li>
     * </ul>
     *
     * @param eqpId 설비 비즈니스 키
     * @return 설비 존재 시 상태 스냅샷(상태가 비어 있어도 present), 설비 미존재 시 empty
     */
    @Override
    public Optional<EqpRuntimeStateView> findRuntimeStateByEqpId(final String eqpId) {
        if (eqpId == null || eqpId.isBlank()) {
            log.warn("설비 런타임 상태 조회 요청 거부 - eqpId가 비어 있습니다.");
            throw new UiBadRequestException("eqpId는 비어 있을 수 없습니다.");
        }

        try {
            final Optional<TcEqp> optionalEqp = eqpStore.findByEqpId(eqpId);
            if (optionalEqp.isEmpty()) {
                if (log.isDebugEnabled()) {
                    log.debug("설비 런타임 상태 조회 대상이 없습니다. eqpId={}", eqpId);
                }
                return Optional.empty();
            }

            final long eqpKey = optionalEqp.get().eqpKey();

            final Optional<TcEqpState> optionalState = eqpStateStore.findByEqpKey(eqpKey);
            final String controlState = optionalState.map(TcEqpState::controlState).map(Enum::name).orElse(null);
            final String eqpState = optionalState.map(TcEqpState::eqpState).map(Enum::name).orElse(null);

            final String connectionState = eqpStateHistStore
                    .findAllByEqpKey(eqpKey, PageRequest.of(0, CONNECTION_STATE_HISTORY_LIMIT))
                    .stream()
                    .filter(stateHist -> stateHist.stateType() == EqpStateType.CONN)
                    .map(stateHist -> stateHist.toState() == null ? null : stateHist.toState().trim())
                    .filter(state -> state != null && !state.isBlank())
                    .findFirst()
                    .orElse(null);

            return Optional.of(new EqpRuntimeStateView(controlState, eqpState, connectionState));
        } catch (RuntimeException e) {
            if (UiDbAdapterExceptionSupport.isBadRequest(e)) {
                log.warn("설비 런타임 상태 조회 요청 거부 - 잘못된 입력. eqpId={}, reason={}",
                        eqpId, UiDbAdapterExceptionSupport.resolveMessage(e, "invalid eqpId"));
                throw new UiBadRequestException("설비 런타임 상태 조회 입력이 올바르지 않습니다.", e);
            }

            log.error("설비 런타임 상태 조회 실패. eqpId={}", eqpId, e);
            throw e;
        }
    }
}

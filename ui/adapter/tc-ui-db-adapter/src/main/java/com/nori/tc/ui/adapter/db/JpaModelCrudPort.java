package com.nori.tc.ui.adapter.db;

import com.nori.tc.db.core.common.PageRequest;
import com.nori.tc.db.core.model.store.TcModelStore;
import com.nori.tc.db.core.model.upsert.UpsertTcModel;
import com.nori.tc.db.domain.model.TcModel;
import com.nori.tc.ui.core.exception.UiBadRequestException;
import com.nori.tc.ui.core.exception.UiConflictException;
import com.nori.tc.ui.core.model.PagedResponse;
import com.nori.tc.ui.core.port.db.ModelCrudPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link ModelCrudPort}의 DB Store 기반 구현체입니다.
 *
 * <p>정책:</p>
 * <ul>
 *   <li>중복/참조 충돌은 {@link UiConflictException}(409 대응)으로 변환</li>
 *   <li>입력값 오류는 {@link UiBadRequestException}(400 대응)으로 변환</li>
 * </ul>
 *
 * <p>중요 정책:</p>
 * <p>{@code tc_model_version} 삭제 시 {@code tc_eqp.model_version_key} 참조가 남아 있으면
 * 충돌로 판단하여 409 경로를 강제합니다.</p>
 */
@Repository
public class JpaModelCrudPort implements ModelCrudPort {

    private static final Logger log = LoggerFactory.getLogger(JpaModelCrudPort.class);

    private final TcModelStore modelStore;

    /**
     * 필수 의존성을 초기화합니다.
     *
     * @param modelStore tc_model Store 포트
     */
    public JpaModelCrudPort(final TcModelStore modelStore) {
        this.modelStore = Objects.requireNonNull(modelStore, "modelStore is null");
        log.info("JpaModelCrudPort initialized. source=tc_model/tc_model_version");
    }

    /**
     * 모델 정보를 업서트합니다.
     *
     * @param command 저장/수정 명령
     * @return 저장 후 모델 스냅샷
     */
    @Override
    public TcModel upsert(final UpsertTcModel command) {
        if (log.isDebugEnabled()) {
            log.debug("모델 업서트 시작. modelVersionKeyCompatibility={}, modelName={}, modelVersion={}",
                    command == null ? null : command.modelKey(),
                    command == null ? null : command.modelName(),
                    command == null ? null : command.modelVersion());
        }

        try {
            final TcModel saved = modelStore.upsert(command);
            if (log.isDebugEnabled()) {
                log.debug("모델 업서트 완료. modelVersionKey={}, modelKey={}, modelName={}, modelVersion={}",
                        saved.modelVersionKey(), saved.modelKey(), saved.modelName(), saved.modelVersion());
            }
            return saved;
        } catch (RuntimeException e) {
            if (UiDbAdapterExceptionSupport.isBadRequest(e)) {
                log.warn("모델 업서트 요청 거부 - 잘못된 입력. reason={}",
                        UiDbAdapterExceptionSupport.resolveMessage(e, "invalid model upsert command"));
                throw new UiBadRequestException("모델 저장 입력이 올바르지 않습니다.", e);
            }
            if (UiDbAdapterExceptionSupport.isConflict(e)) {
                log.warn("모델 업서트 충돌. reason={}",
                        UiDbAdapterExceptionSupport.resolveMessage(e, "model conflict"));
                throw new UiConflictException("모델 저장 중 충돌이 발생했습니다.", e);
            }
            log.error("모델 업서트 실패.", e);
            throw e;
        }
    }

    /**
     * 모델 버전 키 기준 단건을 조회합니다.
     *
     * @param modelVersionKey 모델 버전 키
     * @return 조회 결과(없으면 빈 Optional)
     */
    @Override
    public Optional<TcModel> findByModelVersionKey(final long modelVersionKey) {
        if (modelVersionKey <= 0) {
            log.warn("모델 단건 조회 요청 거부 - modelVersionKey는 1 이상이어야 합니다. modelVersionKey={}", modelVersionKey);
            throw new UiBadRequestException("modelVersionKey는 1 이상이어야 합니다.");
        }

        if (log.isDebugEnabled()) {
            log.debug("모델 단건 조회 시작. modelVersionKey={}", modelVersionKey);
        }

        try {
            final Optional<TcModel> result = modelStore.findByModelVersionKey(modelVersionKey);
            if (log.isDebugEnabled()) {
                log.debug("모델 단건 조회 완료. modelVersionKey={}, found={}", modelVersionKey, result.isPresent());
            }
            return result;
        } catch (RuntimeException e) {
            if (UiDbAdapterExceptionSupport.isBadRequest(e)) {
                log.warn("모델 단건 조회 요청 거부 - 잘못된 입력. modelVersionKey={}, reason={}",
                        modelVersionKey, UiDbAdapterExceptionSupport.resolveMessage(e, "invalid modelVersionKey"));
                throw new UiBadRequestException("모델 조회 입력이 올바르지 않습니다.", e);
            }
            log.error("모델 단건 조회 실패. modelVersionKey={}", modelVersionKey, e);
            throw e;
        }
    }

    /**
     * 모델 목록을 페이지 단위로 조회합니다.
     *
     * @param pageRequest offset/limit 요청(없으면 기본 페이지 사용)
     * @return 목록 + count 페이지 응답
     */
    @Override
    public PagedResponse<TcModel> findAll(final PageRequest pageRequest) {
        final PageRequest effectivePage = (pageRequest == null) ? PageRequest.defaultPage() : pageRequest;

        if (log.isDebugEnabled()) {
            log.debug("모델 목록 조회 시작. offset={}, limit={}", effectivePage.offset(), effectivePage.limit());
        }

        try {
            final List<TcModel> items = modelStore.findAll(effectivePage);
            final long totalCount = UiDbPagedCountSupport.resolveTotalCount(
                    items,
                    effectivePage,
                    UiDbPagedCountSupport.DEFAULT_COUNT_SCAN_LIMIT,
                    modelStore::findAll,
                    log,
                    "model"
            );

            if (log.isDebugEnabled()) {
                log.debug("모델 목록 조회 완료. offset={}, limit={}, pageSize={}, totalCount={}",
                        effectivePage.offset(), effectivePage.limit(), items.size(), totalCount);
            }

            return PagedResponse.of(items, effectivePage.offset(), effectivePage.limit(), totalCount);
        } catch (RuntimeException e) {
            if (UiDbAdapterExceptionSupport.isBadRequest(e)) {
                log.warn("모델 목록 조회 요청 거부 - 잘못된 입력. offset={}, limit={}, reason={}",
                        effectivePage.offset(), effectivePage.limit(),
                        UiDbAdapterExceptionSupport.resolveMessage(e, "invalid page request"));
                throw new UiBadRequestException("모델 목록 조회 입력이 올바르지 않습니다.", e);
            }
            log.error("모델 목록 조회 실패. offset={}, limit={}",
                    effectivePage.offset(), effectivePage.limit(), e);
            throw e;
        }
    }

    /**
     * 모델 버전 키 기준으로 삭제합니다.
     *
     * <p>FK 참조 충돌이 발생하면 409 예외로 변환합니다.</p>
     *
     * @param modelVersionKey 삭제 대상 모델 버전 키
     */
    @Override
    public void deleteByModelVersionKey(final long modelVersionKey) {
        if (modelVersionKey <= 0) {
            log.warn("모델 삭제 요청 거부 - modelVersionKey는 1 이상이어야 합니다. modelVersionKey={}", modelVersionKey);
            throw new UiBadRequestException("modelVersionKey는 1 이상이어야 합니다.");
        }

        if (log.isDebugEnabled()) {
            log.debug("모델 삭제 시작. modelVersionKey={}", modelVersionKey);
        }

        try {
            modelStore.deleteByModelVersionKey(modelVersionKey);
            log.info("모델 삭제 완료. modelVersionKey={}", modelVersionKey);
        } catch (RuntimeException e) {
            if (UiDbAdapterExceptionSupport.isBadRequest(e)) {
                log.warn("모델 삭제 요청 거부 - 잘못된 입력. modelVersionKey={}, reason={}",
                        modelVersionKey, UiDbAdapterExceptionSupport.resolveMessage(e, "invalid modelVersionKey"));
                throw new UiBadRequestException("모델 삭제 입력이 올바르지 않습니다.", e);
            }
            if (UiDbAdapterExceptionSupport.isConflict(e)) {
                log.warn("모델 삭제 충돌 - 참조 관계 또는 제약 위반. modelVersionKey={}, reason={}",
                        modelVersionKey, UiDbAdapterExceptionSupport.resolveMessage(e, "model reference conflict"));
                throw new UiConflictException("해당 모델을 참조 중인 데이터가 있어 삭제할 수 없습니다.", e);
            }
            log.error("모델 삭제 실패. modelVersionKey={}", modelVersionKey, e);
            throw e;
        }
    }
}

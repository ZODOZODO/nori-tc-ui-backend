package com.nori.tc.ui.adapter.db;

import com.nori.tc.db.core.common.PageRequest;
import com.nori.tc.db.core.model.store.TcModelDcopItemStore;
import com.nori.tc.db.core.model.store.TcModelEventIdStore;
import com.nori.tc.db.core.model.store.TcModelMdfStore;
import com.nori.tc.db.core.model.store.TcModelParamStore;
import com.nori.tc.db.core.model.store.TcModelReportIdStore;
import com.nori.tc.db.core.model.store.TcModelMesMessageStore;
import com.nori.tc.db.core.model.store.TcModelSecsMessageStore;
import com.nori.tc.db.core.model.store.TcModelSocketMessageStore;
import com.nori.tc.db.core.model.store.TcModelStore;
import com.nori.tc.db.core.model.store.TcModelVariableIdStore;
import com.nori.tc.db.core.model.store.TcModelWorkflowStore;
import com.nori.tc.db.core.model.upsert.UpsertTcModelDcopItem;
import com.nori.tc.db.core.model.upsert.UpsertTcModelEventId;
import com.nori.tc.db.core.model.upsert.UpsertTcModelMdf;
import com.nori.tc.db.core.model.upsert.UpsertTcModelParam;
import com.nori.tc.db.core.model.upsert.UpsertTcModelReportId;
import com.nori.tc.db.core.model.upsert.UpsertTcModelMesMessage;
import com.nori.tc.db.core.model.upsert.UpsertTcModelSecsMessage;
import com.nori.tc.db.core.model.upsert.UpsertTcModelSocketMessage;
import com.nori.tc.db.core.model.upsert.UpsertTcModelVariableId;
import com.nori.tc.db.core.model.upsert.UpsertTcModelWorkflow;
import com.nori.tc.db.domain.common.model.DcopCollectionRule;
import com.nori.tc.db.domain.common.model.VariableIdType;
import com.nori.tc.db.domain.model.TcModelDcopItem;
import com.nori.tc.db.domain.model.TcModelMdf;
import com.nori.tc.ui.core.exception.UiBadRequestException;
import com.nori.tc.ui.core.exception.UiNotFoundException;
import com.nori.tc.ui.core.port.db.ModelDetailCommandPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link ModelDetailCommandPort}의 DB Store 기반 구현체입니다.
 *
 * <p>일반 상세 테이블은 node 단위 전체 교체 저장을 사용하고,
 * MDF는 UTF-8/XML 정합성 검증 후 저장합니다.</p>
 */
@Repository
public class JpaModelDetailCommandPort implements ModelDetailCommandPort {

    private static final Logger log = LoggerFactory.getLogger(JpaModelDetailCommandPort.class);
    private static final int DETAIL_PAGE_LIMIT = 500;

    private final TcModelStore modelStore;
    private final TcModelParamStore modelParamStore;
    private final TcModelSecsMessageStore modelSecsMessageStore;
    private final TcModelSocketMessageStore modelSocketMessageStore;
    private final TcModelMesMessageStore modelMesMessageStore;
    private final TcModelVariableIdStore modelVariableIdStore;
    private final TcModelReportIdStore modelReportIdStore;
    private final TcModelEventIdStore modelEventIdStore;
    private final TcModelWorkflowStore modelWorkflowStore;
    private final TcModelMdfStore modelMdfStore;
    private final TcModelDcopItemStore modelDcopItemStore;

    /**
     * 필수 의존성을 초기화합니다.
     */
    public JpaModelDetailCommandPort(
            final TcModelStore modelStore,
            final TcModelParamStore modelParamStore,
            final TcModelSecsMessageStore modelSecsMessageStore,
            final TcModelSocketMessageStore modelSocketMessageStore,
            final TcModelMesMessageStore modelMesMessageStore,
            final TcModelVariableIdStore modelVariableIdStore,
            final TcModelReportIdStore modelReportIdStore,
            final TcModelEventIdStore modelEventIdStore,
            final TcModelWorkflowStore modelWorkflowStore,
            final TcModelMdfStore modelMdfStore,
            final TcModelDcopItemStore modelDcopItemStore
    ) {
        this.modelStore = Objects.requireNonNull(modelStore, "modelStore is null");
        this.modelParamStore = Objects.requireNonNull(modelParamStore, "modelParamStore is null");
        this.modelSecsMessageStore = Objects.requireNonNull(modelSecsMessageStore, "modelSecsMessageStore is null");
        this.modelSocketMessageStore = Objects.requireNonNull(modelSocketMessageStore, "modelSocketMessageStore is null");
        this.modelMesMessageStore = Objects.requireNonNull(modelMesMessageStore, "modelMesMessageStore is null");
        this.modelVariableIdStore = Objects.requireNonNull(modelVariableIdStore, "modelVariableIdStore is null");
        this.modelReportIdStore = Objects.requireNonNull(modelReportIdStore, "modelReportIdStore is null");
        this.modelEventIdStore = Objects.requireNonNull(modelEventIdStore, "modelEventIdStore is null");
        this.modelWorkflowStore = Objects.requireNonNull(modelWorkflowStore, "modelWorkflowStore is null");
        this.modelMdfStore = Objects.requireNonNull(modelMdfStore, "modelMdfStore is null");
        this.modelDcopItemStore = Objects.requireNonNull(modelDcopItemStore, "modelDcopItemStore is null");
        log.info("JpaModelDetailCommandPort initialized.");
    }

    /**
     * 일반 상세 테이블 row를 node 단위 전체 교체 방식으로 저장합니다.
     */
    @Override
    @Transactional
    public void saveDetailRows(
            final long modelVersionKey,
            final String detailNode,
            final List<DetailRowCommand> rows
    ) {
        validateModelVersionKey(modelVersionKey);
        ensureModelExists(modelVersionKey);

        final String normalizedNode = normalizeRequiredText(detailNode, "detailNode").toLowerCase(Locale.ROOT);
        final List<DetailRowCommand> normalizedRows = List.copyOf(rows == null ? List.of() : rows);

        switch (normalizedNode) {
            case "model-parameter" -> replaceModelParams(modelVersionKey, normalizedRows);
            case "secs-message" -> replaceSecsMessages(modelVersionKey, normalizedRows);
            case "socket-message" -> replaceSocketMessages(modelVersionKey, normalizedRows);
            case "mes-message" -> replaceMesMessages(modelVersionKey, normalizedRows);
            case "variableides" -> replaceVariableIds(modelVersionKey, normalizedRows);
            case "reportides" -> replaceReportIds(modelVersionKey, normalizedRows);
            case "eventides" -> replaceEventIds(modelVersionKey, normalizedRows);
            case "workflow" -> replaceWorkflows(modelVersionKey, normalizedRows);
            case "dcop-itemes" -> replaceDcopItems(modelVersionKey, normalizedRows);
            case "mdf" -> throw new UiBadRequestException("MDF는 upload API를 사용해 주세요.");
            default -> throw new UiBadRequestException("지원하지 않는 상세 노드입니다.");
        }

        log.info("모델 상세 row 저장 완료. modelVersionKey={}, detailNode={}, rowCount={}",
                modelVersionKey, normalizedNode, normalizedRows.size());
    }

    /**
     * UTF-8 XML 형식 검증을 통과한 MDF를 저장합니다.
     */
    @Override
    @Transactional
    public TcModelMdf saveMdf(final long modelVersionKey, final String mdfName, final byte[] mdfFile) {
        validateModelVersionKey(modelVersionKey);
        final String normalizedMdfName = normalizeRequiredText(mdfName, "mdfName");
        final String normalizedXml = decodeUtf8Xml(mdfFile);

        ensureModelExists(modelVersionKey);
        validateXmlWellFormed(normalizedXml, modelVersionKey, normalizedMdfName);

        final Optional<TcModelMdf> existingMdf = modelMdfStore.findByModelVersionKey(modelVersionKey);
        final UpsertTcModelMdf command = new UpsertTcModelMdf(
                existingMdf.map(TcModelMdf::mdfKey).orElse(null),
                modelVersionKey,
                normalizedMdfName,
                normalizedXml.getBytes(StandardCharsets.UTF_8)
        );

        final TcModelMdf saved = modelMdfStore.upsert(command);
        log.info("MDF 저장 완료. modelVersionKey={}, mdfKey={}, mdfName={}, updatedAt={}",
                modelVersionKey, saved.mdfKey(), saved.mdfName(), resolveUpdatedAt(saved));
        return saved;
    }

    /**
     * model parameter node를 교체 저장합니다.
     */
    private void replaceModelParams(final long modelVersionKey, final List<DetailRowCommand> rows) {
        loadAllByModelVersionKey(modelVersionKey, modelParamStore::findAllByModelVersionKey)
                .forEach(param -> modelParamStore.deleteByModelParamKey(param.modelParamKey()));

        rows.forEach(row -> modelParamStore.upsert(new UpsertTcModelParam(
                modelVersionKey,
                requiredCell(row, 0, "paramName"),
                optionalCell(row, 1),
                optionalCell(row, 2)
        )));
    }

    /**
     * secs message node를 교체 저장합니다.
     */
    private void replaceSecsMessages(final long modelVersionKey, final List<DetailRowCommand> rows) {
        loadAllByModelVersionKey(modelVersionKey, modelSecsMessageStore::findAllByModelVersionKey)
                .forEach(message -> modelSecsMessageStore.deleteBySecsMsgKey(message.secsMsgKey()));

        rows.forEach(row -> modelSecsMessageStore.upsert(new UpsertTcModelSecsMessage(
                null,
                modelVersionKey,
                requiredCell(row, 0, "secsMsgName"),
                optionalCell(row, 1),
                optionalCell(row, 2)
        )));
    }

    /**
     * mes message node를 교체 저장합니다.
     *
     * <p>컬럼 인덱스: [0]=mesMsgName, [1]=description, [2]=dataIndex</p>
     */
    private void replaceMesMessages(final long modelVersionKey, final List<DetailRowCommand> rows) {
        loadAllByModelVersionKey(modelVersionKey, modelMesMessageStore::findAllByModelVersionKey)
                .forEach(message -> modelMesMessageStore.deleteByMesMsgKey(message.mesMsgKey()));

        rows.forEach(row -> modelMesMessageStore.upsert(new UpsertTcModelMesMessage(
                null,
                modelVersionKey,
                requiredCell(row, 0, "mesMsgName"),
                optionalCell(row, 1),
                optionalCell(row, 2)
        )));
    }

    /**
     * socket message node를 교체 저장합니다.
     */
    private void replaceSocketMessages(final long modelVersionKey, final List<DetailRowCommand> rows) {
        loadAllByModelVersionKey(modelVersionKey, modelSocketMessageStore::findAllByModelVersionKey)
                .forEach(message -> modelSocketMessageStore.deleteByModelVersionKeySocketMsgName(
                        modelVersionKey,
                        message.socketMsgName()
                ));

        rows.forEach(row -> modelSocketMessageStore.upsert(new UpsertTcModelSocketMessage(
                modelVersionKey,
                requiredCell(row, 0, "socketMsgName"),
                optionalCell(row, 1),
                optionalCell(row, 2),
                null
        )));
    }

    /**
     * variable id node를 교체 저장합니다.
     */
    private void replaceVariableIds(final long modelVersionKey, final List<DetailRowCommand> rows) {
        loadAllByModelVersionKey(modelVersionKey, modelVariableIdStore::findAllByModelVersionKey)
                .forEach(variableId -> modelVariableIdStore.deleteByVariableKey(variableId.variableKey()));

        rows.forEach(row -> modelVariableIdStore.upsert(new UpsertTcModelVariableId(
                modelVersionKey,
                parseRequiredEnum(requiredCell(row, 1, "variableIdType"), VariableIdType.class, "variableIdType"),
                requiredCell(row, 0, "variableId"),
                optionalCell(row, 2)
        )));
    }

    /**
     * report id node를 교체 저장합니다.
     */
    private void replaceReportIds(final long modelVersionKey, final List<DetailRowCommand> rows) {
        loadAllByModelVersionKey(modelVersionKey, modelReportIdStore::findAllByModelVersionKey)
                .forEach(reportId -> modelReportIdStore.deleteByReportKey(reportId.reportKey()));

        rows.forEach(row -> modelReportIdStore.upsert(new UpsertTcModelReportId(
                modelVersionKey,
                requiredCell(row, 0, "reportId"),
                optionalCell(row, 1),
                parseBooleanCell(optionalCell(row, 2), "enabled"),
                optionalCell(row, 3)
        )));
    }

    /**
     * event id node를 교체 저장합니다.
     */
    private void replaceEventIds(final long modelVersionKey, final List<DetailRowCommand> rows) {
        loadAllByModelVersionKey(modelVersionKey, modelEventIdStore::findAllByModelVersionKey)
                .forEach(eventId -> modelEventIdStore.deleteByEventKey(eventId.eventKey()));

        rows.forEach(row -> modelEventIdStore.upsert(new UpsertTcModelEventId(
                modelVersionKey,
                requiredCell(row, 0, "eventId"),
                optionalCell(row, 1),
                optionalCell(row, 3),
                parseBooleanCell(optionalCell(row, 2), "enabled")
        )));
    }

    /**
     * workflow node를 교체 저장합니다.
     */
    private void replaceWorkflows(final long modelVersionKey, final List<DetailRowCommand> rows) {
        loadAllByModelVersionKey(modelVersionKey, modelWorkflowStore::findAllByModelVersionKey)
                .forEach(workflow -> modelWorkflowStore.deleteByWorkflowKey(workflow.workflowKey()));

        rows.forEach(row -> modelWorkflowStore.upsert(new UpsertTcModelWorkflow(
                null,
                modelVersionKey,
                requiredCell(row, 0, "workflowName"),
                requiredCell(row, 1, "messageName"),
                optionalCell(row, 2),
                optionalCell(row, 3),
                optionalCell(row, 4),
                requiredCell(row, 5, "actionName"),
                optionalCell(row, 6)
        )));
    }

    /**
     * dcop item node를 교체 저장합니다.
     *
     * <p>Controller 계층에서 SOCKET 모델의 경우 6개 values를 7개(SECS 기준)로 확장한 후
     * 이 메서드에 전달하므로, 항상 SECS 기준 인덱스를 사용합니다.</p>
     *
     * <p>컬럼 인덱스 (확장 후 공통):
     * [0]=dcopItemName, [1]=workflowName, [2]=eventId, [3]=variableId,
     * [4]=collectionRule, [5]=calculationRule, [6]=orderRule</p>
     */
    private void replaceDcopItems(final long modelVersionKey, final List<DetailRowCommand> rows) {
        final List<TcModelDcopItem> existingItems =
                loadAllByModelVersionKey(modelVersionKey, modelDcopItemStore::findAllByModelVersionKey);
        existingItems.forEach(item -> modelDcopItemStore.deleteByModelVersionKeyAndName(modelVersionKey, item.dcopItemName()));

        rows.forEach(row -> modelDcopItemStore.upsert(new UpsertTcModelDcopItem(
                modelVersionKey,
                requiredCell(row, 0, "dcopItemName"),
                optionalCell(row, 1),    // workflowName
                optionalCell(row, 2),    // eventId
                optionalCell(row, 3),    // variableId
                parseOptionalEnum(optionalCell(row, 4), DcopCollectionRule.class, "collectionRule"),
                optionalCell(row, 5),    // calculationRule (자유 텍스트)
                parseOptionalInteger(optionalCell(row, 6), "orderRule")
        )));
    }

    /**
     * 대상 모델이 존재하는지 확인합니다.
     */
    private void ensureModelExists(final long modelVersionKey) {
        if (modelStore.findByModelVersionKey(modelVersionKey).isEmpty()) {
            throw new UiNotFoundException("모델을 찾을 수 없습니다.");
        }
    }

    /**
     * modelVersionKey 유효성을 검증합니다.
     */
    private static void validateModelVersionKey(final long modelVersionKey) {
        if (modelVersionKey <= 0L) {
            throw new UiBadRequestException("modelVersionKey는 1 이상이어야 합니다.");
        }
    }

    /**
     * 필수 문자열을 trim 기반으로 정규화합니다.
     */
    private static String normalizeRequiredText(final String value, final String fieldName) {
        final String normalized = normalizeOptionalText(value);
        if (normalized == null) {
            throw new UiBadRequestException(fieldName + "은 비어 있을 수 없습니다.");
        }
        return normalized;
    }

    /**
     * 선택 문자열은 trim 후 blank면 null로 정규화합니다.
     */
    private static String normalizeOptionalText(final String value) {
        if (value == null) {
            return null;
        }
        final String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    /**
     * row의 특정 셀 값을 null-safe하게 조회합니다.
     */
    private static String cell(final DetailRowCommand row, final int index) {
        if (row == null || row.values() == null || index < 0 || index >= row.values().size()) {
            return null;
        }
        return row.values().get(index);
    }

    /**
     * 필수 셀 값을 조회합니다.
     */
    private static String requiredCell(final DetailRowCommand row, final int index, final String fieldName) {
        return normalizeRequiredText(cell(row, index), fieldName);
    }

    /**
     * 선택 셀 값을 조회합니다.
     */
    private static String optionalCell(final DetailRowCommand row, final int index) {
        return normalizeOptionalText(cell(row, index));
    }

    /**
     * 문자열 boolean 셀을 파싱합니다.
     */
    private static boolean parseBooleanCell(final String value, final String fieldName) {
        final String normalized = normalizeOptionalText(value);
        if (normalized == null) {
            return false;
        }

        return switch (normalized.toLowerCase(Locale.ROOT)) {
            case "true", "1", "y", "yes" -> true;
            case "false", "0", "n", "no" -> false;
            default -> throw new UiBadRequestException(fieldName + " 값이 올바르지 않습니다.");
        };
    }

    /**
     * 선택 정수 셀을 파싱합니다.
     */
    private static Integer parseOptionalInteger(final String value, final String fieldName) {
        final String normalized = normalizeOptionalText(value);
        if (normalized == null) {
            return null;
        }

        try {
            return Integer.parseInt(normalized);
        } catch (NumberFormatException e) {
            throw new UiBadRequestException(fieldName + " 값이 올바르지 않습니다.", e);
        }
    }

    /**
     * 필수 enum 문자열을 파싱합니다.
     */
    private static <T extends Enum<T>> T parseRequiredEnum(
            final String value,
            final Class<T> enumType,
            final String fieldName
    ) {
        final String normalized = normalizeRequiredText(value, fieldName);
        try {
            return Enum.valueOf(enumType, normalized.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new UiBadRequestException(fieldName + " 값이 올바르지 않습니다.", e);
        }
    }

    /**
     * 선택 enum 문자열을 파싱합니다.
     */
    private static <T extends Enum<T>> T parseOptionalEnum(
            final String value,
            final Class<T> enumType,
            final String fieldName
    ) {
        final String normalized = normalizeOptionalText(value);
        if (normalized == null) {
            return null;
        }
        try {
            return Enum.valueOf(enumType, normalized.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new UiBadRequestException(fieldName + " 값이 올바르지 않습니다.", e);
        }
    }

    /**
     * model_version_key + 페이지 계약을 공통 처리하는 유틸리티입니다.
     */
    private <T> List<T> loadAllByModelVersionKey(
            final long modelVersionKey,
            final ModelVersionPageFetcher<T> fetcher
    ) {
        int offset = 0;
        final java.util.ArrayList<T> collected = new java.util.ArrayList<>();

        while (true) {
            final List<T> page = fetcher.fetch(modelVersionKey, PageRequest.of(offset, DETAIL_PAGE_LIMIT));
            if (page == null || page.isEmpty()) {
                break;
            }

            collected.addAll(page);
            if (page.size() < DETAIL_PAGE_LIMIT) {
                break;
            }
            offset += DETAIL_PAGE_LIMIT;
        }

        return List.copyOf(collected);
    }

    /**
     * 업로드 바이트를 엄격한 UTF-8 XML 문자열로 디코딩합니다.
     */
    private static String decodeUtf8Xml(final byte[] xmlBytes) {
        if (xmlBytes == null || xmlBytes.length == 0) {
            throw new UiBadRequestException("업로드할 MDF 파일이 비어 있습니다.");
        }

        try {
            final CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(xmlBytes));
            return stripUtf8Bom(decoded.toString());
        } catch (CharacterCodingException e) {
            throw new UiBadRequestException("MDF 파일은 UTF-8 인코딩이어야 합니다.", e);
        }
    }

    /**
     * XML well-formedness와 외부 엔티티 차단 정책을 함께 검증합니다.
     */
    private void validateXmlWellFormed(
            final String xml,
            final long modelVersionKey,
            final String mdfName
    ) {
        try {
            final DocumentBuilderFactory factory = createSecureDocumentBuilderFactory();
            factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        } catch (ParserConfigurationException e) {
            log.error("안전한 XML 파서를 초기화할 수 없습니다. modelVersionKey={}, mdfName={}",
                    modelVersionKey, mdfName, e);
            throw new IllegalStateException("안전한 XML 파서를 초기화할 수 없습니다.", e);
        } catch (SAXException | IOException e) {
            log.warn("MDF XML 형식 검증 실패. modelVersionKey={}, mdfName={}", modelVersionKey, mdfName, e);
            throw new UiBadRequestException("MDF XML 형식이 올바르지 않습니다.", e);
        }
    }

    /**
     * XXE 등 외부 엔티티 위험을 차단한 XML 파서를 생성합니다.
     */
    private static DocumentBuilderFactory createSecureDocumentBuilderFactory() throws ParserConfigurationException {
        final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory;
    }

    /**
     * UTF-8 BOM이 있으면 제거합니다.
     */
    private static String stripUtf8Bom(final String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        if (value.charAt(0) == '\uFEFF') {
            return value.substring(1);
        }
        return value;
    }

    /**
     * 로그 출력용 updatedAt을 null-safe하게 보정합니다.
     */
    private static OffsetDateTime resolveUpdatedAt(final TcModelMdf saved) {
        return saved.updatedAt() == null ? OffsetDateTime.now() : saved.updatedAt();
    }

    /**
     * model_version_key 기반 페이지 조회 함수 시그니처입니다.
     */
    @FunctionalInterface
    private interface ModelVersionPageFetcher<T> {
        List<T> fetch(long modelVersionKey, PageRequest pageRequest);
    }
}

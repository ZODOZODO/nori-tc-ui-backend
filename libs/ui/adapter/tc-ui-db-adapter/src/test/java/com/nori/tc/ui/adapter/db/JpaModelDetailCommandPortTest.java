package com.nori.tc.ui.adapter.db;

import com.nori.tc.db.core.model.store.TcModelDcopItemStore;
import com.nori.tc.db.core.model.store.TcModelEventIdStore;
import com.nori.tc.db.core.model.store.TcModelMdfStore;
import com.nori.tc.db.core.model.store.TcModelMesMessageStore;
import com.nori.tc.db.core.model.store.TcModelParamStore;
import com.nori.tc.db.core.model.store.TcModelReportIdStore;
import com.nori.tc.db.core.model.store.TcModelSecsMessageStore;
import com.nori.tc.db.core.model.store.TcModelSocketMessageStore;
import com.nori.tc.db.core.model.store.TcModelStore;
import com.nori.tc.db.core.model.store.TcModelVariableIdStore;
import com.nori.tc.db.core.model.store.TcModelWorkflowStore;
import com.nori.tc.db.core.model.upsert.UpsertTcModelMdf;
import com.nori.tc.db.domain.common.model.ModelStatus;
import com.nori.tc.db.domain.common.model.ProtocolType;
import com.nori.tc.db.domain.model.TcModel;
import com.nori.tc.db.domain.model.TcModelMdf;
import com.nori.tc.ui.core.exception.UiBadRequestException;
import com.nori.tc.ui.core.exception.UiNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link JpaModelDetailCommandPort}의 MDF 업로드 검증과 저장 계약을 검증합니다.
 */
class JpaModelDetailCommandPortTest {

    @Test
    @DisplayName("saveMdf는 UTF-8 XML 검증 후 기존 mdf_key를 유지하여 저장합니다")
    void saveMdfValidatesAndUpsertsExistingMdf() {
        final Fixture fixture = new Fixture();
        final TcModel model = model(101L);
        final TcModelMdf existing = mdf(55L, 101L, "OLD_MDF", "<mdf><old/></mdf>");
        final String xml = "<mdf><message name=\"REQ\">OK</message></mdf>";

        when(fixture.modelStore.findByModelVersionKey(101L)).thenReturn(Optional.of(model));
        when(fixture.modelMdfStore.findByModelVersionKey(101L)).thenReturn(Optional.of(existing));
        when(fixture.modelMdfStore.upsert(any())).thenReturn(mdf(55L, 101L, "REQ_MDF", xml));

        final TcModelMdf saved = fixture.port.saveMdf(101L, "REQ_MDF", xml.getBytes(StandardCharsets.UTF_8));

        final ArgumentCaptor<UpsertTcModelMdf> captor = ArgumentCaptor.forClass(UpsertTcModelMdf.class);
        verify(fixture.modelMdfStore).upsert(captor.capture());

        assertEquals(55L, captor.getValue().mdfKey());
        assertEquals("REQ_MDF", captor.getValue().mdfName());
        assertEquals(xml, new String(captor.getValue().mdfFile(), StandardCharsets.UTF_8));
        assertEquals("REQ_MDF", saved.mdfName());
    }

    @Test
    @DisplayName("saveMdf는 잘못된 XML 형식을 400으로 거부합니다")
    void saveMdfRejectsMalformedXml() {
        final Fixture fixture = new Fixture();

        when(fixture.modelStore.findByModelVersionKey(101L)).thenReturn(Optional.of(model(101L)));

        final UiBadRequestException exception = assertThrows(
                UiBadRequestException.class,
                () -> fixture.port.saveMdf(
                        101L,
                        "BROKEN_MDF",
                        "<mdf><message name=\"A\"></mdf>".getBytes(StandardCharsets.UTF_8)
                )
        );

        assertEquals("MDF XML 형식이 올바르지 않습니다.", exception.getMessage());
        verify(fixture.modelMdfStore, never()).upsert(any());
    }

    @Test
    @DisplayName("saveMdf는 대상 모델이 없으면 404를 반환합니다")
    void saveMdfRejectsMissingModel() {
        final Fixture fixture = new Fixture();

        when(fixture.modelStore.findByModelVersionKey(999L)).thenReturn(Optional.empty());

        final UiNotFoundException exception = assertThrows(
                UiNotFoundException.class,
                () -> fixture.port.saveMdf(
                        999L,
                        "REQ_MDF",
                        "<mdf><message name=\"REQ\">OK</message></mdf>".getBytes(StandardCharsets.UTF_8)
                )
        );

        assertEquals("모델을 찾을 수 없습니다.", exception.getMessage());
        verify(fixture.modelMdfStore, never()).upsert(any());
    }

    private static TcModel model(final long modelVersionKey) {
        final OffsetDateTime now = OffsetDateTime.parse("2026-03-13T10:15:30+09:00");
        return new TcModel(
                modelVersionKey,
                11L,
                "MODEL-A",
                null,
                "EDIT",
                ProtocolType.SECS,
                ModelStatus.OPERATE,
                "desc",
                "NORI",
                now,
                now,
                "SYSTEM",
                "SYSTEM"
        );
    }

    private static TcModelMdf mdf(
            final long mdfKey,
            final long modelVersionKey,
            final String mdfName,
            final String xml
    ) {
        return new TcModelMdf(
                mdfKey,
                modelVersionKey,
                mdfName,
                xml.getBytes(StandardCharsets.UTF_8),
                OffsetDateTime.parse("2026-03-13T10:15:30+09:00")
        );
    }

    /**
     * 테스트용 mock fixture입니다.
     */
    private static final class Fixture {

        private final TcModelStore modelStore = mock(TcModelStore.class);
        private final TcModelParamStore modelParamStore = mock(TcModelParamStore.class);
        private final TcModelSecsMessageStore modelSecsMessageStore = mock(TcModelSecsMessageStore.class);
        private final TcModelSocketMessageStore modelSocketMessageStore = mock(TcModelSocketMessageStore.class);
        private final TcModelMesMessageStore modelMesMessageStore = mock(TcModelMesMessageStore.class);
        private final TcModelVariableIdStore modelVariableIdStore = mock(TcModelVariableIdStore.class);
        private final TcModelReportIdStore modelReportIdStore = mock(TcModelReportIdStore.class);
        private final TcModelEventIdStore modelEventIdStore = mock(TcModelEventIdStore.class);
        private final TcModelWorkflowStore modelWorkflowStore = mock(TcModelWorkflowStore.class);
        private final TcModelMdfStore modelMdfStore = mock(TcModelMdfStore.class);
        private final TcModelDcopItemStore modelDcopItemStore = mock(TcModelDcopItemStore.class);
        private final JpaModelDetailCommandPort port = new JpaModelDetailCommandPort(
                modelStore,
                modelParamStore,
                modelSecsMessageStore,
                modelSocketMessageStore,
                modelMesMessageStore,
                modelVariableIdStore,
                modelReportIdStore,
                modelEventIdStore,
                modelWorkflowStore,
                modelMdfStore,
                modelDcopItemStore
        );
    }
}

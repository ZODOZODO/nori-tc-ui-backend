package com.nori.tc.ui.adapter.db;

import com.nori.tc.db.core.common.PageRequest;
import com.nori.tc.db.core.exception.DbDuplicateKeyException;
import com.nori.tc.db.core.eqp.store.TcEqpParamStore;
import com.nori.tc.db.core.eqp.store.TcEqpParamVersionStore;
import com.nori.tc.db.core.eqp.store.TcEqpStore;
import com.nori.tc.db.core.eqp.upsert.UpsertTcEqpParam;
import com.nori.tc.db.core.eqp.upsert.UpsertTcEqpParamVersion;
import com.nori.tc.db.domain.common.model.ProtocolType;
import com.nori.tc.db.domain.eqp.TcEqp;
import com.nori.tc.db.domain.eqp.TcEqpParam;
import com.nori.tc.ui.core.exception.EqpAlreadyCheckedOutException;
import com.nori.tc.ui.core.exception.UiBadRequestException;
import com.nori.tc.ui.core.port.db.EqpParamCommandPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link JpaEqpParamCommandPort}의 checkout 경쟁 제어와 충돌 메시지 정규화를 검증합니다.
 */
class JpaEqpParamCommandPortTest {

    private static final DateTimeFormatter PARAM_VERSION_DATE_FORMATTER = DateTimeFormatter.ofPattern("yy.MM.dd");

    @Test
    @DisplayName("checkout은 설비 잠금 조회 뒤 source version을 EDIT로 복사합니다")
    void checkoutUsesLockedEqpLookup() {
        final Fixture fixture = new Fixture();
        final TcEqp eqp = eqp(1L, "EQP-01");
        final TcEqpParam sourceParam = param(11L, eqp.eqpKey(), "TEMP", "v1", "100", "source", "SYSTEM");
        final TcEqpParam editParam = param(12L, eqp.eqpKey(), "TEMP", "EDIT", "100", "source", "tester");

        when(fixture.eqpStore.findByEqpIdForUpdate("EQP-01")).thenReturn(Optional.of(eqp));
        when(fixture.eqpParamStore.findAllByEqpKeyAndVersion(eqp.eqpKey(), "v1")).thenReturn(List.of(sourceParam));
        when(fixture.eqpParamStore.findAllByEqpKeyAndVersion(eqp.eqpKey(), "EDIT")).thenReturn(List.of(editParam));

        final List<EqpParamCommandPort.EqpParamView> result = fixture.port.checkout("EQP-01", "v1", "tester");

        verify(fixture.eqpStore).findByEqpIdForUpdate("EQP-01");
        verify(fixture.eqpStore, never()).findByEqpId("EQP-01");
        verify(fixture.eqpParamStore).existsByEqpKeyAndVersion(eqp.eqpKey(), "EDIT");
        verify(fixture.eqpParamStore).upsert(any());
        assertEquals(1, result.size());
        assertEquals("TEMP", result.get(0).paramName());
        assertEquals("tester", result.get(0).createdBy());
    }

    @Test
    @DisplayName("checkout은 이미 EDIT가 있으면 현재 편집 사용자를 포함한 409 예외를 반환합니다")
    void checkoutWhenAlreadyCheckedOutReturnsNormalizedConflictMessage() {
        final Fixture fixture = new Fixture();
        final TcEqp eqp = eqp(1L, "EQP-01");

        when(fixture.eqpStore.findByEqpIdForUpdate("EQP-01")).thenReturn(Optional.of(eqp));
        when(fixture.eqpParamStore.existsByEqpKeyAndVersion(eqp.eqpKey(), "EDIT")).thenReturn(true);
        when(fixture.eqpParamStore.findAllByEqpKeyAndVersion(eqp.eqpKey(), "EDIT"))
                .thenReturn(List.of(param(21L, eqp.eqpKey(), "TEMP", "EDIT", "100", "editing", "alice")));

        final EqpAlreadyCheckedOutException exception = assertThrows(
                EqpAlreadyCheckedOutException.class,
                () -> fixture.port.checkout("EQP-01", "v1", "tester")
        );

        assertEquals("설비 파라미터가 이미 체크아웃 중입니다. 현재 편집 사용자: alice.", exception.getMessage());
    }

    @Test
    @DisplayName("checkout은 duplicate key 후 owner 재조회가 비어도 정규화된 409 예외를 반환합니다")
    void checkoutDuplicateKeyWithoutOwnerStillReturnsNormalizedConflictMessage() {
        final Fixture fixture = new Fixture();
        final TcEqp eqp = eqp(1L, "EQP-01");

        when(fixture.eqpStore.findByEqpIdForUpdate("EQP-01")).thenReturn(Optional.of(eqp));
        when(fixture.eqpParamStore.findAllByEqpKeyAndVersion(eqp.eqpKey(), "v1"))
                .thenReturn(List.of(param(31L, eqp.eqpKey(), "TEMP", "v1", "100", "source", "SYSTEM")));
        when(fixture.eqpParamStore.upsert(any()))
                .thenThrow(new DbDuplicateKeyException("duplicate", new RuntimeException("duplicate")));
        when(fixture.eqpParamStore.findAllByEqpKeyAndVersion(eqp.eqpKey(), "EDIT")).thenReturn(List.of());

        final EqpAlreadyCheckedOutException exception = assertThrows(
                EqpAlreadyCheckedOutException.class,
                () -> fixture.port.checkout("EQP-01", "v1", "tester")
        );

        assertEquals(
                "설비 파라미터가 이미 다른 사용자에 의해 체크아웃 중입니다. 잠시 후 다시 시도해 주세요.",
                exception.getMessage()
        );
    }

    @Test
    @DisplayName("saveEditParams는 EDIT 전체를 재구성하여 이름 변경과 삭제를 함께 반영합니다")
    void saveEditParamsRebuildsEditRows() {
        final Fixture fixture = new Fixture();
        final TcEqp eqp = eqp(1L, "EQP-01");

        when(fixture.eqpStore.findByEqpId("EQP-01")).thenReturn(Optional.of(eqp));

        fixture.port.saveEditParams(
                "EQP-01",
                List.of(
                        new EqpParamCommandPort.EqpParamEdit("TEMP_RENAMED", "120", "renamed"),
                        new EqpParamCommandPort.EqpParamEdit("PRESS", "220", "new row")
                ),
                "tester"
        );

        verify(fixture.eqpParamStore).deleteAllByEqpKeyAndVersion(eqp.eqpKey(), "EDIT");

        final ArgumentCaptor<UpsertTcEqpParam> captor = ArgumentCaptor.forClass(UpsertTcEqpParam.class);
        verify(fixture.eqpParamStore, org.mockito.Mockito.times(2)).upsert(captor.capture());

        final List<UpsertTcEqpParam> commands = captor.getAllValues();
        assertEquals("TEMP_RENAMED", commands.get(0).paramName());
        assertEquals("PRESS", commands.get(1).paramName());
        assertEquals("tester", commands.get(0).createdBy());
        assertEquals("tester", commands.get(1).createdBy());
    }

    @Test
    @DisplayName("saveEditParams는 중복된 paramName을 거부합니다")
    void saveEditParamsRejectsDuplicatedParamName() {
        final Fixture fixture = new Fixture();

        final UiBadRequestException exception = assertThrows(
                UiBadRequestException.class,
                () -> fixture.port.saveEditParams(
                        "EQP-01",
                        List.of(
                                new EqpParamCommandPort.EqpParamEdit("TEMP", "100", "row1"),
                                new EqpParamCommandPort.EqpParamEdit(" TEMP ", "200", "row2")
                        ),
                        "tester"
                )
        );

        assertEquals("중복된 paramName은 저장할 수 없습니다. paramName=TEMP", exception.getMessage());
        verify(fixture.eqpStore, never()).findByEqpId("EQP-01");
        verify(fixture.eqpParamStore, never()).deleteAllByEqpKeyAndVersion(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString()
        );
    }

    @Test
    @DisplayName("undoCheckout은 EDIT 버전을 모두 삭제합니다")
    void undoCheckoutDeletesAllEditRows() {
        final Fixture fixture = new Fixture();
        final TcEqp eqp = eqp(1L, "EQP-01");

        when(fixture.eqpStore.findByEqpId("EQP-01")).thenReturn(Optional.of(eqp));

        fixture.port.undoCheckout("EQP-01", "tester");

        verify(fixture.eqpParamStore).deleteAllByEqpKeyAndVersion(eqp.eqpKey(), "EDIT");
    }

    @Test
    @DisplayName("checkin은 오늘 생성된 마지막 버전 다음 값으로 자동 증가합니다")
    void checkinGeneratesNextDailyVersion() {
        final LocalDate currentDate = LocalDate.of(2025, 3, 12);
        final Fixture fixture = new Fixture(currentDate);
        final TcEqp eqp = eqp(1L, "EQP-01");

        when(fixture.eqpStore.findByEqpIdForUpdate("EQP-01")).thenReturn(Optional.of(eqp));
        when(fixture.eqpParamStore.findAllByEqpKey(eqp.eqpKey(), PageRequest.of(0, 500))).thenReturn(List.of(
                param(11L, eqp.eqpKey(), "TEMP", versionText(currentDate, 0), "100", "old", "SYSTEM"),
                param(12L, eqp.eqpKey(), "PRESS", versionText(currentDate, 1), "200", "old", "SYSTEM"),
                param(13L, eqp.eqpKey(), "FLOW", versionText(currentDate.minusDays(1), 9), "300", "old", "SYSTEM"),
                param(14L, eqp.eqpKey(), "LEGACY", "v1", "400", "old", "SYSTEM")
        ));
        when(fixture.eqpParamStore.findAllByEqpKeyAndVersion(eqp.eqpKey(), "EDIT")).thenReturn(List.of(
                param(21L, eqp.eqpKey(), "TEMP", "EDIT", "110", "edit", "tester")
        ));

        fixture.port.checkin("EQP-01", "version-desc", "tester");

        final ArgumentCaptor<UpsertTcEqpParam> captor = ArgumentCaptor.forClass(UpsertTcEqpParam.class);
        verify(fixture.eqpParamStore).upsert(captor.capture());
        assertEquals(versionText(currentDate, 2), captor.getValue().paramVersion());
        assertEquals("edit", captor.getValue().description());

        final ArgumentCaptor<UpsertTcEqpParamVersion> versionCaptor = ArgumentCaptor.forClass(UpsertTcEqpParamVersion.class);
        verify(fixture.eqpParamVersionStore).upsert(versionCaptor.capture());
        assertEquals(versionText(currentDate, 2), versionCaptor.getValue().paramVersion());
        assertEquals("version-desc", versionCaptor.getValue().versionDescription());
        verify(fixture.eqpParamStore).deleteAllByEqpKeyAndVersion(eqp.eqpKey(), "EDIT");
    }

    @Test
    @DisplayName("checkin은 날짜가 바뀌면 시퀀스를 0000부터 다시 시작합니다")
    void checkinResetsDailySequenceOnNextDay() {
        final LocalDate currentDate = LocalDate.of(2025, 3, 13);
        final Fixture fixture = new Fixture(currentDate);
        final TcEqp eqp = eqp(1L, "EQP-01");

        when(fixture.eqpStore.findByEqpIdForUpdate("EQP-01")).thenReturn(Optional.of(eqp));
        when(fixture.eqpParamStore.findAllByEqpKey(eqp.eqpKey(), PageRequest.of(0, 500))).thenReturn(List.of(
                param(11L, eqp.eqpKey(), "TEMP", versionText(currentDate.minusDays(1), 7), "100", "old", "SYSTEM"),
                param(12L, eqp.eqpKey(), "PRESS", "v1", "200", "old", "SYSTEM")
        ));
        when(fixture.eqpParamStore.findAllByEqpKeyAndVersion(eqp.eqpKey(), "EDIT")).thenReturn(List.of(
                param(21L, eqp.eqpKey(), "TEMP", "EDIT", "110", "edit", "tester")
        ));

        fixture.port.checkin("EQP-01", "", "tester");

        final ArgumentCaptor<UpsertTcEqpParam> captor = ArgumentCaptor.forClass(UpsertTcEqpParam.class);
        verify(fixture.eqpParamStore).upsert(captor.capture());
        assertEquals(versionText(currentDate, 0), captor.getValue().paramVersion());

        final ArgumentCaptor<UpsertTcEqpParamVersion> versionCaptor = ArgumentCaptor.forClass(UpsertTcEqpParamVersion.class);
        verify(fixture.eqpParamVersionStore).upsert(versionCaptor.capture());
        assertEquals(versionText(currentDate, 0), versionCaptor.getValue().paramVersion());
        assertEquals(null, versionCaptor.getValue().versionDescription());
    }

    /**
     * 테스트용 mock fixture입니다.
     */
    private static final class Fixture {

        private final TcEqpStore eqpStore = mock(TcEqpStore.class);
        private final TcEqpParamStore eqpParamStore = mock(TcEqpParamStore.class);
        private final TcEqpParamVersionStore eqpParamVersionStore = mock(TcEqpParamVersionStore.class);
        private final JpaEqpParamCommandPort port;

        private Fixture() {
            this(LocalDate.of(2025, 3, 12));
        }

        private Fixture(final LocalDate currentDate) {
            this.port = new JpaEqpParamCommandPort(eqpStore, eqpParamStore, eqpParamVersionStore) {
                @Override
                LocalDate resolveCurrentVersionDate() {
                    return currentDate;
                }
            };
        }
    }

    private static String versionText(final LocalDate date, final int sequence) {
        return date.format(PARAM_VERSION_DATE_FORMATTER) + "." + String.format("%04d", sequence);
    }

    private static TcEqp eqp(final long eqpKey, final String eqpId) {
        final OffsetDateTime now = OffsetDateTime.parse("2026-03-11T10:15:30+09:00");
        return new TcEqp(
                eqpKey,
                eqpId,
                ProtocolType.SECS,
                "ACTIVE",
                false,
                1,
                "127.0.0.1",
                5000,
                101L,
                "v1",
                true,
                now,
                now,
                "SYSTEM",
                "SYSTEM"
        );
    }

    private static TcEqpParam param(
            final long eqpParamKey,
            final long eqpKey,
            final String paramName,
            final String paramVersion,
            final String paramValue,
            final String description,
            final String createdBy
    ) {
        return new TcEqpParam(
                eqpParamKey,
                eqpKey,
                paramName,
                paramVersion,
                paramValue,
                description,
                createdBy,
                OffsetDateTime.parse("2026-03-11T10:15:30+09:00")
        );
    }
}

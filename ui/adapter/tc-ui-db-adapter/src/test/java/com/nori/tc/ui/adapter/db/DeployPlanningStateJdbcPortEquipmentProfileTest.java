package com.nori.tc.ui.adapter.db;

import com.nori.tc.ui.core.deploy.DeployCurrentState;
import com.nori.tc.ui.core.eqp.EqpManagementSnapshot;
import com.nori.tc.ui.core.model.UiEquipmentProfileSnapshot;
import com.nori.tc.ui.domain.common.model.UiModelStatus;
import com.nori.tc.ui.domain.common.model.UiProtocolType;
import com.nori.tc.ui.domain.eqp.UiEqp;
import com.nori.tc.ui.domain.model.UiModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P0-3 characterization — {@code DeployPlanningStateJdbcPort.toEquipmentProfile} 의 현재 동작을 박제합니다.
 *
 * <p>이 매핑은 baseline 커버리지 0%(instruction 306 missed / 0 covered)였고 순서 30(S08 B-3)의
 * R 변경 대상이므로, 변경 전에 22-슬롯 매핑을 고정한다. planner 의 equipmentProfile 은
 * {@code DeployPlanningService.injectEquipmentProfile} 를 거쳐 Kafka payload 로 나가므로
 * 슬롯 값이 곧 gateway 계약이다.</p>
 */
class DeployPlanningStateJdbcPortEquipmentProfileTest {

    private static final String EQP_ID = "EQP-PROFILE-001";
    private static final OffsetDateTime FIXED_AT = OffsetDateTime.parse("2026-08-04T01:02:03Z");

    private EqpManagementDbSupport dbSupport;
    private DeployPlanningStateJdbcPort port;

    @BeforeEach
    void setUp() {
        final DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:deploy-planning-profile;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
                "sa",
                ""
        );
        final NamedParameterJdbcTemplate jdbcTemplate = new NamedParameterJdbcTemplate(dataSource);
        dbSupport = mock(EqpManagementDbSupport.class);
        port = new DeployPlanningStateJdbcPort(jdbcTemplate, dbSupport);

        jdbcTemplate.getJdbcOperations().execute("DROP TABLE IF EXISTS tc_eqp_hsms");
        jdbcTemplate.getJdbcOperations().execute("DROP TABLE IF EXISTS tc_eqp_socket");
        jdbcTemplate.getJdbcOperations().execute("DROP TABLE IF EXISTS tc_eqp");
        jdbcTemplate.getJdbcOperations().execute("""
                CREATE TABLE tc_eqp (
                    eqp_key               BIGINT PRIMARY KEY,
                    eqp_id                VARCHAR(64) NOT NULL,
                    comm_interface        VARCHAR(16),
                    comm_mode             VARCHAR(16),
                    eqp_ip                VARCHAR(64),
                    eqp_port              INT,
                    model_version_key     BIGINT,
                    applied_param_version VARCHAR(64),
                    gateway_jar_key       BIGINT,
                    business_jar_key      BIGINT,
                    route_partition       INT
                )
                """);
        jdbcTemplate.getJdbcOperations().execute("""
                CREATE TABLE tc_eqp_socket (
                    eqp_key              BIGINT PRIMARY KEY,
                    socket_protocol_type VARCHAR(32)
                )
                """);
        jdbcTemplate.getJdbcOperations().execute("""
                CREATE TABLE tc_eqp_hsms (
                    eqp_key   BIGINT PRIMARY KEY,
                    device_id INT
                )
                """);
        jdbcTemplate.update("""
                INSERT INTO tc_eqp (eqp_key, eqp_id, comm_interface, comm_mode, eqp_ip, eqp_port,
                                    model_version_key, applied_param_version, gateway_jar_key,
                                    business_jar_key, route_partition)
                VALUES (11, :eqpId, 'SOCKET', 'ACTIVE', '10.10.10.1', 6000,
                        900, 'PARAM_V1', 3001, 4001, 3)
                """, Map.of("eqpId", EQP_ID));
    }

    @Test
    @DisplayName("characterization: 단건 조회의 equipmentProfile 는 light 필드와 tc_eqp 스냅샷을 그대로 옮깁니다")
    void findCurrentStateByEqpIdMapsEquipmentProfileSlots() {
        when(dbSupport.loadSnapshotByEqpId(EQP_ID)).thenReturn(Optional.of(snapshot()));

        final DeployCurrentState currentState = port.findCurrentStateByEqpId(EQP_ID).orElseThrow();

        // light 필드(SELECT 절)
        assertEquals(11L, currentState.eqpKey());
        assertEquals(EQP_ID, currentState.eqpId());
        assertEquals("SOCKET", currentState.interfaceType());
        assertEquals("ACTIVE", currentState.connectionMode());
        assertEquals("10.10.10.1", currentState.eqpIp());
        assertEquals(6000, currentState.eqpPort());
        assertEquals(900L, currentState.modelVersionKey());
        assertEquals("PARAM_V1", currentState.appliedParamVersion());
        assertEquals(3001L, currentState.gatewayJarRef());
        assertEquals(4001L, currentState.businessJarRef());
        assertEquals(3, currentState.routePartition());

        final UiEquipmentProfileSnapshot profile = currentState.equipmentProfile();
        assertEquals(11L, profile.eqpKey());
        assertEquals(EQP_ID, profile.eqpId());
        assertEquals("SOCKET", profile.commInterfaceType());
        assertEquals("TOKENIZED_TEXT", profile.socketType());
        assertNull(profile.hsmsDeviceId());
        assertEquals("10.10.10.1", profile.eqpIp());
        assertEquals(6000, profile.eqpPort());
        assertEquals(3001L, profile.gatewayJarKey());
        assertEquals(4001L, profile.businessJarKey());
        assertEquals("ACTIVE", profile.connectionMode());
        assertEquals(3, profile.routePartition());
        assertEquals(Boolean.TRUE, profile.enabled());
        assertNull(profile.hsmsSettings());
        assertNull(profile.currentStateSnapshot());
        assertEquals(List.of(), profile.portStatuses());
        assertNull(profile.logPolicy());
        assertEquals(List.of(), profile.params());
        assertEquals(FIXED_AT.toString(), profile.updatedAt());
        assertNull(profile.desiredState());
        assertNull(profile.plcSettings());
        assertEquals("TOKENIZED_TEXT", profile.socketSettings().socketProtocolType());
        assertEquals("ACTIVE", profile.socketSettings().connectionMode());
        assertEquals("UTF-8", profile.socketSettings().charset());
    }

    /**
     * characterization — 현재 이 경로의 modelKey 슬롯은 <b>모델 정체성</b>({@code model().modelKey()})이다.
     *
     * <p>박제만 한다. 다른 2벌(DeployServiceSnapshotSupport / DeployServiceCommandSupport)과 소비 측
     * gateway 는 같은 슬롯을 modelVersionKey 로 취급하므로 이 값은 이상 지점이며, 순서 30(S08 B-3 / 결정 D2)이
     * 슬롯 의미를 modelVersionKey 로 통일한다.</p>
     */
    @Test
    @DisplayName("characterization: JDBC planner 경로의 modelKey 슬롯은 현재 모델 정체성입니다(D2 대상)")
    void equipmentProfileModelKeySlotCurrentlyCarriesModelIdentity() {
        when(dbSupport.loadSnapshotByEqpId(EQP_ID)).thenReturn(Optional.of(snapshot()));

        final UiEquipmentProfileSnapshot profile =
                port.findCurrentStateByEqpId(EQP_ID).orElseThrow().equipmentProfile();

        assertEquals(500L, profile.modelKey());
    }

    @Test
    @DisplayName("characterization: 배치 light 조회는 equipmentProfile 를 싣지 않습니다(R2)")
    void findCurrentLightStatesByEqpIdsOmitsEquipmentProfile() {
        final Map<String, DeployCurrentState> states = port.findCurrentLightStatesByEqpIds(List.of(EQP_ID));

        assertEquals(1, states.size());
        assertNull(states.get(EQP_ID).equipmentProfile());
        assertEquals(900L, states.get(EQP_ID).modelVersionKey());
    }

    private static EqpManagementSnapshot snapshot() {
        return new EqpManagementSnapshot(
                new UiEqp(
                        11L,
                        EQP_ID,
                        1L,
                        UiProtocolType.SOCKET,
                        "ACTIVE",
                        false,
                        3,
                        "10.10.10.1",
                        6000,
                        900L,
                        "PARAM_V1",
                        3001L,
                        4001L,
                        true,
                        FIXED_AT,
                        FIXED_AT,
                        "SYSTEM",
                        "SYSTEM"
                ),
                // modelKey(정체성) 500 ≠ modelVersionKey(버전) 900 — 슬롯 의미를 구분할 수 있게 서로 다르게 둔다.
                new UiModel(
                        900L,
                        500L,
                        "MODEL-A",
                        null,
                        "v1",
                        UiProtocolType.SOCKET,
                        UiModelStatus.OPERATE,
                        null,
                        null,
                        FIXED_AT,
                        FIXED_AT,
                        "SYSTEM",
                        "SYSTEM"
                ),
                null,
                new com.nori.tc.ui.domain.eqp.UiEqpSocket(
                        11L,
                        "TOKENIZED_TEXT",
                        "UTF-8",
                        true,
                        30,
                        5000,
                        5000,
                        65536,
                        true,
                        FIXED_AT,
                        FIXED_AT
                ),
                null,
                null,
                null,
                null,
                List.of(),
                List.of(),
                List.of(),
                null,
                null
        );
    }
}

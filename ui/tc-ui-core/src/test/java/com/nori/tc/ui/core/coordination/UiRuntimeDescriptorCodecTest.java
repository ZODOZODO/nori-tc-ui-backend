package com.nori.tc.ui.core.coordination;

import com.nori.tc.ui.core.coordination.model.UiRuntimeDescriptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * descriptor 생산 계약을 고정합니다(§11.1 Business + §12.3 Gateway).
 *
 * <p>이 계약의 핵심은 <b>부재와 빈 값의 의미가 다르다</b>는 것입니다. 값을 null 로 명시해 써 넣으면
 * 소비자가 "빈 값으로 교체" 로 읽고, 반대로 Gateway 필드를 통째로 빼면 overlay 가 조용히 DB baseline 으로
 * 떨어집니다 — 둘 다 실패가 아니라 무동작이라 테스트가 없으면 드러나지 않습니다.</p>
 */
class UiRuntimeDescriptorCodecTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final UiRuntimeDescriptorCodec codec = new UiRuntimeDescriptorCodec(objectMapper);

    @Test
    @DisplayName("미변경 필드는 JSON 에 아예 나타나지 않는다")
    void absentFieldsAreOmitted() {
        final String json = codec.write(new UiRuntimeDescriptor(
                "EQP001", false, null, null, null, null, null, null,
                null, null, true, null, null));

        assertFalse(json.contains("modelParams"), "null 은 '미변경' 이므로 키 자체가 없어야 합니다.");
        assertFalse(json.contains("plcEnabledPollGroups"));
        assertFalse(json.contains("gatewayDesiredState"));
        assertFalse(json.contains("routePartition"));
    }

    @Test
    @DisplayName("빈 컬렉션은 '빈 값으로 교체' 로 직렬화된다")
    void emptyCollectionsAreEmittedAsEmpty() {
        final String json = codec.write(new UiRuntimeDescriptor(
                "EQP001", false, null, null, null, null, null, null,
                Map.of(), Map.of(), true, UiRuntimeDescriptor.GATEWAY_STATE_ENDED, List.of()));

        assertTrue(json.contains("\"modelParams\":{}"));
        assertTrue(json.contains("\"eqpParams\":{}"));
        assertTrue(json.contains("\"plcEnabledPollGroups\":[]"),
                "빈 배열은 '폴 그룹 전부 해제' 를 뜻하므로 부재와 구분돼야 합니다.");
        assertTrue(json.contains("\"gatewayDesiredState\":\"ENDED\""));
    }

    @Test
    @DisplayName("Gateway 가 읽는 3 필드를 생산한다")
    void gatewayFieldsAreProduced() {
        final String json = codec.write(new UiRuntimeDescriptor(
                "EQP001", false, 10L, 1_001L, 3, 20L, "flow.jar", null,
                null, null, true, UiRuntimeDescriptor.GATEWAY_STATE_STARTED, List.of("G1", "G2")));

        assertTrue(json.contains("\"routePartition\":3"));
        assertTrue(json.contains("\"gatewayDesiredState\":\"STARTED\""));
        assertTrue(json.contains("\"plcEnabledPollGroups\":[\"G1\",\"G2\"]"));
        assertTrue(json.contains("\"schemaVersion\":2"), "소비자 코덱이 요구하는 schemaVersion 이 있어야 합니다.");
    }

    @Test
    @DisplayName("gatewayDesiredState 는 STARTED/ENDED 만 허용한다")
    void gatewayDesiredStateIsConstrained() {
        assertThrows(IllegalArgumentException.class, () -> new UiRuntimeDescriptor(
                "EQP001", false, null, null, null, null, null, null,
                null, null, true, "PAUSED", null));
    }

    @Test
    @DisplayName("삭제 tombstone 은 Gateway 연결 의도를 ENDED 로 알린다")
    void deleteTombstoneEndsGatewayConnection() {
        final UiRuntimeDescriptor tombstone =
                new UiEqpDescriptorFactory().forDelete("EQP001", null);

        assertTrue(tombstone.deleted());
        assertEquals(UiRuntimeDescriptor.GATEWAY_STATE_ENDED, tombstone.gatewayDesiredState());
        assertEquals(List.of(), tombstone.plcEnabledPollGroups(), "삭제 설비는 폴 그룹을 전부 해제해야 합니다.");
    }
}

package com.nori.tc.ui.core.service;

import com.nori.tc.ui.core.exception.UiBadRequestException;
import com.nori.tc.ui.core.livemap.LiveMapObject;
import com.nori.tc.ui.core.livemap.LiveMapObjectKind;
import com.nori.tc.ui.core.livemap.UpsertLiveMapPlacement;
import com.nori.tc.ui.core.port.db.LiveMapLayoutStorePort;
import com.nori.tc.ui.core.port.db.LiveMapObjectStorePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AC3 — EQUIPMENT 배치 eqp_key 필수 / STRUCTURE 배치 eqp_key 금지 / 미등록·타라인 eqp_key 400 검증.
 *
 * <p>FK 500 이 아닌 명시 400(UiBadRequestException)으로 거부됨을 확인한다.</p>
 */
class LiveMapLayoutServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LiveMapLayoutStorePort layoutStore;
    private LiveMapObjectStorePort objectStore;
    private LiveMapLayoutService service;

    @BeforeEach
    void setUp() {
        layoutStore = mock(LiveMapLayoutStorePort.class);
        objectStore = mock(LiveMapObjectStorePort.class);
        service = new LiveMapLayoutService(layoutStore, objectStore);
    }

    @Test
    void equipmentPlacementWithoutEqpKeyIsRejected() {
        when(objectStore.findByKey(10L)).thenReturn(Optional.of(equipmentObject()));
        final List<UpsertLiveMapPlacement> placements = List.of(
                new UpsertLiveMapPlacement(10L, null, transform()));

        assertThrows(UiBadRequestException.class, () -> service.putLayout(7L, null, placements));
    }

    @Test
    void structurePlacementWithEqpKeyIsRejected() {
        when(objectStore.findByKey(20L)).thenReturn(Optional.of(structureObject()));
        final List<UpsertLiveMapPlacement> placements = List.of(
                new UpsertLiveMapPlacement(20L, 5L, transform()));

        assertThrows(UiBadRequestException.class, () -> service.putLayout(7L, null, placements));
    }

    @Test
    void unregisteredObjectKeyIsRejected() {
        when(objectStore.findByKey(99L)).thenReturn(Optional.empty());
        final List<UpsertLiveMapPlacement> placements = List.of(
                new UpsertLiveMapPlacement(99L, null, transform()));

        assertThrows(UiBadRequestException.class, () -> service.putLayout(7L, null, placements));
    }

    @Test
    void unknownEqpKeyIsRejected() {
        when(objectStore.findByKey(10L)).thenReturn(Optional.of(equipmentObject()));
        when(layoutStore.findEqpLineKey(5L)).thenReturn(Optional.empty());
        final List<UpsertLiveMapPlacement> placements = List.of(
                new UpsertLiveMapPlacement(10L, 5L, transform()));

        assertThrows(UiBadRequestException.class, () -> service.putLayout(7L, null, placements));
    }

    @Test
    void eqpKeyFromDifferentLineIsRejected() {
        when(objectStore.findByKey(10L)).thenReturn(Optional.of(equipmentObject()));
        when(layoutStore.findEqpLineKey(5L)).thenReturn(Optional.of(99L)); // 다른 라인
        final List<UpsertLiveMapPlacement> placements = List.of(
                new UpsertLiveMapPlacement(10L, 5L, transform()));

        assertThrows(UiBadRequestException.class, () -> service.putLayout(7L, null, placements));
    }

    @Test
    void validEquipmentAndStructurePlacementsAreSaved() {
        when(objectStore.findByKey(10L)).thenReturn(Optional.of(equipmentObject()));
        when(objectStore.findByKey(20L)).thenReturn(Optional.of(structureObject()));
        when(layoutStore.findEqpLineKey(5L)).thenReturn(Optional.of(7L)); // 동일 라인
        when(layoutStore.saveLayout(eq(7L), any(), anyList())).thenReturn(100L);

        final List<UpsertLiveMapPlacement> placements = List.of(
                new UpsertLiveMapPlacement(10L, 5L, transform()),
                new UpsertLiveMapPlacement(20L, null, transform()));

        assertEquals(100L, service.putLayout(7L, null, placements));
        verify(layoutStore).saveLayout(eq(7L), any(), anyList());
    }

    @Test
    void transformJsonMissingSchemaVersionIsRejected() {
        when(objectStore.findByKey(20L)).thenReturn(Optional.of(structureObject()));
        final ObjectNode noVersion = MAPPER.createObjectNode().put("x", 1);
        final List<UpsertLiveMapPlacement> placements = List.of(
                new UpsertLiveMapPlacement(20L, null, noVersion));

        assertThrows(UiBadRequestException.class, () -> service.putLayout(7L, null, placements));
    }

    @Test
    void canvasJsonMissingSchemaVersionIsRejected() {
        final ObjectNode noVersion = MAPPER.createObjectNode().put("width", 1920);

        assertThrows(UiBadRequestException.class, () -> service.putLayout(7L, noVersion, List.of()));
    }

    @Test
    void getLayoutReturnsEmptyCanvasForUnsavedLine() {
        when(layoutStore.findByLineKey(7L)).thenReturn(Optional.empty());

        final var view = service.getLayout(7L);

        assertEquals(7L, view.lineKey());
        assertNull(view.layoutKey());
        assertTrue(view.placements().isEmpty());
        assertTrue(view.objects().isEmpty());
    }

    private static JsonNode transform() {
        return MAPPER.createObjectNode()
                .put("v", 1)
                .put("x", 320)
                .put("y", 180)
                .put("rotation", 0)
                .put("scaleX", 1)
                .put("scaleY", 1)
                .put("skewX", 0)
                .put("skewY", 0)
                .put("zIndex", 5)
                .put("depth", 12);
    }

    private static LiveMapObject equipmentObject() {
        return new LiveMapObject(10L, "pump-01", LiveMapObjectKind.EQUIPMENT, shape(), true);
    }

    private static LiveMapObject structureObject() {
        return new LiveMapObject(20L, "wall-01", LiveMapObjectKind.STRUCTURE, shape(), true);
    }

    private static JsonNode shape() {
        final ObjectNode root = MAPPER.createObjectNode();
        root.put("v", 1);
        root.putObject("root").put("className", "Group");
        return root;
    }
}

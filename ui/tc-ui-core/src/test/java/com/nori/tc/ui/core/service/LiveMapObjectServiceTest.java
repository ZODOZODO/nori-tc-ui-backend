package com.nori.tc.ui.core.service;

import com.nori.tc.ui.core.exception.UiBadRequestException;
import com.nori.tc.ui.core.livemap.LiveMapObject;
import com.nori.tc.ui.core.livemap.LiveMapObjectKind;
import com.nori.tc.ui.core.port.db.LiveMapObjectStorePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link LiveMapObjectService} 입력 검증 단위 테스트(AC1 객체 CRUD 커버).
 */
class LiveMapObjectServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LiveMapObjectStorePort store;
    private LiveMapObjectService service;

    @BeforeEach
    void setUp() {
        store = mock(LiveMapObjectStorePort.class);
        service = new LiveMapObjectService(store);
    }

    @Test
    void blankObjectNameIsRejected() {
        assertThrows(UiBadRequestException.class, () -> service.createObject("  ", "STRUCTURE", shape()));
    }

    @Test
    void unknownObjectKindIsRejected() {
        assertThrows(UiBadRequestException.class, () -> service.createObject("wall", "PIPE", shape()));
    }

    @Test
    void shapeJsonMissingSchemaVersionIsRejected() {
        final JsonNode noVersion = MAPPER.createObjectNode().putObject("root").put("className", "Group");
        assertThrows(UiBadRequestException.class, () -> service.createObject("wall", "STRUCTURE", noVersion));
    }

    @Test
    void createObjectAcceptsLowercaseKindAndReturnsSavedObject() {
        when(store.save(eq("wall"), eq(LiveMapObjectKind.STRUCTURE), any())).thenReturn(20L);
        when(store.findByKey(20L)).thenReturn(Optional.of(
                new LiveMapObject(20L, "wall", LiveMapObjectKind.STRUCTURE, shape(), true)));

        final LiveMapObject saved = service.createObject("wall", "structure", shape());

        assertEquals(20L, saved.objectKey());
        assertEquals(LiveMapObjectKind.STRUCTURE, saved.objectKind());
    }

    @Test
    void updateNonExistentObjectIsRejected() {
        when(store.update(eq(99L), any(), any(), any())).thenReturn(false);
        assertThrows(UiBadRequestException.class, () -> service.updateObject(99L, "wall", "STRUCTURE", shape()));
    }

    @Test
    void deleteNonExistentObjectIsRejected() {
        when(store.delete(99L)).thenReturn(false);
        assertThrows(UiBadRequestException.class, () -> service.deleteObject(99L));
    }

    private static JsonNode shape() {
        final var root = MAPPER.createObjectNode();
        root.put("v", 1);
        root.putObject("root").put("className", "Group");
        return root;
    }
}

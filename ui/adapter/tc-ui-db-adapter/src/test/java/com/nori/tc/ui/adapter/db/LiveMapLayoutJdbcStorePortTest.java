package com.nori.tc.ui.adapter.db;

import com.nori.tc.ui.core.livemap.UpsertLiveMapPlacement;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AC1(PUT 경로) — saveLayout 이 layout upsert(ON CONFLICT + CAST AS JSONB + RETURNING) 후
 * 배치를 delete-then-insert 로 replace 하는지 SQL 레벨 검증.
 */
class LiveMapLayoutJdbcStorePortTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void saveLayoutUpsertsCanvasThenReplacesPlacements() {
        final NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        final LiveMapLayoutJdbcStorePort store = new LiveMapLayoutJdbcStorePort(jdbcTemplate, MAPPER);
        when(jdbcTemplate.queryForObject(any(String.class), any(MapSqlParameterSource.class), eq(Long.class)))
                .thenReturn(100L);

        final ObjectNode canvas = MAPPER.createObjectNode().put("v", 1).put("width", 1920);
        final ObjectNode transform = MAPPER.createObjectNode().put("v", 1);
        final List<UpsertLiveMapPlacement> placements = List.of(
                new UpsertLiveMapPlacement(10L, 5L, transform));

        final long layoutKey = store.saveLayout(7L, canvas, placements);

        assertEquals(100L, layoutKey);

        final ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForObject(sqlCaptor.capture(), any(MapSqlParameterSource.class), eq(Long.class));
        final String upsertSql = sqlCaptor.getValue();
        assertTrue(upsertSql.contains("ON CONFLICT (line_key)"));
        assertTrue(upsertSql.contains("CAST(:canvasJson AS JSONB)"));
        assertTrue(upsertSql.contains("RETURNING layout_key"));

        // DELETE(배치 비우기) 1회 + INSERT(배치 1건) 1회 = 2회 update
        verify(jdbcTemplate, times(2)).update(any(String.class), any(MapSqlParameterSource.class));
    }
}

package com.nori.tc.ui.adapter.db;

import com.nori.tc.ui.core.deploy.DeployActiveEqpConflict;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeployJdbcStoreSupportTest {

    @Test
    @DisplayName("active 설비 충돌 조회는 excludedDeployKey가 없으면 비교 조건을 붙이지 않습니다")
    void findActiveEqpConflictsOmitsExcludedDeployKeyPredicateWhenNull() {
        final NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        final DeployJdbcStoreSupport support = new DeployJdbcStoreSupport(jdbcTemplate, new ObjectMapper());
        when(jdbcTemplate.<DeployActiveEqpConflict>query(any(String.class), any(MapSqlParameterSource.class), anyRowMapper()))
                .thenReturn(List.<DeployActiveEqpConflict>of());

        support.findActiveEqpConflicts(List.of("PHOTO_001", "PHOTO_002"), null);

        final var sqlCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        final var parameterCaptor = org.mockito.ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbcTemplate).<DeployActiveEqpConflict>query(sqlCaptor.capture(), parameterCaptor.capture(), anyRowMapper());

        final String sql = sqlCaptor.getValue();
        final MapSqlParameterSource parameters = parameterCaptor.getValue();
        assertFalse(sql.contains("excludedDeployKey"));
        assertEquals(List.of("PHOTO_001", "PHOTO_002"), parameters.getValue("eqpIds"));
        assertFalse(parameters.hasValue("excludedDeployKey"));
    }

    @Test
    @DisplayName("active 설비 충돌 조회는 excludedDeployKey가 있으면 self 제외 조건을 붙입니다")
    void findActiveEqpConflictsAddsExcludedDeployKeyPredicateWhenPresent() {
        final NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        final DeployJdbcStoreSupport support = new DeployJdbcStoreSupport(jdbcTemplate, new ObjectMapper());
        when(jdbcTemplate.<DeployActiveEqpConflict>query(any(String.class), any(MapSqlParameterSource.class), anyRowMapper()))
                .thenReturn(List.<DeployActiveEqpConflict>of());

        support.findActiveEqpConflicts(List.of("PHOTO_001"), 7001L);

        final var sqlCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        final var parameterCaptor = org.mockito.ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbcTemplate).<DeployActiveEqpConflict>query(sqlCaptor.capture(), parameterCaptor.capture(), anyRowMapper());

        final String sql = sqlCaptor.getValue();
        final MapSqlParameterSource parameters = parameterCaptor.getValue();
        assertTrue(sql.contains("AND d.deploy_key <> :excludedDeployKey"));
        assertEquals(7001L, parameters.getValue("excludedDeployKey"));
    }

    private static <T> RowMapper<T> anyRowMapper() {
        return any();
    }
}

package com.nori.tc.ui.adapter.db;

import com.nori.tc.ui.core.livemap.LiveMapObject;
import com.nori.tc.ui.core.livemap.LiveMapObjectKind;
import com.nori.tc.ui.core.port.db.LiveMapObjectStorePort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * tc_live_map_object(JSONB shape_json) JDBC 어댑터입니다(DeployJdbcStorePort 트랙 2 답습).
 */
@Repository
public class LiveMapObjectJdbcStorePort implements LiveMapObjectStorePort {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final LiveMapJdbcSupport support;

    public LiveMapObjectJdbcStorePort(
            final NamedParameterJdbcTemplate jdbcTemplate,
            final ObjectMapper objectMapper
    ) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate is null");
        this.support = new LiveMapJdbcSupport(objectMapper);
    }

    @Override
    @Transactional
    public long save(final String objectName, final LiveMapObjectKind objectKind, final JsonNode shapeJson) {
        return jdbcTemplate.queryForObject("""
                        INSERT INTO tc_live_map_object (object_name, object_kind, shape_json, is_active)
                        VALUES (:objectName, :objectKind, CAST(:shapeJson AS JSONB), TRUE)
                        RETURNING object_key
                        """,
                new MapSqlParameterSource()
                        .addValue("objectName", objectName)
                        .addValue("objectKind", objectKind.name())
                        .addValue("shapeJson", support.toJson(shapeJson)),
                Long.class
        );
    }

    @Override
    @Transactional
    public boolean update(
            final long objectKey,
            final String objectName,
            final LiveMapObjectKind objectKind,
            final JsonNode shapeJson
    ) {
        final int updated = jdbcTemplate.update("""
                        UPDATE tc_live_map_object
                           SET object_name = :objectName,
                               object_kind = :objectKind,
                               shape_json = CAST(:shapeJson AS JSONB),
                               updated_at = CURRENT_TIMESTAMP
                         WHERE object_key = :objectKey
                        """,
                new MapSqlParameterSource()
                        .addValue("objectKey", objectKey)
                        .addValue("objectName", objectName)
                        .addValue("objectKind", objectKind.name())
                        .addValue("shapeJson", support.toJson(shapeJson))
        );
        return updated > 0;
    }

    @Override
    @Transactional
    public boolean delete(final long objectKey) {
        final int deleted = jdbcTemplate.update(
                "DELETE FROM tc_live_map_object WHERE object_key = :objectKey",
                new MapSqlParameterSource("objectKey", objectKey)
        );
        return deleted > 0;
    }

    @Override
    public List<LiveMapObject> findAllActive() {
        return jdbcTemplate.query("""
                        SELECT object_key, object_name, object_kind, shape_json, is_active
                          FROM tc_live_map_object
                         WHERE is_active = TRUE
                         ORDER BY object_name ASC
                        """,
                new MapSqlParameterSource(),
                this::mapObject
        );
    }

    @Override
    public Optional<LiveMapObject> findByKey(final long objectKey) {
        final List<LiveMapObject> rows = jdbcTemplate.query("""
                        SELECT object_key, object_name, object_kind, shape_json, is_active
                          FROM tc_live_map_object
                         WHERE object_key = :objectKey
                        """,
                new MapSqlParameterSource("objectKey", objectKey),
                this::mapObject
        );
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    @Override
    public List<LiveMapObject> findByKeyIn(final Collection<Long> objectKeys) {
        if (objectKeys == null || objectKeys.isEmpty()) {
            return List.of();
        }
        final List<Long> distinctKeys = objectKeys.stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (distinctKeys.isEmpty()) {
            return List.of();
        }
        return jdbcTemplate.query("""
                        SELECT object_key, object_name, object_kind, shape_json, is_active
                          FROM tc_live_map_object
                         WHERE object_key IN (:objectKeys)
                         ORDER BY object_key ASC
                        """,
                new MapSqlParameterSource("objectKeys", distinctKeys),
                this::mapObject
        );
    }

    private LiveMapObject mapObject(final ResultSet resultSet, final int rowNum) throws SQLException {
        return new LiveMapObject(
                resultSet.getLong("object_key"),
                resultSet.getString("object_name"),
                LiveMapObjectKind.valueOf(resultSet.getString("object_kind")),
                support.parseJson(resultSet.getString("shape_json")),
                resultSet.getBoolean("is_active")
        );
    }
}

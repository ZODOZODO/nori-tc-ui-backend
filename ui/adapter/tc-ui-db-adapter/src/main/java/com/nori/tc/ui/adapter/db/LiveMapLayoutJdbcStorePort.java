package com.nori.tc.ui.adapter.db;

import com.nori.tc.ui.core.livemap.LiveMapLayoutView;
import com.nori.tc.ui.core.livemap.LiveMapPlacementView;
import com.nori.tc.ui.core.livemap.UpsertLiveMapPlacement;
import com.nori.tc.ui.core.port.db.LiveMapLayoutStorePort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * tc_live_map_layout(JSONB canvas_json) + tc_live_map_placement(JSONB transform_json) JDBC 어댑터입니다.
 *
 * <p>레이아웃은 라인당 1행(line_key UNIQUE)이라 upsert(ON CONFLICT)이고, 배치는 PUT 시
 * delete-then-insert 로 일괄 replace 합니다(단일 트랜잭션). DeployJdbcStorePort 트랙 2 답습.</p>
 */
@Repository
public class LiveMapLayoutJdbcStorePort implements LiveMapLayoutStorePort {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final LiveMapJdbcSupport support;

    public LiveMapLayoutJdbcStorePort(
            final NamedParameterJdbcTemplate jdbcTemplate,
            final ObjectMapper objectMapper
    ) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate is null");
        this.support = new LiveMapJdbcSupport(objectMapper);
    }

    @Override
    public Optional<LiveMapLayoutView> findByLineKey(final long lineKey) {
        final List<LayoutRow> layoutRows = jdbcTemplate.query("""
                        SELECT layout_key, line_key, canvas_json
                          FROM tc_live_map_layout
                         WHERE line_key = :lineKey
                        """,
                new MapSqlParameterSource("lineKey", lineKey),
                (resultSet, rowNum) -> new LayoutRow(
                        resultSet.getLong("layout_key"),
                        resultSet.getLong("line_key"),
                        support.parseJson(resultSet.getString("canvas_json"))
                )
        );
        if (layoutRows.isEmpty()) {
            return Optional.empty();
        }

        final LayoutRow layout = layoutRows.getFirst();
        final List<LiveMapPlacementView> placements = jdbcTemplate.query("""
                        SELECT placement_key, object_key, eqp_key, transform_json
                          FROM tc_live_map_placement
                         WHERE layout_key = :layoutKey
                         ORDER BY placement_key ASC
                        """,
                new MapSqlParameterSource("layoutKey", layout.layoutKey()),
                (resultSet, rowNum) -> new LiveMapPlacementView(
                        resultSet.getLong("placement_key"),
                        resultSet.getLong("object_key"),
                        nullableLong(resultSet, "eqp_key"),
                        support.parseJson(resultSet.getString("transform_json"))
                )
        );

        return Optional.of(new LiveMapLayoutView(
                layout.layoutKey(),
                layout.lineKey(),
                layout.canvasJson(),
                placements,
                List.of()
        ));
    }

    @Override
    @Transactional
    public long saveLayout(
            final long lineKey,
            final JsonNode canvasJson,
            final List<UpsertLiveMapPlacement> placements
    ) {
        final long layoutKey = jdbcTemplate.queryForObject("""
                        INSERT INTO tc_live_map_layout (line_key, canvas_json)
                        VALUES (:lineKey, CAST(:canvasJson AS JSONB))
                        ON CONFLICT (line_key) DO UPDATE
                           SET canvas_json = CAST(:canvasJson AS JSONB),
                               updated_at = CURRENT_TIMESTAMP
                        RETURNING layout_key
                        """,
                new MapSqlParameterSource()
                        .addValue("lineKey", lineKey)
                        .addValue("canvasJson", support.toJson(canvasJson)),
                Long.class
        );

        jdbcTemplate.update(
                "DELETE FROM tc_live_map_placement WHERE layout_key = :layoutKey",
                new MapSqlParameterSource("layoutKey", layoutKey)
        );

        final List<UpsertLiveMapPlacement> safePlacements = placements == null ? List.of() : placements;
        for (final UpsertLiveMapPlacement placement : safePlacements) {
            jdbcTemplate.update("""
                            INSERT INTO tc_live_map_placement (layout_key, object_key, eqp_key, transform_json)
                            VALUES (:layoutKey, :objectKey, :eqpKey, CAST(:transformJson AS JSONB))
                            """,
                    new MapSqlParameterSource()
                            .addValue("layoutKey", layoutKey)
                            .addValue("objectKey", placement.objectKey())
                            .addValue("eqpKey", placement.eqpKey())
                            .addValue("transformJson", support.toJson(placement.transformJson()))
            );
        }
        return layoutKey;
    }

    @Override
    public Optional<Long> findEqpLineKey(final long eqpKey) {
        final List<Long> rows = jdbcTemplate.query(
                "SELECT line_key FROM tc_eqp WHERE eqp_key = :eqpKey",
                new MapSqlParameterSource("eqpKey", eqpKey),
                (resultSet, rowNum) -> resultSet.getLong("line_key")
        );
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    private Long nullableLong(final ResultSet resultSet, final String columnLabel) throws SQLException {
        final long value = resultSet.getLong(columnLabel);
        return resultSet.wasNull() ? null : value;
    }

    private record LayoutRow(long layoutKey, long lineKey, JsonNode canvasJson) {
    }
}

package com.nori.tc.ui.adapter.db;

import com.nori.tc.ui.core.deploy.DeployScenarioCatalogMeta;
import com.nori.tc.ui.core.port.db.DeployScenarioCatalogMetaPort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;

/**
 * tc_deploy_scenario_catalog_meta JDBC 어댑터입니다.
 */
@Repository
public class DeployScenarioCatalogMetaJdbcPort implements DeployScenarioCatalogMetaPort {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public DeployScenarioCatalogMetaJdbcPort(final NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate is null");
    }

    @Override
    public Optional<DeployScenarioCatalogMeta> findLatest() {
        return jdbcTemplate.query("""
                        SELECT catalog_version,
                               catalog_checksum,
                               scenario_count,
                               loaded_at,
                               loaded_by,
                               reload_reason
                          FROM tc_deploy_scenario_catalog_meta
                      ORDER BY catalog_version DESC
                         LIMIT 1
                        """,
                resultSet -> resultSet.next()
                        ? Optional.of(mapMeta(resultSet))
                        : Optional.empty()
        );
    }

    @Override
    public DeployScenarioCatalogMeta append(
            final String checksum,
            final int scenarioCount,
            final String loadedBy,
            final String reloadReason
    ) {
        final MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("catalogChecksum", checksum)
                .addValue("scenarioCount", scenarioCount)
                .addValue("loadedBy", loadedBy)
                .addValue("reloadReason", reloadReason);

        return jdbcTemplate.queryForObject("""
                        INSERT INTO tc_deploy_scenario_catalog_meta (
                            catalog_checksum,
                            scenario_count,
                            loaded_by,
                            reload_reason
                        ) VALUES (
                            :catalogChecksum,
                            :scenarioCount,
                            :loadedBy,
                            :reloadReason
                        )
                        RETURNING catalog_version,
                                  catalog_checksum,
                                  scenario_count,
                                  loaded_at,
                                  loaded_by,
                                  reload_reason
                        """,
                parameters,
                (resultSet, rowNum) -> mapMeta(resultSet)
        );
    }

    private DeployScenarioCatalogMeta mapMeta(final ResultSet resultSet) throws SQLException {
        return new DeployScenarioCatalogMeta(
                resultSet.getInt("catalog_version"),
                resultSet.getString("catalog_checksum"),
                resultSet.getInt("scenario_count"),
                resultSet.getObject("loaded_at", OffsetDateTime.class),
                resultSet.getString("loaded_by"),
                resultSet.getString("reload_reason")
        );
    }
}

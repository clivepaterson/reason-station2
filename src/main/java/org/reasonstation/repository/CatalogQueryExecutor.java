package org.reasonstation.repository;

import org.reasonstation.logging.RawSqlQuery;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

@Repository
public class CatalogQueryExecutor {
    private final NamedParameterJdbcTemplate jdbc;

    public CatalogQueryExecutor(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @RawSqlQuery
    public <T> List<T> query(
            String sql, MapSqlParameterSource parameters, RowMapper<T> rowMapper) {
        return jdbc.query(sql, parameters, rowMapper);
    }

    @RawSqlQuery
    public <T> T queryForObject(String sql, Map<String, ?> parameters, Class<T> requiredType) {
        return jdbc.queryForObject(sql, parameters, requiredType);
    }
}

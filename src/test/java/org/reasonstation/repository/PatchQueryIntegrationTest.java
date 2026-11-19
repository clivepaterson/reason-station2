package org.reasonstation.repository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class PatchQueryIntegrationTest {
    private Connection connection;
    private NamedParameterJdbcTemplate jdbc;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection(System.getenv("TEST_DATABASE_URL"));
        jdbc = new NamedParameterJdbcTemplate(new SingleConnectionDataSource(connection, true));
        jdbc.getJdbcTemplate().execute("""
                CREATE TEMP TABLE patches (
                    patchid BIGINT PRIMARY KEY, artist_alias TEXT, date_uploaded TIMESTAMPTZ
                );
                CREATE TEMP TABLE patch_ratings (patchid BIGINT, rating INTEGER);
                INSERT INTO patches VALUES
                    (1, 'Artist', '2026-01-01'),
                    (2, 'Artist', '2026-01-01'),
                    (3, 'Other', '2026-01-02');
                INSERT INTO patch_ratings VALUES (1, 4), (1, 8);
                """);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connection != null) {
            connection.close();
        }
    }

    @Test
    void artistPagesHaveStableOrderingRatingsAndMatchingCounts() throws Exception {
        String pageSql = sql("findWithRatingsByArtistAlias", String.class, int.class, long.class);
        var first = jdbc.queryForList(pageSql, Map.of("artistAlias", "Artist", "limit", 1, "offset", 0));
        var second = jdbc.queryForList(pageSql, Map.of("artistAlias", "Artist", "limit", 1, "offset", 1));

        assertEquals(1, first.size());
        assertEquals(2L, first.getFirst().get("patchid"));
        assertNull(first.getFirst().get("average_patch_rating"));
        assertEquals(1, second.size());
        assertEquals(1L, second.getFirst().get("patchid"));
        assertEquals(6.0, ((Number) second.getFirst().get("average_patch_rating")).doubleValue());
        assertEquals(2L, jdbc.queryForObject(sql("countByArtistAlias", String.class),
                Map.of("artistAlias", "Artist"), Long.class));
        assertEquals(0, jdbc.queryForList(pageSql,
                Map.of("artistAlias", "Artist", "limit", 1, "offset", 2)).size());
        assertEquals(0L, jdbc.queryForObject(sql("countByArtistAlias", String.class),
                Map.of("artistAlias", "Missing"), Long.class));
    }

    private String sql(String method, Class<?>... parameters) throws Exception {
        return PatchRepository.class.getMethod(method, parameters).getAnnotation(Query.class).value();
    }
}

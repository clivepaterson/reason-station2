package org.reasonstation.repository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class TrackQueryIntegrationTest {
    private Connection connection;
    private NamedParameterJdbcTemplate jdbc;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection(System.getenv("TEST_DATABASE_URL"));
        jdbc = new NamedParameterJdbcTemplate(new SingleConnectionDataSource(connection, true));
        jdbc.getJdbcTemplate().execute("""
                CREATE TEMP TABLE tracks (
                    trackid BIGINT PRIMARY KEY, artist_alias TEXT, track_name TEXT,
                    date_uploaded TIMESTAMPTZ
                );
                CREATE TEMP TABLE track_downloads (trackid BIGINT, ip_address INET);
                CREATE TEMP TABLE track_ratings (trackid BIGINT, rating INTEGER);
                INSERT INTO tracks VALUES
                    (1, 'Artist', 'Song A', '2026-01-01'),
                    (2, 'Artist', 'Song B', '2026-01-01'),
                    (3, 'Other', 'Song C', '2026-01-02');
                INSERT INTO track_downloads VALUES
                    (1, '127.0.0.1'), (1, '127.0.0.2'), (1, '127.0.0.3');
                INSERT INTO track_ratings VALUES (1, 4), (1, 8), (2, 10);
                """);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connection != null) {
            connection.close();
        }
    }

    @Test
    void randomTracksCountDownloadsIndependentlyOfRatings() throws Exception {
        var rows = jdbc.queryForList(sql("findRandomTracks", int.class), Map.of("limit", 10));

        assertEquals(3, rows.size());
        var rated = rows.stream().filter(row -> row.get("trackid").equals(1L)).findFirst().orElseThrow();
        assertEquals(3L, rated.get("download_count"));
        assertEquals(6.0, ((Number) rated.get("average_track_rating")).doubleValue());
        var empty = rows.stream().filter(row -> row.get("trackid").equals(3L)).findFirst().orElseThrow();
        assertEquals(0L, empty.get("download_count"));
        assertNull(empty.get("average_track_rating"));
        assertEquals(1, jdbc.queryForList(sql("findRandomTracks", int.class), Map.of("limit", 1)).size());
    }

    @Test
    void artistPagesHaveStableOrderingAndIndependentCounts() throws Exception {
        String sql = sql("findByArtistAlias", String.class, int.class, long.class);
        var first = jdbc.queryForList(sql, Map.of("artistAlias", "Artist", "limit", 1, "offset", 0));
        var second = jdbc.queryForList(sql, Map.of("artistAlias", "Artist", "limit", 1, "offset", 1));

        assertEquals(2L, first.getFirst().get("trackid"));
        assertEquals(0L, first.getFirst().get("download_count"));
        assertEquals(1L, second.getFirst().get("trackid"));
        assertEquals(3L, second.getFirst().get("download_count"));
        assertEquals(2L, jdbc.queryForObject(sql("countByArtistAlias", String.class),
                Map.of("artistAlias", "Artist"), Long.class));
    }

    @Test
    void searchPagesDoNotInventOrMultiplyDownloads() throws Exception {
        String sql = sql("searchByArtistAliasOrTrackName", String.class, int.class, long.class);
        var rows = jdbc.queryForList(sql, Map.of("searchTerm", "Song", "limit", 2, "offset", 0));

        assertEquals(2, rows.size());
        assertEquals(1L, rows.getFirst().get("trackid"));
        assertEquals(3L, rows.getFirst().get("download_count"));
        assertEquals(0L, rows.get(1).get("download_count"));
        var next = jdbc.queryForList(sql, Map.of("searchTerm", "Song", "limit", 2, "offset", 2));
        assertEquals(3L, next.getFirst().get("trackid"));
        assertNull(next.getFirst().get("average_track_rating"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void archiveFiltersMatchNullableColumnsWithoutChangingResults(boolean songs) {
        String table = songs ? "archive_songs" : "archive_refills";
        jdbc.getJdbcTemplate().execute("""
                CREATE TEMP TABLE %s (
                    archive_key TEXT, name TEXT, creator TEXT, filename TEXT,
                    date_created DATE, extension TEXT, filesize BIGINT, s3_uri TEXT,
                    style TEXT, avr NUMERIC
                );
                INSERT INTO %s (archive_key, name, creator, filename) VALUES
                    ('1', 'Classic', NULL, NULL),
                    ('2', NULL, 'Classic artist', NULL),
                    ('3', NULL, NULL, 'classic.rns'),
                    ('4', NULL, NULL, NULL);
                """.formatted(table, table));
        var catalog = new CatalogRepository(new CatalogQueryExecutor(jdbc));
        for (String filter : new String[]{"", "CLASSIC", "missing"}) {
            var result = songs
                    ? catalog.findArchivedSongs(1, 25, "dateCreated", CatalogRepository.SortDirection.DESC, filter)
                    : catalog.findArchivedRefills(1, 25, "dateCreated", CatalogRepository.SortDirection.DESC, filter);
            int expected = filter.isEmpty() ? 4 : filter.equals("CLASSIC") ? 3 : 0;
            assertEquals(expected, result.totalElements());
            assertEquals(expected, result.items().size());
        }
    }

    private String sql(String method, Class<?>... parameters) throws Exception {
        return TrackRepository.class.getMethod(method, parameters).getAnnotation(Query.class).value();
    }
}

package org.reasonstation.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Repository
public class CatalogRepository {
    private static final Map<String, String> ARTIST_SORT_COLUMNS = Map.of(
            "artistAlias", "artist_alias",
            "dateCreated", "date_created");
    private static final Map<String, String> TRACK_SORT_COLUMNS = Map.of(
            "artistAlias", "t.artist_alias",
            "trackName", "t.track_name",
            "musicStyle", "t.music_style",
            "filename", "t.filename",
            "dateUploaded", "t.date_uploaded");
    private static final Map<String, String> PATCH_SORT_COLUMNS = Map.of(
            "artistAlias", "artist_alias",
            "patchName", "patch_name",
            "filename", "filename",
            "ext", "ext",
            "dateUploaded", "date_uploaded");

    private final CatalogQueryExecutor queries;

    public CatalogRepository(CatalogQueryExecutor queries) {
        this.queries = queries;
    }

    public PagedResult<ArtistListItem> findArtists(
            int page, int pageSize, String sort, SortDirection direction, String filter) {
        String orderBy = sortColumn(ARTIST_SORT_COLUMNS, sort);
        var parameters = paginationParameters(page, pageSize)
                .addValue("filter", filter);
        String filterClause = """
                WHERE :filter = ''
                   OR t.artist_alias ILIKE '%' || :filter || '%'
                """;
        long totalElements = queryCount("""
                SELECT COUNT(*)
                FROM (
                    SELECT t.userid, t.artist_alias
                    FROM tracks t
                    %s
                    GROUP BY t.userid, t.artist_alias
                ) artists
                """.formatted(filterClause), Map.of("filter", filter));
        var items = queries.query("""
                SELECT t.artist_alias,
                       u.date_created,
                       COUNT(*) AS track_count,
                       COALESCE(MAX(patch_counts.patch_count), 0) AS patch_count
                FROM tracks t
                JOIN "user" u ON u.userid = t.userid
                LEFT JOIN (
                    SELECT userid,
                           artist_alias,
                           COUNT(*) AS patch_count
                    FROM patches
                    GROUP BY userid, artist_alias
                ) patch_counts
                  ON patch_counts.userid = t.userid
                 AND patch_counts.artist_alias = t.artist_alias
                %s
                GROUP BY t.userid, t.artist_alias, u.date_created
                ORDER BY %s %s, t.artist_alias ASC, t.userid ASC
                LIMIT :limit
                OFFSET :offset
                """.formatted(filterClause, orderBy, direction.sql()), parameters,
                (resultSet, rowNumber) -> new ArtistListItem(
                        resultSet.getString("artist_alias"),
                        toInstant(resultSet.getTimestamp("date_created")),
                        resultSet.getLong("track_count"),
                        resultSet.getLong("patch_count")));
        return page(items, page, pageSize, totalElements);
    }

    public PagedResult<TrackListItem> findTracks(
            int page, int pageSize, String sort, SortDirection direction, String filter) {
        String orderBy = sortColumn(TRACK_SORT_COLUMNS, sort);
        var parameters = paginationParameters(page, pageSize)
                .addValue("filter", filter);
        String filterClause = """
                WHERE :filter = ''
                   OR (t.artist_alias || ' ' || t.track_name || ' ' || t.filename)
                      ILIKE '%' || :filter || '%'
                """;
        long totalElements = queryCount(
                """
                SELECT COUNT(*)
                FROM tracks t
                """ + filterClause,
                Map.of("filter", filter));
        var items = queries.query("""
                SELECT t.trackid,
                       t.artist_alias,
                       t.track_name,
                       t.music_style,
                       t.filename,
                       t.filesize,
                       t.date_uploaded,
                       AVG(r.rating) AS average_rating
                FROM tracks t
                LEFT JOIN track_ratings r ON r.trackid = t.trackid
                %s
                GROUP BY t.trackid
                ORDER BY %s %s, t.trackid ASC
                LIMIT :limit
                OFFSET :offset
                """.formatted(filterClause, orderBy, direction.sql()), parameters,
                (resultSet, rowNumber) -> new TrackListItem(
                        resultSet.getLong("trackid"),
                        resultSet.getString("artist_alias"),
                        resultSet.getString("track_name"),
                        resultSet.getString("music_style"),
                        resultSet.getString("filename"),
                        resultSet.getObject("filesize", Long.class),
                        toInstant(resultSet.getTimestamp("date_uploaded")),
                        toDouble(resultSet.getBigDecimal("average_rating"))));
        return page(items, page, pageSize, totalElements);
    }

    public PagedResult<PatchListItem> findPatches(
            int page, int pageSize, String sort, SortDirection direction, String filter) {
        String orderBy = sortColumn(PATCH_SORT_COLUMNS, sort);
        var parameters = paginationParameters(page, pageSize)
                .addValue("filter", filter);
        String filterClause = """
                WHERE :filter = ''
                   OR (patch_name || ' ' || COALESCE(description, '') || ' ' || filename)
                      ILIKE '%' || :filter || '%'
                """;
        long totalElements = queryCount(
                "SELECT COUNT(*) FROM patches " + filterClause,
                Map.of("filter", filter));
        var items = queries.query("""
                SELECT patchid,
                       artist_alias,
                       patch_name,
                       description,
                       filename,
                       ext,
                       date_uploaded
                FROM patches
                %s
                ORDER BY %s %s, patchid ASC
                LIMIT :limit
                OFFSET :offset
                """.formatted(filterClause, orderBy, direction.sql()), parameters,
                (resultSet, rowNumber) -> new PatchListItem(
                        resultSet.getLong("patchid"),
                        resultSet.getString("artist_alias"),
                        resultSet.getString("patch_name"),
                        resultSet.getString("description"),
                        resultSet.getString("filename"),
                        resultSet.getString("ext"),
                        toInstant(resultSet.getTimestamp("date_uploaded"))));
        return page(items, page, pageSize, totalElements);
    }

    private MapSqlParameterSource paginationParameters(int page, int pageSize) {
        return new MapSqlParameterSource()
                .addValue("limit", pageSize)
                .addValue("offset", (long) (page - 1) * pageSize);
    }

    private long queryCount(String sql) {
        return queryCount(sql, Map.of());
    }

    private long queryCount(String sql, Map<String, ?> parameters) {
        Long count = queries.queryForObject(sql, parameters, Long.class);
        return count == null ? 0 : count;
    }

    private String sortColumn(Map<String, String> columns, String sort) {
        String column = columns.get(sort);
        if (column == null) {
            throw new IllegalArgumentException("Unsupported sort column: " + sort);
        }
        return column;
    }

    private <T> PagedResult<T> page(List<T> items, int page, int pageSize, long totalElements) {
        int totalPages = (int) ((totalElements + pageSize - 1) / pageSize);
        return new PagedResult<>(items, page, pageSize, totalElements, totalPages);
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static Double toDouble(BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }

    public enum SortDirection {
        ASC,
        DESC;

        public String sql() {
            return name();
        }
    }

    public record ArtistListItem(String artistAlias, Instant dateCreated,
                                 long trackCount, long patchCount) {
    }

    public record TrackListItem(Long trackid, String artistAlias, String trackName, String musicStyle,
                                String filename, Long filesize, Instant dateUploaded,
                                Double averageRating) {
    }

    public record PatchListItem(Long patchid, String artistAlias, String patchName, String description,
                                String filename, String ext, Instant dateUploaded) {
    }

    public record PagedResult<T>(List<T> items, int page, int pageSize,
                                 long totalElements, int totalPages) {
    }
}

package org.reasonstation.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;

@Repository
public class SitemapRepository {
    private final JdbcTemplate jdbc;

    public SitemapRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<SitemapEntry> findTracks() {
        return jdbc.query("SELECT trackid, date_uploaded FROM tracks ORDER BY trackid",
                (row, rowNumber) -> {
                    Timestamp uploaded = row.getTimestamp("date_uploaded");
                    return new SitemapEntry(row.getLong("trackid"),
                            uploaded == null ? null : uploaded.toInstant());
                });
    }

    public List<SitemapEntry> findPatches() {
        return jdbc.query("SELECT patchid, date_uploaded FROM patches ORDER BY patchid",
                (row, rowNumber) -> {
                    Timestamp uploaded = row.getTimestamp("date_uploaded");
                    return new SitemapEntry(row.getLong("patchid"),
                            uploaded == null ? null : uploaded.toInstant());
                });
    }
}

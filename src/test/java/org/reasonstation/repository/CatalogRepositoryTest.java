package org.reasonstation.repository;

import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

import java.sql.ResultSet;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class CatalogRepositoryTest {
    @Mock
    private CatalogQueryExecutor queries;

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void listsArchivePagesWithStableOrderingAndOptionalMetadata(boolean songs) throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getString("archive_key")).thenReturn("file:classic.rns");
        when(row.getString("name")).thenReturn(null);
        when(row.getString("creator")).thenReturn(null);
        when(row.getString("style")).thenReturn(null);
        when(row.getString("filename")).thenReturn("classic.rns");
        when(row.getString("extension")).thenReturn("rns");
        when(row.getObject("filesize", Long.class)).thenReturn(1024L);
        when(row.getObject("date_created", LocalDate.class))
                .thenReturn(LocalDate.of(2007, 12, 1));
        when(row.getBigDecimal("avr")).thenReturn(songs ? new BigDecimal("9.5") : null);
        when(row.getBoolean("download_available")).thenReturn(songs);
        String table = songs ? "archive_songs" : "archive_refills";
        when(queries.queryForObject(contains("FROM " + table), eq(Map.of("filter", "classic")),
                eq(Long.class))).thenReturn(51L);
        when(queries.<CatalogRepository.ArchiveListItem>query(
                contains("FROM " + table), any(MapSqlParameterSource.class), any()))
                .thenAnswer(invocation -> {
                    RowMapper<CatalogRepository.ArchiveListItem> mapper = invocation.getArgument(2);
                    return List.of(mapper.mapRow(row, 0));
                });

        var repository = new CatalogRepository(queries);
        var result = songs
                ? repository.findArchivedSongs(2, 25, "dateCreated", CatalogRepository.SortDirection.DESC, "classic")
                : repository.findArchivedRefills(2, 25, "dateCreated", CatalogRepository.SortDirection.DESC, "classic");

        assertEquals(51, result.totalElements());
        assertEquals(3, result.totalPages());
        assertEquals(2, result.page());
        var item = result.items().getFirst();
        assertNull(item.name());
        assertNull(item.creator());
        assertEquals("classic.rns", item.filename());
        assertEquals(1024L, item.filesize());
        assertEquals(LocalDate.of(2007, 12, 1), item.dateCreated());
        assertEquals(songs ? 9.5 : null, item.averageRating());
        assertEquals(songs, item.downloadAvailable());

        var sql = ArgumentCaptor.forClass(String.class);
        var parameters = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(queries).query(sql.capture(), parameters.capture(), any());
        assertTrue(sql.getValue().contains("ORDER BY date_created DESC NULLS LAST, archive_key ASC"));
        assertTrue(sql.getValue().contains("COALESCE(creator, '') ILIKE"));
        assertEquals(25, parameters.getValue().getValue("limit"));
        assertEquals(25L, parameters.getValue().getValue("offset"));
        assertEquals("classic", parameters.getValue().getValue("filter"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void looksUpArchiveDownloadsUsingBoundKeys(boolean songs) throws Exception {
        String key = "file:downloads/artist's song.rps";
        String table = songs ? "archive_songs" : "archive_refills";
        ResultSet row = mock(ResultSet.class);
        when(row.getString("s3_uri")).thenReturn("s3://reasonarchive/songs/artist's song.rps");
        when(row.getString("filename")).thenReturn("artist's song.rps");
        when(queries.<CatalogRepository.ArchiveDownload>query(
                eq("SELECT s3_uri, filename FROM " + table + " WHERE archive_key = :archiveKey"),
                any(MapSqlParameterSource.class), any()))
                .thenAnswer(invocation -> {
                    RowMapper<CatalogRepository.ArchiveDownload> mapper = invocation.getArgument(2);
                    return List.of(mapper.mapRow(row, 0));
                });
        var repository = new CatalogRepository(queries);

        var result = songs ? repository.findArchivedSongDownload(key) : repository.findArchivedRefillDownload(key);

        assertEquals("artist's song.rps", result.orElseThrow().filename());
        assertEquals("s3://reasonarchive/songs/artist's song.rps", result.orElseThrow().objectUri());
        var parameters = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(queries).query(any(String.class), parameters.capture(), any());
        assertEquals(key, parameters.getValue().getValue("archiveKey"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void paginatesOneHundredArchiveItems(boolean songs) {
        String table = songs ? "archive_songs" : "archive_refills";
        when(queries.queryForObject(contains("FROM " + table), eq(Map.of("filter", "")),
                eq(Long.class))).thenReturn(201L);
        when(queries.<CatalogRepository.ArchiveListItem>query(
                contains("FROM " + table), any(MapSqlParameterSource.class), any()))
                .thenReturn(List.of());
        var repository = new CatalogRepository(queries);

        var result = songs
                ? repository.findArchivedSongs(3, 100, "name", CatalogRepository.SortDirection.ASC, "")
                : repository.findArchivedRefills(3, 100, "name", CatalogRepository.SortDirection.ASC, "");

        assertEquals(100, result.pageSize());
        assertEquals(3, result.totalPages());
        var parameters = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(queries).query(any(String.class), parameters.capture(), any());
        assertEquals(100, parameters.getValue().getValue("limit"));
        assertEquals(200L, parameters.getValue().getValue("offset"));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = "13.2.1")
    void listsTracksWithOptionalProgramVersion(String programVersion) throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getLong("trackid")).thenReturn(42L);
        when(row.getString("artist_alias")).thenReturn("Artist");
        when(row.getString("track_name")).thenReturn("Track");
        when(row.getString("music_style")).thenReturn("Rock");
        when(row.getString("filename")).thenReturn("song.reason");
        when(row.getString("program_version")).thenReturn(programVersion);
        when(queries.queryForObject(
                any(String.class), eq(Map.of("filter", "")), eq(Long.class)))
                .thenReturn(1L);
        when(queries.<CatalogRepository.TrackListItem>query(
                contains("t.program_version"), any(MapSqlParameterSource.class), any()))
                .thenAnswer(invocation -> {
                    RowMapper<CatalogRepository.TrackListItem> mapper = invocation.getArgument(2);
                    return List.of(mapper.mapRow(row, 0));
                });

        var result = new CatalogRepository(queries).findTracks(
                1, 25, "dateUploaded", CatalogRepository.SortDirection.DESC, "");

        assertEquals(1, result.items().size());
        assertEquals(42L, result.items().getFirst().trackid());
        assertEquals("song.reason", result.items().getFirst().filename());
        assertEquals(programVersion, result.items().getFirst().programVersion());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = "s3://tracks/patch-images/bass")
    void listsPatchesWithOptionalImageUri(String imageUri) throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getLong("patchid")).thenReturn(15L);
        when(row.getString("artist_alias")).thenReturn("Artist");
        when(row.getString("patch_name")).thenReturn("Bass");
        when(row.getString("description")).thenReturn("Warm bass");
        when(row.getString("filename")).thenReturn("bass.thor");
        when(row.getString("ext")).thenReturn(".thor");
        when(row.getString("patch_img_uri")).thenReturn(imageUri);
        when(queries.queryForObject(
                any(String.class), eq(Map.of("filter", "")), eq(Long.class)))
                .thenReturn(1L);
        when(queries.<CatalogRepository.PatchListItem>query(
                contains("patch_img_uri"), any(MapSqlParameterSource.class), any()))
                .thenAnswer(invocation -> {
                    RowMapper<CatalogRepository.PatchListItem> mapper = invocation.getArgument(2);
                    return List.of(mapper.mapRow(row, 0));
                });

        var result = new CatalogRepository(queries).findPatches(
                1, 25, "dateUploaded", CatalogRepository.SortDirection.DESC, "");

        assertEquals(1, result.items().size());
        assertEquals(15L, result.items().getFirst().patchid());
        assertEquals("Bass", result.items().getFirst().patchName());
        assertEquals(imageUri, result.items().getFirst().patchImgUri());
    }
}

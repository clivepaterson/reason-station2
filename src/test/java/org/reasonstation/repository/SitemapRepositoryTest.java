package org.reasonstation.repository;

import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SitemapRepositoryTest {
    @Mock
    private JdbcTemplate jdbc;

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void selectsOnlyIdsAndUploadDatesWithStableOrdering(boolean tracks) throws Exception {
        String idColumn = tracks ? "trackid" : "patchid";
        String table = tracks ? "tracks" : "patches";
        String sql = "SELECT " + idColumn + ", date_uploaded FROM " + table + " ORDER BY " + idColumn;
        Instant uploaded = Instant.parse("2026-11-03T10:15:30Z");
        ResultSet dated = mock(ResultSet.class);
        ResultSet undated = mock(ResultSet.class);
        when(dated.getLong(idColumn)).thenReturn(42L);
        when(dated.getTimestamp("date_uploaded")).thenReturn(Timestamp.from(uploaded));
        when(undated.getLong(idColumn)).thenReturn(43L);
        when(jdbc.query(eq(sql), any(RowMapper.class))).thenAnswer(invocation -> {
            RowMapper<SitemapEntry> mapper = invocation.getArgument(1);
            return List.of(mapper.mapRow(dated, 0), mapper.mapRow(undated, 1));
        });
        var repository = new SitemapRepository(jdbc);

        var entries = tracks ? repository.findTracks() : repository.findPatches();

        assertEquals(new SitemapEntry(42L, uploaded), entries.getFirst());
        assertEquals(43L, entries.get(1).id());
        assertNull(entries.get(1).dateUploaded());
        verify(jdbc).query(eq(sql), any(RowMapper.class));
        verifyNoMoreInteractions(jdbc);
        verify(dated).getLong(idColumn);
        verify(dated).getTimestamp("date_uploaded");
        verifyNoMoreInteractions(dated);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void propagatesQueryFailures(boolean tracks) {
        var failure = new IllegalStateException("database unavailable");
        when(jdbc.query(any(String.class), any(RowMapper.class))).thenThrow(failure);
        var repository = new SitemapRepository(jdbc);

        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> {
                    if (tracks) {
                        repository.findTracks();
                    } else {
                        repository.findPatches();
                    }
                }));
    }
}

package org.reasonstation.repository;

import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

import java.sql.ResultSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CatalogRepositoryTest {
    @Mock
    private CatalogQueryExecutor queries;

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

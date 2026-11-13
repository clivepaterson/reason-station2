package org.reasonstation.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.reasonstation.repository.CatalogRepository;
import org.reasonstation.repository.CatalogRepository.SortDirection;
import org.reasonstation.service.S3StorageService;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

@ExtendWith(MockitoExtension.class)
class CatalogControllerTest {
    @Mock
    private CatalogRepository catalog;
    @Mock
    private S3StorageService storage;

    private CatalogController controller;

    @BeforeEach
    void setUp() {
        controller = new CatalogController(catalog, storage);
    }

    @Test
    void listsArtistsUsingDefaultDateSort() {
        var expected = new CatalogRepository.PagedResult<>(
                List.<CatalogRepository.ArtistListItem>of(), 1, 25, 0, 0);
        when(catalog.findArtists(1, 25, "dateCreated", SortDirection.DESC, ""))
                .thenReturn(expected);

        assertEquals(expected, controller.artists(1, "dateCreated", "desc", ""));
    }

    @Test
    void listsTracksUsingAnAllowedAscendingSort() {
        var expected = new CatalogRepository.PagedResult<>(
                List.<CatalogRepository.TrackListItem>of(), 2, 25, 0, 0);
        when(catalog.findTracks(2, 25, "trackName", SortDirection.ASC, ""))
                .thenReturn(expected);

        assertEquals(expected, controller.tracks(2, "trackName", "asc", ""));
        verify(catalog).findTracks(2, 25, "trackName", SortDirection.ASC, "");
    }

    @Test
    void rejectsFilesizeAsATrackSort() {
        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> controller.tracks(1, "filesize", "asc", ""));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
    }

    @Test
    void rejectsSubmittedCountsAsArtistSorts() {
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(
                ResponseStatusException.class,
                () -> controller.artists(1, "trackCount", "asc", "")).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(
                ResponseStatusException.class,
                () -> controller.artists(1, "patchCount", "asc", "")).getStatusCode());
    }

    @Test
    void rejectsAverageRatingAsATrackSort() {
        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> controller.tracks(1, "averageRating", "desc", ""));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
    }

    @Test
    void rejectsDescriptionAsAPatchSort() {
        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> controller.patches(1, "description", "asc", ""));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
    }

    @Test
    void filtersPatchesUsingAnAllowedFilter() {
        var expected = new CatalogRepository.PagedResult<>(
                List.<CatalogRepository.PatchListItem>of(), 1, 25, 0, 0);
        when(catalog.findPatches(1, 25, "dateUploaded", SortDirection.DESC, "Bass.01"))
                .thenReturn(expected);

        assertEquals(expected, controller.patches(
                1, "dateUploaded", "desc", "Bass.01"));
        verify(catalog).findPatches(
                1, 25, "dateUploaded", SortDirection.DESC, "Bass.01");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "s3://tracks/patch-images/bass"})
    void includesImageUrlsInPatchCatalogWithoutChangingPagination(String imageUri) throws Exception {
        boolean hasImage = imageUri != null && !imageUri.isBlank();
        String imageUrl = hasImage ? "https://storage.example/bass?signature=test" : null;
        if (hasImage) {
            when(storage.createImageUrl(imageUri)).thenReturn(java.net.URI.create(imageUrl));
        }
        var patch = new CatalogRepository.PatchListItem(4L, "Artist", "Bass", "Warm bass",
                "bass.thor", ".thor", java.time.Instant.now(), imageUri);
        when(catalog.findPatches(2, 25, "dateUploaded", SortDirection.DESC, ""))
                .thenReturn(new CatalogRepository.PagedResult<>(List.of(patch), 2, 25, 26, 2));

        standaloneSetup(controller).build()
                .perform(get("/api/catalog/patches").param("page", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].patchid").value(4))
                .andExpect(jsonPath("$.items[0].patchName").value("Bass"))
                .andExpect(jsonPath("$.items[0].description").value("Warm bass"))
                .andExpect(jsonPath("$.items[0].filename").value("bass.thor"))
                .andExpect(jsonPath("$.items[0].ext").value(".thor"))
                .andExpect(jsonPath("$.items[0].dateUploaded").exists())
                .andExpect(jsonPath("$.items[0].patchImgUri").value(imageUri))
                .andExpect(jsonPath("$.items[0].imageUrl").value(imageUrl))
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.pageSize").value(25))
                .andExpect(jsonPath("$.totalElements").value(26))
                .andExpect(jsonPath("$.totalPages").value(2));

        if (!hasImage) {
            verifyNoInteractions(storage);
        }
    }

    @Test
    void filtersTracksUsingAnAllowedFilter() {
        var expected = new CatalogRepository.PagedResult<>(
                List.<CatalogRepository.TrackListItem>of(), 1, 25, 0, 0);
        when(catalog.findTracks(1, 25, "dateUploaded", SortDirection.DESC, "Bass.01"))
                .thenReturn(expected);

        assertEquals(expected, controller.tracks(
                1, "dateUploaded", "desc", "Bass.01"));
        verify(catalog).findTracks(
                1, 25, "dateUploaded", SortDirection.DESC, "Bass.01");
    }

    @Test
    void rejectsTrackFilterWithUnsupportedCharacters() {
        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> controller.tracks(1, "dateUploaded", "desc", "Bass track"));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
    }

    @Test
    void filtersArtistsUsingAnAllowedFilter() {
        var expected = new CatalogRepository.PagedResult<>(
                List.<CatalogRepository.ArtistListItem>of(), 1, 25, 0, 0);
        when(catalog.findArtists(1, 25, "dateCreated", SortDirection.DESC, "Artist.01"))
                .thenReturn(expected);

        assertEquals(expected, controller.artists(
                1, "dateCreated", "desc", "  Artist.01  "));
        verify(catalog).findArtists(
                1, 25, "dateCreated", SortDirection.DESC, "Artist.01");
    }

    @Test
    void rejectsArtistFilterWithUnsupportedCharacters() {
        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> controller.artists(1, "dateCreated", "desc", "Artist alias"));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
    }

    @Test
    void rejectsPatchFilterWithUnsupportedCharacters() {
        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> controller.patches(1, "dateUploaded", "desc", "Bass patch"));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
    }

    @Test
    void listsArchivedSongsWithPaginationAndFiltering() {
        var expected = new CatalogRepository.PagedResult<>(
                List.<CatalogRepository.ArchiveListItem>of(), 2, 25, 30, 2);
        when(catalog.findArchivedSongs(2, 25, "dateCreated", SortDirection.DESC, "Bass"))
                .thenReturn(expected);

        assertEquals(expected, controller.archivedSongs(2, "dateCreated", "desc", " Bass ", 25));
        verify(catalog).findArchivedSongs(2, 25, "dateCreated", SortDirection.DESC, "Bass");
    }

    @Test
    void listsArchivedRefillsWithNameSort() {
        var expected = new CatalogRepository.PagedResult<>(
                List.<CatalogRepository.ArchiveListItem>of(), 1, 25, 0, 0);
        when(catalog.findArchivedRefills(1, 25, "name", SortDirection.ASC, ""))
                .thenReturn(expected);

        assertEquals(expected, controller.archivedRefills(1, "name", "asc", "", 25));
    }

    @ParameterizedTest
    @ValueSource(ints = {25, 100})
    void supportsArchivePageSizes(int pageSize) {
        var expected = new CatalogRepository.PagedResult<>(
                List.<CatalogRepository.ArchiveListItem>of(), 2, pageSize, 201, (201 + pageSize - 1) / pageSize);
        when(catalog.findArchivedSongs(2, pageSize, "name", SortDirection.ASC, "Bass"))
                .thenReturn(expected);
        when(catalog.findArchivedRefills(2, pageSize, "name", SortDirection.ASC, "Bass"))
                .thenReturn(expected);

        assertEquals(expected, controller.archivedSongs(2, "name", "asc", "Bass", pageSize));
        assertEquals(expected, controller.archivedRefills(2, "name", "asc", "Bass", pageSize));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 1, 50, 101, 1000})
    void rejectsUnsupportedArchivePageSizes(int pageSize) {
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> controller.archivedSongs(1, "name", "asc", "", pageSize)).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> controller.archivedRefills(1, "name", "asc", "", pageSize)).getStatusCode());
    }

    @Test
    void rejectsInvalidArchiveParameters() {
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> controller.archivedSongs(0, "dateCreated", "desc", "", 25)).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> controller.archivedSongs(1, "source_path", "desc", "", 25)).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> controller.archivedRefills(1, "name", "invalid", "", 25)).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> controller.archivedRefills(1, "name", "asc", "%", 25)).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> controller.archivedSongs(1, "name", "asc", "a".repeat(101), 25)).getStatusCode());
    }

    @Test
    void rejectsInvalidPageAndDirection() {
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(
                ResponseStatusException.class,
                () -> controller.artists(0, "dateCreated", "desc", "")).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(
                ResponseStatusException.class,
                () -> controller.artists(1, "dateCreated", "sideways", "")).getStatusCode());
    }
}

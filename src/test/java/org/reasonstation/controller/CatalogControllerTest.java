package org.reasonstation.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.reasonstation.repository.CatalogRepository;
import org.reasonstation.repository.CatalogRepository.SortDirection;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CatalogControllerTest {
    @Mock
    private CatalogRepository catalog;

    private CatalogController controller;

    @BeforeEach
    void setUp() {
        controller = new CatalogController(catalog);
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

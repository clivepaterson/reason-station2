package org.reasonstation.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.reasonstation.repository.CatalogRepository;
import org.reasonstation.repository.CatalogRepository.ArchiveDownload;
import org.reasonstation.service.S3StorageService;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ArchiveDownloadControllerTest {
    @Mock
    private CatalogRepository catalog;
    @Mock
    private S3StorageService storage;

    private ArchiveDownloadController controller;

    @BeforeEach
    void setUp() {
        controller = new ArchiveDownloadController(catalog, storage);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void redirectsToTheStoredArchiveFile(boolean songs) {
        String key = "file:2007/downloads/classic song.rps";
        var file = new ArchiveDownload("s3://reasonarchive/songs/classic song.rps", "classic song.rps");
        var downloadUrl = URI.create("https://storage.example/reasonarchive/file?signature=test");
        if (songs) {
            when(catalog.findArchivedSongDownload(key)).thenReturn(Optional.of(file));
        } else {
            when(catalog.findArchivedRefillDownload(key)).thenReturn(Optional.of(file));
        }
        when(storage.createArchiveDownloadUrl(file.objectUri(), file.filename())).thenReturn(downloadUrl);

        var response = songs ? controller.downloadSong(key) : controller.downloadRefill(key);

        assertEquals(HttpStatus.FOUND, response.getStatusCode());
        assertEquals(downloadUrl, response.getHeaders().getLocation());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void returnsNotFoundForUnknownEntries(boolean songs) {
        if (songs) {
            when(catalog.findArchivedSongDownload("missing")).thenReturn(Optional.empty());
        } else {
            when(catalog.findArchivedRefillDownload("missing")).thenReturn(Optional.empty());
        }

        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
                () -> {
                    if (songs) {
                        controller.downloadSong("missing");
                    } else {
                        controller.downloadRefill("missing");
                    }
                }).getStatusCode());
        verifyNoInteractions(storage);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " "})
    void returnsNotFoundWhenTheStoredFileIsUnavailable(String objectUri) {
        when(catalog.findArchivedRefillDownload("missing-file"))
                .thenReturn(Optional.of(new ArchiveDownload(objectUri, "patch.cmb")));

        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
                () -> controller.downloadRefill("missing-file")).getStatusCode());
        verifyNoInteractions(storage);
    }

    @Test
    void usesTheObjectFilenameWhenMetadataIsMissing() {
        when(catalog.findArchivedSongDownload("file:song.rns"))
                .thenReturn(Optional.of(new ArchiveDownload("s3://reasonarchive/songs/song.rns", null)));
        URI url = URI.create("https://storage.example/song");
        when(storage.createArchiveDownloadUrl("s3://reasonarchive/songs/song.rns", "song.rns"))
                .thenReturn(url);

        assertEquals(url, controller.downloadSong("file:song.rns").getHeaders().getLocation());
    }

    @Test
    void rejectsBlankKeysBeforeLookingUpOrSigningFiles() {
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> controller.downloadSong(" ")).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> controller.downloadRefill(null)).getStatusCode());
        verifyNoInteractions(catalog, storage);
    }
}

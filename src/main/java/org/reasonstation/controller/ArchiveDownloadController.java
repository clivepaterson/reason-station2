package org.reasonstation.controller;

import org.reasonstation.repository.CatalogRepository;
import org.reasonstation.repository.CatalogRepository.ArchiveDownload;
import org.reasonstation.service.S3StorageService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

@RestController
@RequestMapping("/api/catalog")
public class ArchiveDownloadController {
    private final CatalogRepository catalog;
    private final S3StorageService storage;

    public ArchiveDownloadController(CatalogRepository catalog, S3StorageService storage) {
        this.catalog = catalog;
        this.storage = storage;
    }

    @GetMapping("/archived-songs/download")
    public ResponseEntity<Void> downloadSong(@RequestParam("key") String key) {
        validateKey(key);
        return download(catalog.findArchivedSongDownload(key));
    }

    @GetMapping("/archived-refills/download")
    public ResponseEntity<Void> downloadRefill(@RequestParam("key") String key) {
        validateKey(key);
        return download(catalog.findArchivedRefillDownload(key));
    }

    private void validateKey(String key) {
        if (key == null || key.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Archive key is required.");
        }
    }

    private ResponseEntity<Void> download(Optional<ArchiveDownload> result) {
        ArchiveDownload file = result.orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Archive entry not found."));
        if (file.objectUri() == null || file.objectUri().isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Archive file is unavailable.");
        }
        String filename = file.filename() == null || file.filename().isBlank()
                ? file.objectUri().substring(file.objectUri().lastIndexOf('/') + 1)
                : file.filename();
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(storage.createArchiveDownloadUrl(file.objectUri(), filename))
                .build();
    }
}

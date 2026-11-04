package org.reasonstation.repository;

import java.time.Instant;

public record PatchWithRating(
        Long patchid,
        String artistAlias,
        String patchName,
        String description,
        String filename,
        String ext,
        Long filesize,
        String patchImgUri,
        Instant dateUploaded,
        Double averagePatchRating) {
}

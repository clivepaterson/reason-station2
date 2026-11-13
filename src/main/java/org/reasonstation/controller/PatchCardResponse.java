package org.reasonstation.controller;

import org.reasonstation.repository.PatchWithRating;

import java.net.URI;
import java.time.Instant;

record PatchCardResponse(
        Long patchid,
        String artistAlias,
        String patchName,
        String description,
        String filename,
        String ext,
        Long filesize,
        String patchImgUri,
        Instant dateUploaded,
        Double averagePatchRating,
        URI imageUrl) {
    static PatchCardResponse from(PatchWithRating patch, URI imageUrl) {
        return new PatchCardResponse(patch.patchid(), patch.artistAlias(), patch.patchName(),
                patch.description(), patch.filename(), patch.ext(), patch.filesize(), patch.patchImgUri(),
                patch.dateUploaded(), patch.averagePatchRating(), imageUrl);
    }
}

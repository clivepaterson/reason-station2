package org.reasonstation.repository;

public record HighestRatedPatch(
        Long patchid,
        String artistAlias,
        String patchName,
        Double averagePatchRating,
        Long ratingCount) {
}

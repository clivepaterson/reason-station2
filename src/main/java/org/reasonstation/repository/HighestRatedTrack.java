package org.reasonstation.repository;

public record HighestRatedTrack(
        Long trackid,
        String artistAlias,
        String trackName,
        String musicStyle,
        String filename,
        Double averageTrackRating,
        Long ratingCount) {
}

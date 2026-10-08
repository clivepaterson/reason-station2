package org.reasonstation.repository;

import java.time.Instant;

public record TrackWithRating(
        Long trackid,
        Long userid,
        String artistAlias,
        String trackName,
        String musicStyle,
        String filename,
        String trackUri,
        Instant dateUploaded,
        Double averageTrackRating) {
}

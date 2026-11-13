package org.reasonstation.repository;

import java.time.Instant;

public record TrackWithRating(
        Long trackid,
        Long userid,
        String artistAlias,
        String trackName,
        String musicStyle,
        String filename,
        Long filesize,
        String trackUri,
        Instant dateUploaded,
        Double averageTrackRating,
        long downloadCount,
        String audioLink,
        String programVersion,
        String imageUri,
        String audioUri,
        Long audioFilesize) {
}

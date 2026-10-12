package org.reasonstation.repository;

import java.time.Instant;

public record TrackSearchResult(
        Long trackid,
        String artistAlias,
        String trackName,
        String musicStyle,
        String filename,
        Instant dateUploaded,
        long downloadCount) {
}

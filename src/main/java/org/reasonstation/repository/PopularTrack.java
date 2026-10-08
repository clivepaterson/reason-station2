package org.reasonstation.repository;

public record PopularTrack(
        Long trackid,
        String artistAlias,
        String trackName,
        String musicStyle,
        String filename,
        long downloadCount) {
}

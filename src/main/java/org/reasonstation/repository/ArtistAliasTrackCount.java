package org.reasonstation.repository;

import java.time.Instant;

public record ArtistAliasTrackCount(String artistAlias, long trackCount, Instant dateCreated) {
}

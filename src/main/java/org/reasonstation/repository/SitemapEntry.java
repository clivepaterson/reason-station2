package org.reasonstation.repository;

import java.time.Instant;

public record SitemapEntry(long id, Instant dateUploaded) {
}

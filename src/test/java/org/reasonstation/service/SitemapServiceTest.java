package org.reasonstation.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.reasonstation.repository.SitemapEntry;
import org.reasonstation.repository.SitemapRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@Timeout(10)
class SitemapServiceTest {
    private static final String BASE_URL = "https://reasonstation.example";

    @Mock
    private SitemapRepository repository;

    @Test
    void preservesXmlPathsEscapingAndOptionalUtcDates() {
        when(repository.findTracks()).thenReturn(List.of(
                new SitemapEntry(42L, Instant.parse("2026-11-03T23:15:30Z")),
                new SitemapEntry(43L, null)));
        when(repository.findPatches()).thenReturn(List.of(
                new SitemapEntry(15L, Instant.parse("2026-11-04T00:15:30Z")),
                new SitemapEntry(16L, null)));
        var service = new SitemapService(repository, 300_000);

        String xml = service.sitemap(BASE_URL + "/a&b<c>");

        assertTrue(xml.startsWith("""
                <?xml version="1.0" encoding="UTF-8"?>
                <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
                """));
        String escapedBase = BASE_URL + "/a&amp;b&lt;c&gt;";
        for (String path : List.of(
                "/index.html", "/index.html?view=artists", "/index.html?view=tracks",
                "/index.html?view=patches", "/index.html?view=tutorials",
                "/index.html?view=archived-songs", "/index.html?view=archived-refills",
                "/index.html?view=links", "/index.html?view=about")) {
            assertTrue(xml.contains("<url><loc>" + escapedBase + path + "</loc></url>"));
        }
        assertTrue(xml.contains("<loc>" + escapedBase
                + "/tracks/42</loc><lastmod>2026-11-03</lastmod>"));
        assertTrue(xml.contains("<loc>" + escapedBase
                + "/patches/15</loc><lastmod>2026-11-04</lastmod>"));
        assertTrue(xml.contains("<loc>" + escapedBase + "/tracks/43</loc></url>"));
        assertTrue(xml.contains("<loc>" + escapedBase + "/patches/16</loc></url>"));
        assertTrue(xml.endsWith("</urlset>\n"));
        assertEquals(13, xml.lines().filter(line -> line.startsWith("  <url>")).count());
    }

    @Test
    void cachesCompletedXmlUntilConfiguredTtlAfterCompletion() {
        AtomicLong now = new AtomicLong();
        when(repository.findTracks()).thenAnswer(invocation -> {
            now.set(TimeUnit.MILLISECONDS.toNanos(100));
            return List.of(new SitemapEntry(42L, null));
        });
        when(repository.findPatches()).thenReturn(List.of());
        var service = new SitemapService(repository, 300_000, now::get);

        String first = service.sitemap(BASE_URL);
        now.set(TimeUnit.MILLISECONDS.toNanos(300_099));
        assertSame(first, service.sitemap(BASE_URL));
        verify(repository).findTracks();
        verify(repository).findPatches();

        when(repository.findTracks()).thenReturn(List.of(new SitemapEntry(43L, null)));
        now.set(TimeUnit.MILLISECONDS.toNanos(300_100));
        String refreshed = service.sitemap(BASE_URL);
        assertTrue(refreshed.contains("/tracks/43"));
        assertFalse(refreshed.contains("/tracks/42"));
        assertSame(refreshed, service.sitemap(BASE_URL));
        verify(repository, times(2)).findTracks();
        verify(repository, times(2)).findPatches();
    }

    @Test
    void cacheRemainsBoundedToOneBaseUrlAndCachesEmptyResults() {
        var service = new SitemapService(repository, 300_000);

        String first = service.sitemap(BASE_URL);
        assertSame(first, service.sitemap(BASE_URL));
        String other = service.sitemap("https://other.example");
        assertFalse(other.contains(BASE_URL));
        assertTrue(other.contains("https://other.example/index.html"));
        assertEquals(first, service.sitemap(BASE_URL));
        verify(repository, times(3)).findTracks();
        verify(repository, times(3)).findPatches();
    }

    @Test
    void concurrentRequestsShareOneLoadAndOnlyReceiveCompletedXml() throws Exception {
        CountDownLatch loadingPatches = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Thread> waitingThread = new AtomicReference<>();
        when(repository.findTracks()).thenReturn(List.of(new SitemapEntry(42L, null)));
        when(repository.findPatches()).thenAnswer(invocation -> {
            loadingPatches.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return List.of(new SitemapEntry(15L, null));
        });
        var service = new SitemapService(repository, 300_000);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> service.sitemap(BASE_URL));
            try {
                assertTrue(loadingPatches.await(5, TimeUnit.SECONDS));
                var second = executor.submit(() -> {
                    waitingThread.set(Thread.currentThread());
                    return service.sitemap(BASE_URL);
                });
                assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                    while (waitingThread.get() == null
                            || waitingThread.get().getState() != Thread.State.WAITING) {
                        if (Thread.currentThread().isInterrupted()) {
                            throw new AssertionError("Timed out waiting for the shared sitemap load.");
                        }
                        Thread.onSpinWait();
                    }
                });
                assertFalse(first.isDone());
                assertFalse(second.isDone());
                release.countDown();
                String xml = first.get(5, TimeUnit.SECONDS);
                assertSame(xml, second.get(5, TimeUnit.SECONDS));
                assertTrue(xml.contains("/tracks/42"));
                assertTrue(xml.contains("/patches/15"));
                assertTrue(xml.endsWith("</urlset>\n"));
                verify(repository).findTracks();
                verify(repository).findPatches();
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void failedPatchLoadIsSurfacedAndPartialXmlIsNeverCached() {
        var failure = new IllegalStateException("patch query failed");
        when(repository.findTracks()).thenReturn(List.of(new SitemapEntry(42L, null)));
        when(repository.findPatches()).thenThrow(failure)
                .thenReturn(List.of(new SitemapEntry(15L, null)));
        var service = new SitemapService(repository, 300_000);

        assertSame(failure, assertThrows(IllegalStateException.class, () -> service.sitemap(BASE_URL)));
        String recovered = service.sitemap(BASE_URL);
        assertTrue(recovered.contains("/patches/15"));
        assertSame(recovered, service.sitemap(BASE_URL));
        verify(repository, times(2)).findTracks();
        verify(repository, times(2)).findPatches();
    }

    @Test
    void expiredCacheDoesNotHideRefreshFailures() {
        AtomicLong now = new AtomicLong();
        var service = new SitemapService(repository, 20, now::get);
        service.sitemap(BASE_URL);
        now.set(TimeUnit.MILLISECONDS.toNanos(20));
        var failure = new IllegalStateException("track query failed");
        when(repository.findTracks()).thenThrow(failure);

        assertSame(failure, assertThrows(IllegalStateException.class, () -> service.sitemap(BASE_URL)));
        verify(repository).findPatches();
    }

    @Test
    void zeroTtlDisablesReuseAndNegativeTtlIsRejected() {
        var service = new SitemapService(repository, Duration.ZERO);
        service.sitemap(BASE_URL);
        service.sitemap(BASE_URL);

        verify(repository, times(2)).findTracks();
        verify(repository, times(2)).findPatches();
        assertThrows(IllegalArgumentException.class,
                () -> new SitemapService(repository, Duration.ofMillis(-1)));
    }
}

package org.reasonstation.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.reasonstation.entity.Track;
import org.reasonstation.repository.ArtistAliasTrackCount;
import org.reasonstation.repository.HighestRatedPatch;
import org.reasonstation.repository.HighestRatedTrack;
import org.reasonstation.repository.PatchRepository;
import org.reasonstation.repository.PopularArtist;
import org.reasonstation.repository.PopularTrack;
import org.reasonstation.repository.TrackRepository;
import org.reasonstation.repository.TrackWithRating;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Timeout(10)
class DiscoveryFeedServiceTest {
    private final TrackRepository tracks = mock(TrackRepository.class);
    private final PatchRepository patches = mock(PatchRepository.class);
    private final AtomicLong now = new AtomicLong();
    private final DiscoveryFeedService service = new DiscoveryFeedService(tracks, patches,
            Duration.ofMinutes(10), Duration.ofHours(1), Duration.ofMinutes(1), now::get);

    @Test
    void cachesAllSevenRawRepositoryFeedsIndependently() {
        List<ArtistAliasTrackCount> artists = List.of(mock(ArtistAliasTrackCount.class));
        List<TrackWithRating> random = List.of(mock(TrackWithRating.class));
        List<PopularTrack> popular = List.of(mock(PopularTrack.class));
        List<HighestRatedTrack> ratedTracks = List.of(mock(HighestRatedTrack.class));
        List<HighestRatedPatch> ratedPatches = List.of(mock(HighestRatedPatch.class));
        List<PopularArtist> popularArtists = List.of(mock(PopularArtist.class));
        List<Track> newest = List.of(new Track());
        when(tracks.findRandomArtistAliasesWithTrackCount(3)).thenReturn(artists);
        when(tracks.findRandomTracks(10)).thenReturn(random);
        when(tracks.findMostDownloadedTracksThisWeek(5)).thenReturn(popular);
        when(tracks.findHighestRatedTracksThisWeek(3)).thenReturn(ratedTracks);
        when(patches.findHighestRatedPatches(10)).thenReturn(ratedPatches);
        when(tracks.findMostDownloadedArtistsThisMonth(3)).thenReturn(popularArtists);
        when(tracks.findNewestTracks(10)).thenReturn(newest);

        for (int i = 0; i < 2; i++) {
            assertSame(artists, service.findRandomArtistAliasesWithTrackCount(3));
            assertSame(random, service.findRandomTracks(10));
            assertSame(popular, service.findMostDownloadedTracksThisWeek(5));
            assertSame(ratedTracks, service.findHighestRatedTracksThisWeek(3));
            assertSame(ratedPatches, service.findHighestRatedPatches(10));
            assertSame(popularArtists, service.findMostDownloadedArtistsThisMonth(3));
            assertSame(newest, service.findNewestTracks(10));
        }

        verify(tracks).findRandomArtistAliasesWithTrackCount(3);
        verify(tracks).findRandomTracks(10);
        verify(tracks).findMostDownloadedTracksThisWeek(5);
        verify(tracks).findHighestRatedTracksThisWeek(3);
        verify(patches).findHighestRatedPatches(10);
        verify(tracks).findMostDownloadedArtistsThisMonth(3);
        verify(tracks).findNewestTracks(10);
        verifyNoMoreInteractions(tracks, patches);
    }

    @Test
    void cachesEmptyResultsAndRefreshesAtConfiguredTtl() {
        List<Track> empty = List.of();
        List<Track> refreshed = List.of(new Track());
        when(tracks.findNewestTracks(10)).thenReturn(empty, refreshed);

        assertSame(empty, service.findNewestTracks(10));
        now.set(TimeUnit.MILLISECONDS.toNanos(59_999));
        assertSame(empty, service.findNewestTracks(10));
        now.set(TimeUnit.MILLISECONDS.toNanos(60_000));
        assertSame(refreshed, service.findNewestTracks(10));
        verify(tracks, times(2)).findNewestTracks(10);
    }

    @ParameterizedTest
    @CsvSource({
            "59999, 1, 1, 1",
            "60000, 1, 1, 2",
            "599999, 1, 1, 2",
            "600000, 2, 1, 2",
            "3599999, 2, 1, 2",
            "3600000, 2, 2, 2"
    })
    void expiresEachFeedAtItsOwnTtl(long elapsedMillis, int randomLoads, int rankedLoads, int newestLoads) {
        for (int i = 0; i < 2; i++) {
            service.findRandomArtistAliasesWithTrackCount(3);
            service.findRandomTracks(10);
            service.findMostDownloadedTracksThisWeek(5);
            service.findHighestRatedTracksThisWeek(3);
            service.findHighestRatedPatches(10);
            service.findMostDownloadedArtistsThisMonth(3);
            service.findNewestTracks(10);
            now.set(TimeUnit.MILLISECONDS.toNanos(elapsedMillis));
        }

        verify(tracks, times(randomLoads)).findRandomArtistAliasesWithTrackCount(3);
        verify(tracks, times(randomLoads)).findRandomTracks(10);
        verify(tracks, times(rankedLoads)).findMostDownloadedTracksThisWeek(5);
        verify(tracks, times(rankedLoads)).findHighestRatedTracksThisWeek(3);
        verify(patches, times(rankedLoads)).findHighestRatedPatches(10);
        verify(tracks, times(rankedLoads)).findMostDownloadedArtistsThisMonth(3);
        verify(tracks, times(newestLoads)).findNewestTracks(10);
        verifyNoMoreInteractions(tracks, patches);
    }

    @Test
    void failuresPropagateAndAreRetried() {
        var failure = new IllegalStateException("database unavailable");
        List<HighestRatedPatch> recovered = List.of();
        when(patches.findHighestRatedPatches(10)).thenThrow(failure).thenReturn(recovered);

        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> service.findHighestRatedPatches(10)));
        assertSame(recovered, service.findHighestRatedPatches(10));
        assertSame(recovered, service.findHighestRatedPatches(10));
        verify(patches, times(2)).findHighestRatedPatches(10);
    }

    @Test
    void differentLimitsNeverReuseTheWrongResult() {
        List<Track> three = List.of(new Track());
        List<Track> ten = List.of(new Track(), new Track());
        when(tracks.findNewestTracks(3)).thenReturn(three);
        when(tracks.findNewestTracks(10)).thenReturn(ten);

        assertSame(three, service.findNewestTracks(3));
        assertSame(ten, service.findNewestTracks(10));
        assertSame(three, service.findNewestTracks(3));
        verify(tracks, times(2)).findNewestTracks(3);
        verify(tracks).findNewestTracks(10);
    }

    @Test
    void acceptsDurationConfigurationAndAllowsDisablingResultReuse() {
        var uncached = new DiscoveryFeedService(tracks, patches,
                Duration.ZERO, Duration.ZERO, Duration.ZERO);
        when(tracks.findNewestTracks(10)).thenReturn(List.of());

        uncached.findNewestTracks(10);
        uncached.findNewestTracks(10);

        verify(tracks, times(2)).findNewestTracks(10);
    }

    @Test
    void loadingOneFeedDoesNotBlockAnotherFeed() throws Exception {
        CountDownLatch loading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<TrackWithRating> random = List.of();
        List<Track> newest = List.of();
        when(tracks.findRandomTracks(10)).thenAnswer(invocation -> {
            loading.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return random;
        });
        when(tracks.findNewestTracks(10)).thenReturn(newest);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var blockedFeed = executor.submit(() -> service.findRandomTracks(10));
            try {
                assertTrue(loading.await(5, TimeUnit.SECONDS));
                var otherFeed = executor.submit(() -> service.findNewestTracks(10));
                assertSame(newest, otherFeed.get(5, TimeUnit.SECONDS));
            } finally {
                release.countDown();
            }
            assertSame(random, blockedFeed.get(5, TimeUnit.SECONDS));
        }
    }
}

package org.reasonstation.service;

import org.reasonstation.entity.Track;
import org.reasonstation.repository.ArtistAliasTrackCount;
import org.reasonstation.repository.HighestRatedPatch;
import org.reasonstation.repository.HighestRatedTrack;
import org.reasonstation.repository.PatchRepository;
import org.reasonstation.repository.PopularArtist;
import org.reasonstation.repository.PopularTrack;
import org.reasonstation.repository.TrackRepository;
import org.reasonstation.repository.TrackWithRating;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.function.LongSupplier;

@Service
public class DiscoveryFeedService {
    private final TrackRepository tracks;
    private final PatchRepository patches;
    private final SingleFlightCache<Integer, List<ArtistAliasTrackCount>> randomArtists;
    private final SingleFlightCache<Integer, List<TrackWithRating>> randomTracks;
    private final SingleFlightCache<Integer, List<PopularTrack>> popularTracks;
    private final SingleFlightCache<Integer, List<HighestRatedTrack>> highestRatedTracks;
    private final SingleFlightCache<Integer, List<HighestRatedPatch>> highestRatedPatches;
    private final SingleFlightCache<Integer, List<PopularArtist>> popularArtists;
    private final SingleFlightCache<Integer, List<Track>> newestTracks;

    @Autowired
    public DiscoveryFeedService(TrackRepository tracks, PatchRepository patches,
                               @Value("${app.discovery.random-cache-ttl:PT10M}") Duration randomTtl,
                               @Value("${app.discovery.ranked-cache-ttl:PT1H}") Duration rankedTtl,
                               @Value("${app.discovery.newest-cache-ttl:PT1M}") Duration newestTtl) {
        this(tracks, patches, randomTtl, rankedTtl, newestTtl, System::nanoTime);
    }

    DiscoveryFeedService(TrackRepository tracks, PatchRepository patches,
                         Duration randomTtl, Duration rankedTtl, Duration newestTtl,
                         LongSupplier ticker) {
        this.tracks = tracks;
        this.patches = patches;
        randomArtists = new SingleFlightCache<>(randomTtl.toMillis(), ticker);
        randomTracks = new SingleFlightCache<>(randomTtl.toMillis(), ticker);
        popularTracks = new SingleFlightCache<>(rankedTtl.toMillis(), ticker);
        highestRatedTracks = new SingleFlightCache<>(rankedTtl.toMillis(), ticker);
        highestRatedPatches = new SingleFlightCache<>(rankedTtl.toMillis(), ticker);
        popularArtists = new SingleFlightCache<>(rankedTtl.toMillis(), ticker);
        newestTracks = new SingleFlightCache<>(newestTtl.toMillis(), ticker);
    }

    public List<ArtistAliasTrackCount> findRandomArtistAliasesWithTrackCount(int limit) {
        return randomArtists.get(limit, () -> tracks.findRandomArtistAliasesWithTrackCount(limit));
    }

    public List<TrackWithRating> findRandomTracks(int limit) {
        return randomTracks.get(limit, () -> tracks.findRandomTracks(limit));
    }

    public List<PopularTrack> findMostDownloadedTracksThisWeek(int limit) {
        return popularTracks.get(limit, () -> tracks.findMostDownloadedTracksThisWeek(limit));
    }

    public List<HighestRatedTrack> findHighestRatedTracksThisWeek(int limit) {
        return highestRatedTracks.get(limit, () -> tracks.findHighestRatedTracksThisWeek(limit));
    }

    public List<HighestRatedPatch> findHighestRatedPatches(int limit) {
        return highestRatedPatches.get(limit, () -> patches.findHighestRatedPatches(limit));
    }

    public List<PopularArtist> findMostDownloadedArtistsThisMonth(int limit) {
        return popularArtists.get(limit, () -> tracks.findMostDownloadedArtistsThisMonth(limit));
    }

    public List<Track> findNewestTracks(int limit) {
        return newestTracks.get(limit, () -> tracks.findNewestTracks(limit));
    }
}

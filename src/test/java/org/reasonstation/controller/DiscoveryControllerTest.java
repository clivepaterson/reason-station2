package org.reasonstation.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.reasonstation.repository.PatchWithRating;
import org.reasonstation.entity.Track;
import org.reasonstation.repository.HighestRatedPatch;
import org.reasonstation.repository.HighestRatedTrack;
import org.reasonstation.repository.ArtistAliasTrackCount;
import org.reasonstation.repository.PopularArtist;
import org.reasonstation.repository.PatchRepository;
import org.reasonstation.repository.PopularTrack;
import org.reasonstation.repository.TrackRepository;
import org.reasonstation.repository.TrackWithRating;
import org.reasonstation.repository.TrackSearchResult;
import org.reasonstation.service.S3StorageService;
import org.reasonstation.service.DiscoveryFeedService;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class DiscoveryControllerTest {
    @Mock
    private TrackRepository tracks;
    @Mock
    private PatchRepository patches;
    @Mock
    private S3StorageService storage;
    @Mock
    private DiscoveryFeedService feeds;

    private DiscoveryController controller;

    @BeforeEach
    void setUp() {
        controller = new DiscoveryController(tracks, patches, storage, feeds);
    }

    @Test
    void returnsPublicTrackCardMetadataWithoutOwnerOrProjectStorageDetails() throws Exception {
        var uploaded = java.time.Instant.parse("2026-11-03T12:00:00Z");
        var track = new TrackWithRating(42L, 7L, "Artist", "Song", "Rock", "song.reason", 1024L,
                "s3://private/project", uploaded, 8.5, 3, "https://soundcloud.com/artist/song", "13",
                "s3://tracks/image", "s3://tracks/audio", 2048L);
        when(tracks.findWithRatingById(42L)).thenReturn(Optional.of(track));
        when(storage.createImageUrl("s3://tracks/image"))
                .thenReturn(java.net.URI.create("https://storage.example/cover"));

        MockMvcBuilders.standaloneSetup(controller).build()
                .perform(get("/api/discovery/tracks/42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trackid").value(42))
                .andExpect(jsonPath("$.trackName").value("Song"))
                .andExpect(jsonPath("$.artistAlias").value("Artist"))
                .andExpect(jsonPath("$.musicStyle").value("Rock"))
                .andExpect(jsonPath("$.audioUri").value("s3://tracks/audio"))
                .andExpect(jsonPath("$.imageUri").value("s3://tracks/image"))
                .andExpect(jsonPath("$.imageUrl").value("https://storage.example/cover"))
                .andExpect(jsonPath("$.filename").value("song.reason"))
                .andExpect(jsonPath("$.filesize").value(1024))
                .andExpect(jsonPath("$.audioFilesize").value(2048))
                .andExpect(jsonPath("$.downloadCount").value(3))
                .andExpect(jsonPath("$.averageTrackRating").value(8.5))
                .andExpect(jsonPath("$.dateUploaded").value(uploaded.toString()))
                .andExpect(jsonPath("$.programVersion").value("13"))
                .andExpect(jsonPath("$.audioLink").value("https://soundcloud.com/artist/song"))
                .andExpect(jsonPath("$.userid").doesNotExist())
                .andExpect(jsonPath("$.trackUri").doesNotExist());
        verify(storage).createImageUrl("s3://tracks/image");
    }

    @Test
    void returnsNotFoundForMissingEmbeddedTrack() {
        when(tracks.findWithRatingById(42L)).thenReturn(Optional.empty());
        assertEquals(HttpStatus.NOT_FOUND, controller.track(42L).getStatusCode());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "s3://tracks/track-audio/preview"})
    void includesPlayerMetadataInSidebarTrackFeeds(String audioUri) throws Exception {
        String imageUri = "s3://tracks/track-images/cover";
        var uploaded = java.time.Instant.parse("2026-11-03T12:00:00Z");
        var newest = new Track(7L, "Artist", "Track", "Rock", "song.reason", 1024L, "s3://tracks/project");
        newest.setTrackid(42L);
        newest.setDateUploaded(uploaded);
        newest.setAudioUri(audioUri);
        newest.setImageUri(imageUri);
        when(feeds.findMostDownloadedTracksThisWeek(5)).thenReturn(List.of(
                new PopularTrack(42L, "Artist", "Track", "Rock", "song.reason", 2, audioUri, imageUri)));
        when(feeds.findHighestRatedTracksThisWeek(3)).thenReturn(List.of(
                new HighestRatedTrack(42L, "Artist", "Track", "Rock", "song.reason", 8.5, 3L, audioUri, imageUri)));
        when(feeds.findNewestTracks(10)).thenReturn(List.of(newest));
        var mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        for (String feed : List.of("popular-tracks", "highest-rated-tracks", "newest-tracks")) {
            mockMvc.perform(get("/api/discovery/" + feed))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].trackid").value(42))
                    .andExpect(jsonPath("$[0].artistAlias").value("Artist"))
                    .andExpect(jsonPath("$[0].trackName").value("Track"))
                    .andExpect(jsonPath("$[0].musicStyle").value("Rock"))
                    .andExpect(jsonPath("$[0].filename").value("song.reason"))
                    .andExpect(jsonPath("$[0].audioUri").value(audioUri))
                    .andExpect(jsonPath("$[0].imageUri").value(imageUri))
                    .andExpect(jsonPath("$[0].trackUri").doesNotExist());
        }
        mockMvc.perform(get("/api/discovery/popular-tracks"))
                .andExpect(jsonPath("$[0].downloadCount").value(2));
        mockMvc.perform(get("/api/discovery/highest-rated-tracks"))
                .andExpect(jsonPath("$[0].averageTrackRating").value(8.5))
                .andExpect(jsonPath("$[0].ratingCount").value(3));
        mockMvc.perform(get("/api/discovery/newest-tracks"))
                .andExpect(jsonPath("$[0].dateUploaded").value(uploaded.toString()));
        verifyNoInteractions(storage);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void rejectsInvalidEmbeddedTrackId(long id) {
        assertEquals(HttpStatus.BAD_REQUEST, controller.track(id).getStatusCode());
        verifyNoInteractions(tracks, storage);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "s3://tracks/patch-images/bass"})
    void returnsPublicPatchCardMetadata(String imageUri) throws Exception {
        var uploaded = java.time.Instant.parse("2026-11-03T12:00:00Z");
        boolean hasImage = imageUri != null && !imageUri.isBlank();
        String imageUrl = hasImage ? "https://storage.example/bass?signature=test" : null;
        when(patches.findWithRatingById(42L)).thenReturn(Optional.of(
                new PatchWithRating(42L, "Artist", "Warm Bass", "Bass patch",
                        "warm-bass.thor", ".thor", 1024L, imageUri, uploaded, 8.5)));
        if (hasImage) {
            when(storage.createImageUrl(imageUri)).thenReturn(java.net.URI.create(imageUrl));
        }

        MockMvcBuilders.standaloneSetup(controller).build()
                .perform(get("/api/discovery/patches/42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.patchid").value(42))
                .andExpect(jsonPath("$.artistAlias").value("Artist"))
                .andExpect(jsonPath("$.patchName").value("Warm Bass"))
                .andExpect(jsonPath("$.description").value("Bass patch"))
                .andExpect(jsonPath("$.filename").value("warm-bass.thor"))
                .andExpect(jsonPath("$.ext").value(".thor"))
                .andExpect(jsonPath("$.filesize").value(1024))
                .andExpect(jsonPath("$.dateUploaded").value(uploaded.toString()))
                .andExpect(jsonPath("$.averagePatchRating").value(8.5))
                .andExpect(jsonPath("$.patchImgUri").value(imageUri))
                .andExpect(jsonPath("$.imageUrl").value(imageUrl))
                .andExpect(jsonPath("$.userid").doesNotExist())
                .andExpect(jsonPath("$.patchUri").doesNotExist());

        verifyNoInteractions(tracks);
        if (!hasImage) {
            verifyNoInteractions(storage);
        }
    }

    @Test
    void returnsNotFoundForMissingPatch() throws Exception {
        when(patches.findWithRatingById(42L)).thenReturn(Optional.empty());
        MockMvcBuilders.standaloneSetup(controller).build()
                .perform(get("/api/discovery/patches/42"))
                .andExpect(status().isNotFound());
        verifyNoInteractions(tracks, storage);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void rejectsInvalidPatchId(long id) throws Exception {
        MockMvcBuilders.standaloneSetup(controller).build()
                .perform(get("/api/discovery/patches/" + id))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Patch ID must be a positive integer."));
        verifyNoInteractions(tracks, patches, storage);
    }

    @Test
    void listsPatchesForArtistAlias() throws Exception {
        var patch = new PatchWithRating(7L, "Artist", "Warm Bass", "Bass patch",
                "warm-bass.thor", ".thor", 1024L, null, java.time.Instant.now(), 8.5);
        when(patches.findWithRatingsByArtistAlias("Artist", 20, 0))
                .thenReturn(List.of(patch));
        when(patches.countByArtistAlias("Artist")).thenReturn(1L);

        MockMvcBuilders.standaloneSetup(controller).build()
                .perform(get("/api/discovery/artist-patches").param("artistAlias", "Artist"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results.length()").value(1))
                .andExpect(jsonPath("$.results[0].averagePatchRating").value(8.5))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.pageSize").value(20))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1));
        verify(patches).findWithRatingsByArtistAlias("Artist", 20, 0);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "s3://tracks/patch-images/bass"})
    void includesImageUrlsInArtistPatchFeed(String imageUri) throws Exception {
        boolean hasImage = imageUri != null && !imageUri.isBlank();
        String imageUrl = hasImage ? "https://storage.example/bass?signature=test" : null;
        if (hasImage) {
            when(storage.createImageUrl(imageUri)).thenReturn(java.net.URI.create(imageUrl));
        }
        when(patches.findWithRatingsByArtistAlias("Artist", 20, 0)).thenReturn(List.of(
                new PatchWithRating(7L, "Artist", "Warm Bass", "Bass patch",
                        "warm-bass.thor", ".thor", 1024L, imageUri, java.time.Instant.now(), 8.5)));

        MockMvcBuilders.standaloneSetup(controller).build()
                .perform(get("/api/discovery/artist-patches").param("artistAlias", "Artist"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].patchImgUri").value(imageUri))
                .andExpect(jsonPath("$.results[0].imageUrl").value(imageUrl))
                .andExpect(jsonPath("$.results[0].averagePatchRating").value(8.5))
                .andExpect(jsonPath("$.results[0].filesize").value(1024));

        if (!hasImage) {
            verifyNoInteractions(storage);
        }
    }

    @Test
    void listsTopTenHighestRatedPatches() throws Exception {
        var patch = new HighestRatedPatch(7L, "Artist", "Warm Bass", 8.5, 12L);
        when(feeds.findHighestRatedPatches(10)).thenReturn(List.of(patch));

        MockMvcBuilders.standaloneSetup(controller).build()
                .perform(get("/api/discovery/highest-rated-patches"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].patchid").value(7))
                .andExpect(jsonPath("$[0].artistAlias").value("Artist"))
                .andExpect(jsonPath("$[0].patchName").value("Warm Bass"))
                .andExpect(jsonPath("$[0].averagePatchRating").value(8.5))
                .andExpect(jsonPath("$[0].ratingCount").value(12));

        verify(feeds).findHighestRatedPatches(10);
        verifyNoInteractions(tracks);
    }

    @Test
    void returnsEmptyChartWhenNoPatchesHaveRatings() throws Exception {
        when(feeds.findHighestRatedPatches(10)).thenReturn(List.of());

        MockMvcBuilders.standaloneSetup(controller).build()
                .perform(get("/api/discovery/highest-rated-patches"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void mapsCachedArtistFeedsWithoutQueryingRepositories() throws Exception {
        var created = java.time.Instant.parse("2026-11-03T12:00:00Z");
        when(feeds.findRandomArtistAliasesWithTrackCount(3)).thenReturn(List.of(
                new ArtistAliasTrackCount("Artist", 42, created)));
        when(feeds.findMostDownloadedArtistsThisMonth(3)).thenReturn(List.of(new PopularArtist("Artist", 17)));
        var mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        mockMvc.perform(get("/api/discovery/artists-with-track-count"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].artistAlias").value("Artist"))
                .andExpect(jsonPath("$[0].trackCount").value(42))
                .andExpect(jsonPath("$[0].dateCreated").value(created.toString()));
        mockMvc.perform(get("/api/discovery/popular-artists"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].artistAlias").value("Artist"))
                .andExpect(jsonPath("$[0].downloadCount").value(17));

        verifyNoInteractions(tracks, patches, storage);
    }

    @ParameterizedTest
    @ValueSource(strings = {"artists-with-track-count", "tracks", "popular-tracks",
            "highest-rated-tracks", "popular-artists", "highest-rated-patches", "newest-tracks"})
    void servesHomeFeedsThroughFeedServiceWithoutDirectQueries(String feed) throws Exception {
        MockMvcBuilders.standaloneSetup(controller).build()
                .perform(get("/api/discovery/" + feed))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        verifyNoInteractions(tracks, patches, storage);
    }

    @Test
    void trimsArtistAliasBeforeLookup() {
        when(tracks.findByArtistAlias("Artist", 20, 0)).thenReturn(List.of());

        var response = controller.artistTracks("  Artist  ", 1);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(tracks).findByArtistAlias("Artist", 20, 0);
        verify(tracks).countByArtistAlias("Artist");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = "13.2.1")
    void includesAudioLinksAndProgramVersionsInAllTrackCardFeeds(String programVersion) throws Exception {
        String audioLink = "https://audiomack.com/artist/song/track";
        String imageUri = "s3://tracks/track-images/cover";
        String imageUrl = "https://storage.example/cover?signature=test";
        when(storage.createImageUrl(imageUri)).thenReturn(java.net.URI.create(imageUrl));
        String audioUri = "s3://tracks/track-audio/preview";
        Long audioFilesize = programVersion == null ? null : 1572864L;
        var track = new TrackWithRating(42L, 7L, "Artist", "Track", "Rock",
                "song.reason", 3L, "s3://tracks/song", java.time.Instant.now(), 8.0, 2, audioLink, programVersion,
                imageUri, audioUri, audioFilesize);
        when(feeds.findRandomTracks(10)).thenReturn(List.of(track));
        when(tracks.findByArtistAlias("Artist", 20, 0)).thenReturn(List.of(track));
        when(tracks.searchByArtistAliasOrTrackName("Track", 20, 0)).thenReturn(List.of(
                new TrackSearchResult(track.trackid(), track.artistAlias(), track.trackName(),
                        track.musicStyle(), track.filename(), track.filesize(), track.dateUploaded(),
                        track.averageTrackRating(), track.downloadCount(), audioLink, track.programVersion(),
                        imageUri, audioUri, audioFilesize)));
        when(tracks.countByArtistAliasOrTrackName("Track")).thenReturn(1L);
        var mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        mockMvc.perform(get("/api/discovery/tracks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].audioLink").value(audioLink))
                .andExpect(jsonPath("$[0].imageUri").value(imageUri))
                .andExpect(jsonPath("$[0].imageUrl").value(imageUrl))
                .andExpect(jsonPath("$[0].audioUri").value(audioUri))
                .andExpect(jsonPath("$[0].audioFilesize").value(audioFilesize))
                .andExpect(jsonPath("$[0].programVersion").value(programVersion));
        mockMvc.perform(get("/api/discovery/artist-tracks").param("artistAlias", "Artist"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].audioLink").value(audioLink))
                .andExpect(jsonPath("$.results[0].imageUri").value(imageUri))
                .andExpect(jsonPath("$.results[0].imageUrl").value(imageUrl))
                .andExpect(jsonPath("$.results[0].audioUri").value(audioUri))
                .andExpect(jsonPath("$.results[0].audioFilesize").value(audioFilesize))
                .andExpect(jsonPath("$.results[0].programVersion").value(programVersion));
        mockMvc.perform(get("/api/discovery/search").param("query", "Track"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].audioLink").value(audioLink))
                .andExpect(jsonPath("$.results[0].imageUri").value(imageUri))
                .andExpect(jsonPath("$.results[0].imageUrl").value(imageUrl))
                .andExpect(jsonPath("$.results[0].audioUri").value(audioUri))
                .andExpect(jsonPath("$.results[0].audioFilesize").value(audioFilesize))
                .andExpect(jsonPath("$.results[0].programVersion").value(programVersion));
    }

    @Test
    void rejectsBlankArtistAlias() {
        var response = controller.artistTracks(" ", 1);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verifyNoInteractions(tracks);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "   ")
    void listsArtistTracksWithoutSigningMissingImages(String imageUri) throws Exception {
        when(tracks.findByArtistAlias("Artist", 20, 0)).thenReturn(List.of(
                new TrackWithRating(42L, 7L, "Artist", "Track", "Rock", "song.reason", 3L,
                        "s3://tracks/song", java.time.Instant.now(), null, 0, null, null,
                        imageUri, null, null)));

        MockMvcBuilders.standaloneSetup(controller).build()
                .perform(get("/api/discovery/artist-tracks").param("artistAlias", "Artist"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].imageUrl").doesNotExist());

        verifyNoInteractions(storage);
    }

    @Test
    void rejectsMissingSearchTerm() {
        var response = controller.searchTracks(null, 1);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verifyNoInteractions(tracks);
    }

    @ParameterizedTest
    @ValueSource(strings = {"artist-tracks", "artist-patches"})
    void paginatesArtistFeedsWithTwentyResults(String feed) throws Exception {
        if (feed.equals("artist-tracks")) {
            when(tracks.countByArtistAlias("Artist")).thenReturn(41L);
            when(tracks.findByArtistAlias("Artist", 20, 20L)).thenReturn(List.of());
        } else {
            when(patches.countByArtistAlias("Artist")).thenReturn(41L);
            when(patches.findWithRatingsByArtistAlias("Artist", 20, 20L)).thenReturn(List.of());
        }
        MockMvcBuilders.standaloneSetup(controller).build()
                .perform(get("/api/discovery/" + feed).param("artistAlias", " Artist ").param("page", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results").isArray())
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.pageSize").value(20))
                .andExpect(jsonPath("$.totalElements").value(41))
                .andExpect(jsonPath("$.totalPages").value(3));
        if (feed.equals("artist-tracks")) {
            verify(tracks).findByArtistAlias("Artist", 20, 20L);
            verifyNoInteractions(patches);
        } else {
            verify(patches).findWithRatingsByArtistAlias("Artist", 20, 20L);
            verifyNoInteractions(tracks);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"artist-tracks", "artist-patches"})
    void returnsEmptyArtistPagesBeyondLastPageWithoutOffsetOverflow(String feed) throws Exception {
        long offset = (Integer.MAX_VALUE - 1L) * 20;
        if (feed.equals("artist-tracks")) {
            when(tracks.countByArtistAlias("Artist")).thenReturn(20L);
            when(tracks.findByArtistAlias("Artist", 20, offset)).thenReturn(List.of());
        } else {
            when(patches.countByArtistAlias("Artist")).thenReturn(20L);
            when(patches.findWithRatingsByArtistAlias("Artist", 20, offset)).thenReturn(List.of());
        }
        MockMvcBuilders.standaloneSetup(controller).build()
                .perform(get("/api/discovery/" + feed).param("artistAlias", "Artist")
                        .param("page", Integer.toString(Integer.MAX_VALUE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results").isEmpty())
                .andExpect(jsonPath("$.page").value(Integer.MAX_VALUE))
                .andExpect(jsonPath("$.totalPages").value(1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"artist-tracks", "artist-patches"})
    void defaultsEmptyArtistFeedsToFirstPage(String feed) throws Exception {
        MockMvcBuilders.standaloneSetup(controller).build()
                .perform(get("/api/discovery/" + feed).param("artistAlias", "Artist"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results").isEmpty())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.pageSize").value(20))
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.totalPages").value(0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "no", "2147483648"})
    void rejectsInvalidArtistPagesBeforeRepositoryLookup(String page) throws Exception {
        var mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        for (String feed : List.of("artist-tracks", "artist-patches")) {
            mockMvc.perform(get("/api/discovery/" + feed).param("artistAlias", "Artist").param("page", page))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(tracks, patches, storage);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "   ")
    void rejectsInvalidArtistAliasesBeforeRepositoryLookup(String alias) {
        assertEquals(HttpStatus.BAD_REQUEST, controller.artistTracks(alias, 1).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, controller.artistPatches(alias, 1).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, controller.artistTracks("a".repeat(256), 1).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, controller.artistPatches("a".repeat(256), 1).getStatusCode());
        verifyNoInteractions(tracks, patches, storage);
    }
}

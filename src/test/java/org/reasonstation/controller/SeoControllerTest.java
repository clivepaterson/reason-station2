package org.reasonstation.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.reasonstation.entity.Track;
import org.reasonstation.entity.Patch;
import org.reasonstation.repository.PatchRepository;
import org.reasonstation.repository.TrackRepository;
import org.reasonstation.repository.TrackWithRating;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SeoControllerTest {
    @Mock
    private TrackRepository tracks;
    @Mock
    private PatchRepository patches;

    private SeoController controller;

    @BeforeEach
    void setUp() {
        controller = new SeoController(tracks, patches, new ObjectMapper(), "https://reasonstation.example/");
    }

    @Test
    void rendersSearchAndSocialMetadataForTrackDirectLink() {
        when(tracks.findWithRatingById(42L)).thenReturn(Optional.of(track()));

        var response = controller.trackPage(42L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        String html = response.getBody();
        assertTrue(html.contains("<title>Signal &amp; Noise by A &amp; B | Reason Station</title>"));
        assertTrue(html.contains("<link rel=\"canonical\" href=\"https://reasonstation.example/tracks/42\">"));
        assertTrue(html.contains("<meta property=\"og:type\" content=\"music.song\">"));
        assertTrue(html.contains("\"@type\":\"MusicRecording\""));
        assertTrue(html.contains("href=\"/index.html\">Home</a>"));
        assertTrue(html.contains("href=\"/api/tracks/42/download\""));
        assertTrue(html.contains("window.createRateTrackButton"));
        assertTrue(html.contains("<script src=\"/js/copy_link.js\"></script>"));
        assertTrue(html.contains("window.createCopyLinkButton('/tracks/' + encodeURIComponent(track.trackid), track.trackName)"));
        assertTrue(html.contains("\"trackid\":42"));
        assertTrue(html.contains("class=\"track-card-art\""));
        assertTrue(html.contains("<script src=\"/js/style_icons.js\"></script>"));
        assertTrue(html.contains("window.createMusicStyleIcon(track.musicStyle)"));
        assertTrue(html.contains("<a class=\"track-file-meta\" href=\"/api/tracks/42/download\""));
        assertTrue(html.contains("class=\"track-style-meta\">Style: Electronic"));
        assertTrue(html.contains("class=\"track-filesize-meta\">Size: 1 Kb"));
        assertTrue(html.contains("class=\"track-downloads-meta\">Downloads: 12"));
        assertTrue(html.contains("class=\"track-rating-stars\""));
        assertTrue(html.contains("--rating-percent: 85.0%"));
        assertTrue(html.contains("class=\"track-rating-value\" aria-hidden=\"true\">8.5/10"));
        assertTrue(html.contains("href=\"https://soundcloud.com/artist/song?one=1&amp;two=2\""
                + " target=\"_blank\" rel=\"noopener noreferrer\">"
                + "https://soundcloud.com/artist/song?one=1&amp;two=2</a>"));
    }

    @Test
    void omitsAudioLinkWhenTrackHasNone() {
        TrackWithRating track = track();
        when(tracks.findWithRatingById(42L)).thenReturn(Optional.of(new TrackWithRating(
                track.trackid(), track.userid(), track.artistAlias(), track.trackName(),
                track.musicStyle(), track.filename(), track.filesize(), track.trackUri(),
                track.dateUploaded(), track.averageTrackRating(), track.downloadCount(), null, track.programVersion())));

        assertFalse(controller.trackPage(42L).getBody().contains("track-audio-link"));
    }

    @Test
    void showsProgramVersionAfterFilenameWhenAvailable() {
        when(tracks.findWithRatingById(42L)).thenReturn(Optional.of(track()));

        assertTrue(controller.trackPage(42L).getBody().contains(">signal.reason (Reason 13.2.1)</a>"));
    }

    @Test
    void omitsProgramVersionWhenTrackHasNone() {
        TrackWithRating track = track();
        when(tracks.findWithRatingById(42L)).thenReturn(Optional.of(new TrackWithRating(
                track.trackid(), track.userid(), track.artistAlias(), track.trackName(),
                track.musicStyle(), track.filename(), track.filesize(), track.trackUri(),
                track.dateUploaded(), track.averageTrackRating(), track.downloadCount(), track.audioLink(), null)));

        assertTrue(controller.trackPage(42L).getBody().contains(">signal.reason</a>"));
    }

    @Test
    void returnsNoindexPageWhenTrackDoesNotExist() {
        when(tracks.findWithRatingById(99L)).thenReturn(Optional.empty());

        var response = controller.trackPage(99L);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertTrue(response.getBody().contains("<meta name=\"robots\" content=\"noindex, follow\">"));
    }

    @Test
    void rejectsNonPositiveTrackId() {
        var response = controller.trackPage(0L);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void includesTracksInSitemapAndAdvertisesItToCrawlers() {
        Track track = new Track();
        track.setTrackid(42L);
        track.setDateUploaded(Instant.parse("2026-11-03T10:15:30Z"));
        when(tracks.findAll()).thenReturn(List.of(track));
        when(patches.findAll()).thenReturn(List.of(patch()));

        String sitemap = controller.sitemap();

        assertTrue(sitemap.contains("<loc>https://reasonstation.example/index.html</loc>"));
        assertTrue(sitemap.contains("<loc>https://reasonstation.example/tracks/42</loc>"));
        assertTrue(sitemap.contains("<lastmod>2026-11-03</lastmod>"));
        assertTrue(sitemap.contains("<loc>https://reasonstation.example/patches/15</loc>"));
        assertTrue(controller.robots().contains(
                "Sitemap: https://reasonstation.example/sitemap.xml"));
    }

    @Test
    void rendersPatchMetadataAndAllPublicDetails() {
        when(patches.findById(15L)).thenReturn(Optional.of(patch()));

        var response = controller.patchPage(15L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        String html = response.getBody();
        assertTrue(html.contains("<title>Warm &amp; Deep by A &amp; B | Reason Station</title>"));
        assertTrue(html.contains("<link rel=\"canonical\" href=\"https://reasonstation.example/patches/15\">"));
        assertTrue(html.contains("\"@type\":\"CreativeWork\""));
        assertTrue(html.contains("content=\"https://reasonstation.example/api/patches/15/image\""));
        assertTrue(html.contains("href=\"/api/patches/15/download\""));
        assertTrue(html.contains("Bass &lt;description&gt;"));
        assertTrue(html.contains("bass.thor"));
        assertTrue(html.contains("class=\"track-style-badge\">.thor"));
        assertTrue(html.contains("class=\"track-filesize-meta\">Size: 1 Kb"));
        assertTrue(html.contains("class=\"track-card-art patch-card-image\" data-image-zoom"));
        assertTrue(html.contains("class=\"patch-card-heading\""));
        assertTrue(html.contains("class=\"patch-card-identity\""));
        assertTrue(html.contains("class=\"track-card-meta patch-card-meta\""));
        assertTrue(html.contains("class=\"patch-uploaded-meta\""));
        assertTrue(html.contains("Uploaded: 2026-11-03"));
        assertTrue(html.contains("<script src=\"/js/image_zoom.js\"></script>"));
        assertTrue(html.contains("<script src=\"/js/track_rating.js\"></script>"));
        assertTrue(html.contains("window.createRatePatchButton"));
        assertTrue(html.contains("<script src=\"/js/copy_link.js\"></script>"));
        assertTrue(html.contains("window.createCopyLinkButton('/patches/' + encodeURIComponent(patch.patchid), patch.patchName)"));
        assertTrue(html.contains("\"patchid\":15"));
        assertTrue(html.contains("\"patchName\":\"Warm & Deep\""));
    }

    @Test
    void rendersPatchWithoutImageOrOptionalMetadata() {
        Patch patch = patch();
        patch.setPatchImgUri(null);
        patch.setDescription(null);
        patch.setFilesize(null);
        patch.setDateUploaded(null);
        when(patches.findById(15L)).thenReturn(Optional.of(patch));

        var response = controller.patchPage(15L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().contains(
                "content=\"https://reasonstation.example/images/logo_by_chatgpt.jpg\""));
        assertTrue(response.getBody().contains(
                "class=\"track-card-art patch-card-image\" src=\"/images/icon-patch.svg\" alt=\"\""));
        assertFalse(response.getBody().contains("data-image-zoom"));
        assertFalse(response.getBody().contains("class=\"patch-description\""));
        assertFalse(response.getBody().contains("class=\"track-filesize-meta\""));
        assertFalse(response.getBody().contains("class=\"patch-uploaded-meta\""));
    }

    @Test
    void formatsPatchFilesizesLikeTheSharedCards() {
        Patch patch = patch();
        when(patches.findById(15L)).thenReturn(Optional.of(patch));
        long[] sizes = {0, 1, 1023, 1536, 1048576, 1572864};
        String[] labels = {"0 bytes", "1 byte", "1,023 bytes", "1.5 Kb", "1 Mb", "1.5 Mb"};
        for (int i = 0; i < sizes.length; i++) {
            patch.setFilesize(sizes[i]);
            assertTrue(controller.patchPage(15L).getBody().contains(
                    "class=\"track-filesize-meta\">Size: " + labels[i]), labels[i]);
        }
    }

    @Test
    void returnsNoindexPageForUnknownPatch() {
        when(patches.findById(99L)).thenReturn(Optional.empty());

        var response = controller.patchPage(99L);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertTrue(response.getBody().contains("Patch not found"));
        assertTrue(response.getBody().contains("content=\"noindex, follow\""));
    }

    @Test
    void rejectsNonPositivePatchId() {
        assertEquals(HttpStatus.BAD_REQUEST, controller.patchPage(0L).getStatusCode());
    }

    private Patch patch() {
        Patch patch = new Patch(7L, "A & B", "Warm & Deep", "Bass <description>",
                "bass.thor", ".thor", 1024L, "s3://tracks/patches/bass");
        patch.setPatchid(15L);
        patch.setPatchImgUri("s3://tracks/patch-images/bass");
        patch.setDateUploaded(Instant.parse("2026-11-03T10:15:30Z"));
        return patch;
    }

    private TrackWithRating track() {
        return new TrackWithRating(
                42L, 7L, "A & B", "Signal & Noise", "Electronic",
                "signal.reason", 1024L, "s3://tracks/signal",
                Instant.parse("2026-11-03T10:15:30Z"), 8.5, 12,
                "https://soundcloud.com/artist/song?one=1&two=2", "13.2.1");
    }
}

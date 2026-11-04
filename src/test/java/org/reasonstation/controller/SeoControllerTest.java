package org.reasonstation.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.reasonstation.entity.Track;
import org.reasonstation.repository.TrackRepository;
import org.reasonstation.repository.TrackWithRating;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SeoControllerTest {
    @Mock
    private TrackRepository tracks;

    private SeoController controller;

    @BeforeEach
    void setUp() {
        controller = new SeoController(tracks, new ObjectMapper(), "https://reasonstation.example/");
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
        assertTrue(html.contains("\"trackid\":42"));
    }

    @Test
    void returnsNoindexPageWhenTrackDoesNotExist() {
        when(tracks.findWithRatingById(99L)).thenReturn(Optional.empty());

        var response = controller.trackPage(99L);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertTrue(response.getBody().contains("<meta name=\"robots\" content=\"noindex, follow\">"));
    }

    @Test
    void includesTracksInSitemapAndAdvertisesItToCrawlers() {
        Track track = new Track();
        track.setTrackid(42L);
        track.setDateUploaded(Instant.parse("2026-11-03T10:15:30Z"));
        when(tracks.findAll()).thenReturn(List.of(track));

        String sitemap = controller.sitemap();

        assertTrue(sitemap.contains("<loc>https://reasonstation.example/index.html</loc>"));
        assertTrue(sitemap.contains("<loc>https://reasonstation.example/tracks/42</loc>"));
        assertTrue(sitemap.contains("<lastmod>2026-11-03</lastmod>"));
        assertTrue(controller.robots().contains(
                "Sitemap: https://reasonstation.example/sitemap.xml"));
    }

    private TrackWithRating track() {
        return new TrackWithRating(
                42L, 7L, "A & B", "Signal & Noise", "Electronic",
                "signal.reason", 1024L, "s3://tracks/signal",
                Instant.parse("2026-11-03T10:15:30Z"), 8.5, 12);
    }
}

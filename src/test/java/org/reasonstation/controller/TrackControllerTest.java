package org.reasonstation.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.reasonstation.entity.User;
import org.reasonstation.entity.Track;
import org.reasonstation.repository.TrackDownloadRepository;
import org.reasonstation.repository.TrackRatingRepository;
import org.reasonstation.repository.TrackRepository;
import org.reasonstation.repository.TrackWithRating;
import org.reasonstation.repository.UserRepository;
import org.reasonstation.service.S3StorageService;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

@ExtendWith(MockitoExtension.class)
class TrackControllerTest {
    @Mock
    private TrackRepository tracks;
    @Mock
    private TrackDownloadRepository downloads;
    @Mock
    private TrackRatingRepository ratings;
    @Mock
    private UserRepository users;
    @Mock
    private S3StorageService storage;
    @Mock
    private Authentication authentication;

    private TrackController controller;

    @BeforeEach
    void setUp() {
        controller = new TrackController(tracks, downloads, ratings, users, storage);
    }

    @Test
    void rejectsTrackUploadWhenEmailIsNotVerified() throws IOException {
        User user = user(false);
        when(authentication.getName()).thenReturn("listener");
        when(users.findByAlias("listener")).thenReturn(Optional.of(user));

        var response = controller.uploadTrack(authentication, "Artist", "Track", "Rock", trackFile(), null, null, null);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertEquals(Map.of("error", "Verify your email address before uploading tracks."),
                response.getBody());
        verify(storage, never()).upload(any(), any(), any());
        verify(tracks, never()).save(any());
    }

    @Test
    void uploadsTrackWhenEmailIsVerified() throws IOException {
        User user = user(true);
        when(authentication.getName()).thenReturn("listener");
        when(users.findByAlias("listener")).thenReturn(Optional.of(user));
        when(storage.upload(any(), any(), any())).thenReturn("s3://tracks/upload");
        when(tracks.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var response = controller.uploadTrack(
                authentication, "Artist Name", "Track Name", "Rock", trackFile(), null, null, null);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        verify(storage).upload(any(), any(), any());
        ArgumentCaptor<Track> savedTrack = ArgumentCaptor.forClass(Track.class);
        verify(tracks).save(savedTrack.capture());
        assertEquals("Artist Name", savedTrack.getValue().getArtistAlias());
        assertEquals("Track Name", savedTrack.getValue().getTrackName());
        assertEquals((long) trackFile().getSize(), savedTrack.getValue().getFilesize());
        assertEquals(null, savedTrack.getValue().getAudioLink());
        assertEquals(null, savedTrack.getValue().getImageUri());
        assertEquals(null, savedTrack.getValue().getAudioUri());
        assertEquals(null, savedTrack.getValue().getProgramVersion());
    }

    @ParameterizedTest
    @CsvSource({
            "song.reason, 13.2.1, 13.2.1",
            "song.REASON, 13.2.1, 13.2.1",
            "song.rns, 4.0.0,",
            "song.rps, 4.0.0, 4.0.0",
            "song.RPS, 3.0.0, 3.0.0",
            "song.rps, 0.0.0, 0.0.0"
    })
    void extractsProgramVersionForReasonAndRpsUploads(
            String filename, String programVersion, String expectedVersion) throws IOException {
        when(authentication.getName()).thenReturn("listener");
        when(users.findByAlias("listener")).thenReturn(Optional.of(user(true)));
        when(storage.upload(any(), any(), any())).thenReturn("s3://tracks/upload");
        when(tracks.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        byte[] marker = "PROGRAMVERSIONBODY".getBytes(StandardCharsets.US_ASCII);
        byte[] version = programVersion.getBytes(StandardCharsets.US_ASCII);
        byte[] content = ByteBuffer.allocate(262892 + marker.length + 8 + version.length)
                .put(fileHeader(filename)).position(262892).put(marker).putInt(4 + version.length)
                .putInt(version.length).put(version).array();
        var file = new MockMultipartFile("file", filename, "application/octet-stream", content);

        var response = controller.uploadTrack(authentication, "Artist", "Track", "Rock", file, null, null, null);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        ArgumentCaptor<Track> savedTrack = ArgumentCaptor.forClass(Track.class);
        verify(tracks).save(savedTrack.capture());
        assertEquals(expectedVersion, savedTrack.getValue().getProgramVersion());
        verify(storage).upload(any(), org.mockito.ArgumentMatchers.eq(content), any());
    }

    @ParameterizedTest
    @CsvSource({
            "song.rps, 'no metadata'",
            "song.RPS, 'no metadata'",
            "song.rps, PROGRAMVERSIONBODY"
    })
    void defaultsRpsVersionToOneWhenNoValidProgramVersionIsFound(
            String filename, String content) throws IOException {
        when(authentication.getName()).thenReturn("listener");
        when(users.findByAlias("listener")).thenReturn(Optional.of(user(true)));
        when(storage.upload(any(), any(), any())).thenReturn("s3://tracks/upload");
        when(tracks.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var file = new MockMultipartFile("file", filename, "application/octet-stream",
                ByteBuffer.allocate(12 + content.length()).put(fileHeader(filename))
                        .put(content.getBytes(StandardCharsets.US_ASCII)).array());

        var response = controller.uploadTrack(authentication, "Artist", "Track", "Rock", file, null, null, null);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        ArgumentCaptor<Track> savedTrack = ArgumentCaptor.forClass(Track.class);
        verify(tracks).save(savedTrack.capture());
        assertEquals("1.0.0", savedTrack.getValue().getProgramVersion());
    }

    @Test
    void uploadsReasonFileWithMalformedVersionWithoutSettingProgramVersion() throws IOException {
        when(authentication.getName()).thenReturn("listener");
        when(users.findByAlias("listener")).thenReturn(Optional.of(user(true)));
        when(storage.upload(any(), any(), any())).thenReturn("s3://tracks/upload");
        when(tracks.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var file = new MockMultipartFile("file", "song.reason", "application/octet-stream",
                "X15_FILEPROGRAMVERSIONBODY".getBytes(StandardCharsets.US_ASCII));

        var response = controller.uploadTrack(authentication, "Artist", "Track", "Rock", file, null, null, null);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        ArgumentCaptor<Track> savedTrack = ArgumentCaptor.forClass(Track.class);
        verify(tracks).save(savedTrack.capture());
        assertEquals(null, savedTrack.getValue().getProgramVersion());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://soundcloud.com/artist/song",
            "https://on.soundcloud.com/AbCd123",
            "https://audiomack.com/artist/song/song?key=value&other=value"
    })
    void uploadsAndReturnsAudioLink(String audioLink) throws Exception {
        when(authentication.getName()).thenReturn("listener");
        when(users.findByAlias("listener")).thenReturn(Optional.of(user(true)));
        when(storage.upload(any(), any(), any())).thenReturn("s3://tracks/upload");
        when(tracks.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        standaloneSetup(controller).build()
                .perform(multipart("/api/tracks")
                        .file(trackFile())
                        .principal(authentication)
                        .param("artistAlias", "Artist")
                        .param("trackName", "Track")
                        .param("musicStyle", "Rock")
                        .param("audioLink", " " + audioLink + " "))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.audioLink").value(audioLink));

        ArgumentCaptor<Track> savedTrack = ArgumentCaptor.forClass(Track.class);
        verify(tracks).save(savedTrack.capture());
        assertEquals(audioLink, savedTrack.getValue().getAudioLink());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void allowsMissingAudioLink(String audioLink) throws IOException {
        when(authentication.getName()).thenReturn("listener");
        when(users.findByAlias("listener")).thenReturn(Optional.of(user(true)));
        when(storage.upload(any(), any(), any())).thenReturn("s3://tracks/upload");
        when(tracks.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var response = controller.uploadTrack(
                authentication, "Artist", "Track", "Rock", trackFile(), audioLink, null, null);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        ArgumentCaptor<Track> savedTrack = ArgumentCaptor.forClass(Track.class);
        verify(tracks).save(savedTrack.capture());
        assertEquals(null, savedTrack.getValue().getAudioLink());
    }

    @Test
    void allowsOmittingAudioLinkFromMultipartRequest() throws Exception {
        when(authentication.getName()).thenReturn("listener");
        when(users.findByAlias("listener")).thenReturn(Optional.of(user(true)));
        when(storage.upload(any(), any(), any())).thenReturn("s3://tracks/upload");
        when(tracks.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        standaloneSetup(controller).build()
                .perform(multipart("/api/tracks").file(trackFile()).principal(authentication)
                        .param("artistAlias", "Artist")
                        .param("trackName", "Track")
                        .param("musicStyle", "Rock"))
                .andExpect(status().isCreated());
    }

    @Test
    void includesAudioLinkAndProgramVersionInCurrentUserTracks() throws Exception {
        when(authentication.getName()).thenReturn("listener");
        when(users.findByAlias("listener")).thenReturn(Optional.of(user(true)));
        String audioLink = "https://soundcloud.com/artist/song";
        when(tracks.findByUseridWithRating(7L)).thenReturn(java.util.List.of(
                new TrackWithRating(42L, 7L, "Artist", "Track", "Rock", "song.reason", 3L,
                        "s3://tracks/song", java.time.Instant.now(), null, 0, audioLink, "13.2.1",
                        "s3://tracks/track-images/cover", "s3://tracks/track-audio/preview")));

        standaloneSetup(controller).build()
                .perform(get("/api/tracks/me").principal(authentication))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].audioLink").value(audioLink))
                .andExpect(jsonPath("$[0].programVersion").value("13.2.1"))
                .andExpect(jsonPath("$[0].imageUri").value("s3://tracks/track-images/cover"))
                .andExpect(jsonPath("$[0].audioUri").value("s3://tracks/track-audio/preview"));
    }

    @Test
    void redirectsTrackAudioToSignedDownloadWithoutCountingAReasonDownload() throws Exception {
        Track track = new Track();
        track.setTrackName("Test Track");
        track.setAudioUri("s3://tracks/track-audio/preview");
        when(tracks.findById(42L)).thenReturn(Optional.of(track));
        URI url = URI.create("https://storage.example/preview?signature=test");
        when(storage.createDownloadUrl(track.getAudioUri(), "Test Track.mp3")).thenReturn(url);

        var response = standaloneSetup(controller).build()
                .perform(get("/api/tracks/42/audio"))
                .andExpect(status().isFound())
                .andReturn().getResponse();

        assertEquals(url.toString(), response.getHeader("Location"));
        verifyNoInteractions(downloads);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void returnsNotFoundForTrackWithoutAudio(String audioUri) {
        Track track = new Track();
        track.setAudioUri(audioUri);
        when(tracks.findById(42L)).thenReturn(Optional.of(track));

        assertEquals(HttpStatus.NOT_FOUND, controller.trackAudio(42L).getStatusCode());
        verifyNoInteractions(storage, downloads);
    }

    @Test
    void returnsNotFoundForAudioOfMissingTrack() {
        when(tracks.findById(42L)).thenReturn(Optional.empty());

        assertEquals(HttpStatus.NOT_FOUND, controller.trackAudio(42L).getStatusCode());
        verifyNoInteractions(storage, downloads);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void rejectsInvalidTrackAudioId(long trackid) {
        assertEquals(HttpStatus.BAD_REQUEST, controller.trackAudio(trackid).getStatusCode());
        verifyNoInteractions(tracks, storage, downloads);
    }

    @Test
    void redirectsTrackImageToSignedStorageUrl() throws Exception {
        Track track = new Track();
        track.setImageUri("s3://tracks/track-images/cover");
        when(tracks.findById(42L)).thenReturn(Optional.of(track));
        URI url = URI.create("https://storage.example/cover?signature=test");
        when(storage.createImageUrl(track.getImageUri())).thenReturn(url);

        var response = standaloneSetup(controller).build()
                .perform(get("/api/tracks/42/image"))
                .andExpect(status().isFound())
                .andReturn().getResponse();

        assertEquals(url.toString(), response.getHeader("Location"));
        verifyNoInteractions(downloads);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void returnsNotFoundForTrackWithoutImage(String imageUri) {
        Track track = new Track();
        track.setImageUri(imageUri);
        when(tracks.findById(42L)).thenReturn(Optional.of(track));

        assertEquals(HttpStatus.NOT_FOUND, controller.trackImage(42L).getStatusCode());
        verifyNoInteractions(storage);
    }

    @Test
    void returnsNotFoundForImageOfMissingTrack() {
        when(tracks.findById(42L)).thenReturn(Optional.empty());

        assertEquals(HttpStatus.NOT_FOUND, controller.trackImage(42L).getStatusCode());
        verifyNoInteractions(storage);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void rejectsInvalidTrackImageId(long trackid) {
        assertEquals(HttpStatus.BAD_REQUEST, controller.trackImage(trackid).getStatusCode());
        verifyNoInteractions(tracks, storage);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://soundcloud.com/artist/song", "https://soundcloud.com",
            "https://soundcloud.com.evil.example/song", "https://soundcloud.com@evil.example/song",
            "http://on.soundcloud.com/AbCd123", "https://on.soundcloud.com",
            "https://on.soundcloud.com.evil.example/AbCd123", "https://on.soundcloud.com@evil.example/song",
            "https://on.audiomack.com/song", "https://other.soundcloud.com/song",
            "https://audiomack.com.evil.example/song", "https://example.com/song",
            "javascript:alert(1)", "https://soundcloud.com/artist song",
            "https://audiomack.com/artist\\song"
    })
    void rejectsUnsupportedAudioLinkBeforeSavingOrUploading(String audioLink) throws IOException {
        when(authentication.getName()).thenReturn("listener");
        when(users.findByAlias("listener")).thenReturn(Optional.of(user(true)));

        var response = controller.uploadTrack(
                authentication, "Artist", "Track", "Rock", trackFile(), audioLink, null, null);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verifyNoInteractions(storage, tracks);
    }

    @Test
    void redirectsTrackDownloadToObjectStorageAndRecordsIt() {
        Track track = new Track(
                7L, "Artist", "Track", "Rock", "song.reason", 3L,
                "s3://tracks/song");
        when(tracks.findById(4L)).thenReturn(Optional.of(track));
        URI downloadUrl = URI.create("https://storage.example/tracks/song?signature=test");
        when(storage.createDownloadUrl("s3://tracks/song", "song.reason")).thenReturn(downloadUrl);
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        request.setRemoteAddr("192.0.2.1");

        var response = controller.downloadTrack(4L, request);

        assertEquals(HttpStatus.FOUND, response.getStatusCode());
        assertEquals(downloadUrl, response.getHeaders().getLocation());
        assertEquals(null, response.getBody());
        verify(downloads).recordDownload(4L, "192.0.2.1");
    }

    @Test
    void rejectsNonPositiveTrackIdBeforeDownloadLookup() {
        var response = controller.downloadTrack(0L, new org.springframework.mock.web.MockHttpServletRequest());

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verifyNoInteractions(tracks, downloads, storage);
    }

    @Test
    void rejectsOverlongTrackName() throws IOException {
        when(authentication.getName()).thenReturn("listener");
        when(users.findByAlias("listener")).thenReturn(Optional.of(user(true)));

        var response = controller.uploadTrack(
                authentication, "Artist", "T".repeat(256), "Rock", trackFile(), null, null, null);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verify(storage, never()).upload(any(), any(), any());
        verify(tracks, never()).save(any());
    }

    @Test
    void rejectsUnsafeTrackFilename() throws IOException {
        when(authentication.getName()).thenReturn("listener");
        when(users.findByAlias("listener")).thenReturn(Optional.of(user(true)));
        var unsafeFile = new MockMultipartFile(
                "file", "../song.reason", "application/octet-stream", new byte[]{1});

        var response = controller.uploadTrack(
                authentication, "Artist", "Track", "Rock", unsafeFile, null, null, null);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verify(storage, never()).upload(any(), any(), any());
        verify(tracks, never()).save(any());
    }

    @Test
    void rejectsTrackLargerThanEightyMegabytes() throws IOException {
        when(authentication.getName()).thenReturn("listener");
        when(users.findByAlias("listener")).thenReturn(Optional.of(user(true)));
        MultipartFile oversizedFile = mock(MultipartFile.class);
        when(oversizedFile.isEmpty()).thenReturn(false);
        when(oversizedFile.getOriginalFilename()).thenReturn("song.reason");
        when(oversizedFile.getSize()).thenReturn(80L * 1024 * 1024 + 1);

        var response = controller.uploadTrack(
                authentication, "Artist", "Track", "Rock", oversizedFile, null, null, null);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verify(storage, never()).upload(any(), any(), any());
        verify(tracks, never()).save(any());
    }

    @ParameterizedTest
    @CsvSource({
            "song.reason, not a Reason file",
            "song.rns, X15_FILE",
            "song.reason, Propellerheads Reason Song File",
            "song.rps, FORM",
            "song.rps, FORM0000AIFFReason",
            "song.rps, FORM0000RPS",
            "song.reason, prefixX15_FILE",
            "song.rns, Propellerheads Reason Song Fil"
    })
    void rejectsInvalidFileSignaturesBeforeSavingOrUploading(String filename, String content)
            throws Exception {
        when(authentication.getName()).thenReturn("listener");
        when(users.findByAlias("listener")).thenReturn(Optional.of(user(true)));

        standaloneSetup(controller).build()
                .perform(multipart("/api/tracks")
                        .file(new MockMultipartFile("file", filename, "application/octet-stream",
                                content.getBytes(StandardCharsets.US_ASCII)))
                        .principal(authentication)
                        .param("artistAlias", "Artist")
                        .param("trackName", "Track")
                        .param("musicStyle", "Rock"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(
                        "File contents do not match the expected Reason format for its extension."));

        verifyNoInteractions(storage, tracks);
    }

    @Test
    void acceptsTrackRatingOfTen() throws Exception {
        when(tracks.findById(3L)).thenReturn(Optional.of(new Track()));
        MockMvc mockMvc = standaloneSetup(controller).build();

        mockMvc.perform(post("/api/tracks/3/rating")
                        .contentType("application/json")
                        .content("{\"rating\":10}")
                        .with(request -> {
                            request.setRemoteAddr("192.0.2.1");
                            return request;
                        }))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rating").value(10));

        verify(ratings).recordRating(3L, "192.0.2.1", (short) 10);
    }

    @Test
    void rejectsTrackRatingAboveTen() throws Exception {
        MockMvc mockMvc = standaloneSetup(controller).build();

        mockMvc.perform(post("/api/tracks/3/rating")
                        .contentType("application/json")
                        .content("{\"rating\":11}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error")
                        .value("Rating must be an integer between 1 and 10."));

        verifyNoInteractions(ratings);
    }

    private User user(boolean emailVerified) {
        User user = new User(7L, "listener", "listener@example.com", "password");
        user.setEmailVerified(emailVerified);
        return user;
    }

    private MockMultipartFile trackFile() {
        return new MockMultipartFile(
                "file", "song.reason", "application/octet-stream", fileHeader("song.reason"));
    }

    private byte[] fileHeader(String filename) {
        if (filename.toLowerCase(Locale.ROOT).endsWith(".rps")) {
            return ByteBuffer.allocate(12).put("FORM".getBytes(StandardCharsets.US_ASCII))
                    .putInt(4).put("RPS2".getBytes(StandardCharsets.US_ASCII)).array();
        }
        return (filename.toLowerCase(Locale.ROOT).endsWith(".rns")
                ? "Propellerheads Reason Song File" : "X15_FILE").getBytes(StandardCharsets.US_ASCII);
    }
}

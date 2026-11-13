package org.reasonstation.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.reasonstation.entity.Track;
import org.reasonstation.entity.User;
import org.reasonstation.repository.TrackDownloadRepository;
import org.reasonstation.repository.TrackRatingRepository;
import org.reasonstation.repository.TrackRepository;
import org.reasonstation.repository.UserRepository;
import org.reasonstation.service.S3StorageService;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.Authentication;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

@ExtendWith(MockitoExtension.class)
class TrackMediaUploadTest {
    @Mock private TrackRepository tracks;
    @Mock private UserRepository users;
    @Mock private S3StorageService storage;
    @Mock private Authentication authentication;

    private TrackController controller;

    @BeforeEach
    void setUp() {
        controller = new TrackController(tracks, mock(TrackDownloadRepository.class),
                mock(TrackRatingRepository.class), users, storage);
    }

    @ParameterizedTest
    @CsvSource({"true,true", "true,false", "false,true", "false,false"})
    void bindsOptionalMediaAndPersistsPrivateS3References(boolean withImage, boolean withAudio) throws Exception {
        verifiedUser();
        stubUploads();
        var request = multipart("/api/tracks")
                .file(reasonFile())
                .principal(authentication)
                .param("artistAlias", "Artist").param("trackName", "Track").param("musicStyle", "Rock");
        byte[] image = imageBytes();
        byte[] audio = audioBytes();
        if (withImage) {
            request.file(new MockMultipartFile("image", "cover.PNG", "application/octet-stream", image));
        }
        if (withAudio) {
            request.file(new MockMultipartFile("audio", "preview.MP3", "application/octet-stream", audio));
        }

        standaloneSetup(controller).build().perform(request).andExpect(status().isCreated())
                .andExpect(jsonPath("$.imageUri").value(withImage ? "s3://tracks/track-images/object" : null))
                .andExpect(jsonPath("$.audioUri").value(withAudio ? "s3://tracks/track-audio/object" : null))
                .andExpect(jsonPath("$.audioFilesize").value(withAudio ? (long) audio.length : null));

        var saved = ArgumentCaptor.forClass(Track.class);
        verify(tracks).save(saved.capture());
        assertEquals("s3://tracks/tracks/object", saved.getValue().getTrackUri());
        assertEquals(withImage ? "s3://tracks/track-images/object" : null, saved.getValue().getImageUri());
        assertEquals(withAudio ? "s3://tracks/track-audio/object" : null, saved.getValue().getAudioUri());
        assertEquals(withAudio ? (long) audio.length : null, saved.getValue().getAudioFilesize());
        if (withImage) {
            verify(storage).upload(startsWith("track-images/"), eq(image), eq("image/png"));
        }
        if (withAudio) {
            verify(storage).upload(startsWith("track-audio/"), eq(audio), eq("audio/mpeg"));
        }
        verify(storage, times(1 + (withImage ? 1 : 0) + (withAudio ? 1 : 0))).upload(any(), any(), any());
    }

    @ParameterizedTest
    @CsvSource({
            "image, cover.exe", "image, ../cover.png", "image, cover.svg",
            "image, cover.png", "image, cover.jpg",
            "audio, preview.wav", "audio, ../preview.mp3", "audio, preview.mp3"
    })
    void rejectsInvalidMediaBeforeAnyUploads(String part, String filename) throws Exception {
        verifiedUser();
        var invalid = new MockMultipartFile(part, filename, "application/octet-stream", new byte[]{1, 2, 3});

        standaloneSetup(controller).build().perform(multipart("/api/tracks")
                        .file(reasonFile()).file(invalid).principal(authentication)
                        .param("artistAlias", "Artist").param("trackName", "Track").param("musicStyle", "Rock"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(storage, tracks);
    }

    @Test
    void rejectsImageWhoseContentsDoNotMatchExtension() throws Exception {
        verifiedUser();
        var response = upload(new MockMultipartFile("image", "cover.jpg", "image/jpeg", imageBytes()), null);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verifyNoInteractions(storage, tracks);
    }

    @ParameterizedTest
    @ValueSource(strings = {"image", "audio"})
    void rejectsOversizedMediaWithoutReadingIt(String part) throws Exception {
        verifiedUser();
        MultipartFile oversized = mock(MultipartFile.class);
        when(oversized.getOriginalFilename()).thenReturn(part.equals("image") ? "cover.png" : "preview.mp3");
        when(oversized.getSize()).thenReturn((part.equals("image") ? 30L : 80L) * 1024 * 1024 + 1);

        var response = upload(part.equals("image") ? oversized : null, part.equals("audio") ? oversized : null);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verify(oversized, never()).getBytes();
        verifyNoInteractions(storage, tracks);
    }

    @ParameterizedTest
    @ValueSource(strings = {"image", "audio"})
    void acceptsMediaAtItsSizeLimit(String part) throws Exception {
        verifiedUser();
        stubUploads();
        MultipartFile media = mock(MultipartFile.class);
        when(media.getOriginalFilename()).thenReturn(part.equals("image") ? "cover.png" : "preview.mp3");
        when(media.getSize()).thenReturn((part.equals("image") ? 30L : 80L) * 1024 * 1024);
        when(media.getBytes()).thenReturn(part.equals("image") ? imageBytes() : audioBytes());

        var response = upload(part.equals("image") ? media : null, part.equals("audio") ? media : null);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        var saved = ArgumentCaptor.forClass(Track.class);
        verify(tracks).save(saved.capture());
        assertEquals(part.equals("audio") ? (long) audioBytes().length : null,
                saved.getValue().getAudioFilesize());
    }

    @ParameterizedTest
    @ValueSource(strings = {"image", "audio"})
    void rejectsSelectedEmptyFiles(String part) throws Exception {
        verifiedUser();
        var empty = new MockMultipartFile(part, part.equals("image") ? "cover.png" : "preview.mp3",
                "application/octet-stream", new byte[0]);
        var response = upload(part.equals("image") ? empty : null, part.equals("audio") ? empty : null);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verifyNoInteractions(storage, tracks);
    }

    @Test
    void ignoresUnselectedBrowserFileParts() throws Exception {
        verifiedUser();
        stubUploads();
        var response = upload(new MockMultipartFile("image", new byte[0]),
                new MockMultipartFile("audio", new byte[0]));
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        verify(storage).upload(any(), any(), any());
        var saved = ArgumentCaptor.forClass(Track.class);
        verify(tracks).save(saved.capture());
        assertNull(saved.getValue().getAudioFilesize());
    }

    @Test
    void cleansUpReasonAndImageWhenAudioUploadFails() throws Exception {
        verifiedUser();
        when(storage.upload(startsWith("tracks/"), any(), any())).thenReturn("s3://tracks/reason");
        when(storage.upload(startsWith("track-images/"), any(), any())).thenReturn("s3://tracks/image");
        var failure = new IllegalStateException("Audio upload failed");
        when(storage.upload(startsWith("track-audio/"), any(), any())).thenThrow(failure);

        assertSame(failure, assertThrows(IllegalStateException.class, () -> upload(imageFile(), audioFile())));

        verify(storage).delete("s3://tracks/reason");
        verify(storage).delete("s3://tracks/image");
        verifyNoInteractions(tracks);
    }

    @Test
    void cleansUpAllObjectsOnDatabaseFailureEvenIfOneCleanupFails() throws Exception {
        verifiedUser();
        when(storage.upload(any(), any(), any())).thenAnswer(invocation ->
                "s3://tracks/" + invocation.<String>getArgument(0).split("/")[0] + "/object");
        var failure = new IllegalStateException("Database unavailable");
        var cleanupFailure = new IllegalStateException("Delete failed");
        when(tracks.save(any())).thenThrow(failure);
        doThrow(cleanupFailure).when(storage).delete("s3://tracks/tracks/object");

        assertSame(failure, assertThrows(IllegalStateException.class, () -> upload(imageFile(), audioFile())));

        verify(storage).delete("s3://tracks/tracks/object");
        verify(storage).delete("s3://tracks/track-images/object");
        verify(storage).delete("s3://tracks/track-audio/object");
        assertArrayEquals(new Throwable[]{cleanupFailure}, failure.getSuppressed());
    }

    @Test
    void deletesAssociatedMediaWithOwnedTrack() {
        verifiedUser();
        Track track = new Track(7L, "Artist", "Track", "song.reason", "s3://tracks/reason");
        track.setImageUri("s3://tracks/image");
        track.setAudioUri("s3://tracks/audio");
        when(tracks.findById(42L)).thenReturn(Optional.of(track));

        assertEquals(HttpStatus.NO_CONTENT, controller.deleteTrack(authentication, 42L).getStatusCode());

        verify(storage).delete("s3://tracks/reason");
        verify(storage).delete("s3://tracks/image");
        verify(storage).delete("s3://tracks/audio");
        verify(tracks).deleteByTrackidAndUserid(42L, 7L);
    }

    private void verifiedUser() {
        var user = new User(7L, "listener", "listener@example.com", "password");
        user.setEmailVerified(true);
        when(authentication.getName()).thenReturn("listener");
        when(users.findByAlias("listener")).thenReturn(Optional.of(user));
    }

    private void stubUploads() {
        when(storage.upload(any(), any(), any())).thenAnswer(invocation ->
                "s3://tracks/" + invocation.<String>getArgument(0).split("/")[0] + "/object");
        when(tracks.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private org.springframework.http.ResponseEntity<?> upload(MultipartFile image, MultipartFile audio) throws Exception {
        return controller.uploadTrack(authentication, "Artist", "Track", "Rock", reasonFile(), null, image, audio);
    }

    private MockMultipartFile reasonFile() {
        return new MockMultipartFile("file", "song.reason", "application/octet-stream",
                "X15_FILE".getBytes(StandardCharsets.US_ASCII));
    }

    private MockMultipartFile imageFile() throws Exception {
        return new MockMultipartFile("image", "cover.png", "image/png", imageBytes());
    }

    private byte[] imageBytes() throws Exception {
        var output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", output);
        return output.toByteArray();
    }

    private MockMultipartFile audioFile() {
        return new MockMultipartFile("audio", "preview.mp3", "audio/mpeg", audioBytes());
    }

    private byte[] audioBytes() {
        byte[] frame = new byte[417];
        frame[0] = (byte) 0xff;
        frame[1] = (byte) 0xfb;
        frame[2] = (byte) 0x90;
        return frame;
    }
}

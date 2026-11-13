package org.reasonstation.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.reasonstation.entity.Patch;
import org.reasonstation.entity.Track;
import org.reasonstation.entity.User;
import org.reasonstation.repository.*;
import org.reasonstation.service.S3StorageService;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class OwnerEditingTest {
    private final TrackRepository tracks = mock(TrackRepository.class);
    private final PatchRepository patches = mock(PatchRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final S3StorageService storage = mock(S3StorageService.class);
    private final Authentication auth = new UsernamePasswordAuthenticationToken("owner", "password");
    private final TrackController trackController = new TrackController(tracks,
            mock(TrackDownloadRepository.class), mock(TrackRatingRepository.class), users, storage);
    private final PatchController patchController = new PatchController(patches,
            mock(PatchRatingRepository.class), users, storage);
    private Track track;
    private Patch patch;
    private User user;
    private final Instant uploaded = Instant.parse("2020-01-01T00:00:00Z");

    @BeforeEach
    void setUp() {
        user = new User(7L, "owner", "owner@example.com", "hash");
        user.setEmailVerified(true);
        when(users.findByAlias("owner")).thenReturn(Optional.of(user));
        track = new Track(7L, "Artist", "Track", "Rock", "old.reason", 500L, "s3://bucket/old-file");
        track.setTrackid(42L);
        track.setProgramVersion("13.2.1");
        track.setImageUri("s3://bucket/old-image");
        track.setAudioUri("s3://bucket/old-audio");
        track.setAudioFilesize(123456L);
        track.setAudioLink("https://soundcloud.com/artist/song");
        track.setDateUploaded(uploaded);
        patch = new Patch(7L, "Artist", "Patch", "Old description",
                "old.thor", ".thor", 500L, "s3://bucket/old-file");
        patch.setPatchid(42L);
        patch.setPatchImgUri("s3://bucket/old-image");
        patch.setDateUploaded(uploaded);
        when(tracks.findById(42L)).thenReturn(Optional.of(track));
        when(patches.findById(42L)).thenReturn(Optional.of(patch));
        when(tracks.save(any())).thenAnswer(call -> call.getArgument(0));
        when(patches.save(any())).thenAnswer(call -> call.getArgument(0));
        when(storage.upload(anyString(), any(), any())).thenAnswer(call ->
                "s3://bucket/new-" + call.<String>getArgument(0).split("/")[0]);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void bindsMetadataOnlyPutAndRetainsExistingFileMetadata(boolean isTrack) throws Exception {
        var request = multipart(HttpMethod.PUT, path(isTrack)).principal(auth)
                .param("artistAlias", " New Artist ").param(isTrack ? "trackName" : "patchName", " New Name ");
        if (isTrack) {
            request.param("musicStyle", "Ambient").param("audioLink", "https://audiomack.com/new");
        } else {
            request.param("description", " New description ");
        }
        standaloneSetup(isTrack ? trackController : patchController).build().perform(request)
                .andExpect(status().isOk())
                .andExpect(jsonPath(isTrack ? "$.trackid" : "$.patchid").value(42))
                .andExpect(jsonPath("$.artistAlias").value("New Artist"))
                .andExpect(jsonPath(isTrack ? "$.trackName" : "$.patchName").value("New Name"))
                .andExpect(jsonPath("$.filename").value(isTrack ? "old.reason" : "old.thor"))
                .andExpect(jsonPath("$.filesize").value(500));
        if (isTrack) {
            verify(tracks).save(argThat(saved -> saved.getProgramVersion().equals("13.2.1")
                    && saved.getAudioUri().equals(track.getAudioUri())
                    && saved.getAudioFilesize().equals(123456L)
                    && saved.getImageUri().equals(track.getImageUri())
                    && saved.getDateUploaded().equals(uploaded)
                    && saved.getAudioLink().equals("https://audiomack.com/new")));
        } else {
            verify(patches).save(argThat(saved -> saved.getExt().equals(".thor")
                    && saved.getDescription().equals("New description")
                    && saved.getPatchImgUri().equals(patch.getPatchImgUri())
                    && saved.getDateUploaded().equals(uploaded)));
        }
        verifyNoInteractions(storage);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void emptyNamedPartsRetainAllOldFilesAndOmittedOptionalProperties(boolean isTrack) throws Exception {
        var empty = new MockMultipartFile("file", "empty.reason", null, new byte[0]);
        assertEquals(HttpStatus.OK, edit(isTrack, empty, empty, empty).getStatusCode());
        verifyNoInteractions(storage);
        if (isTrack) {
            verify(tracks).save(argThat(saved -> saved.getTrackUri().equals(track.getTrackUri())
                    && saved.getProgramVersion().equals(track.getProgramVersion())
                    && saved.getAudioFilesize().equals(123456L)
                    && saved.getAudioLink().equals(track.getAudioLink())));
        } else {
            verify(patches).save(argThat(saved -> saved.getPatchUri().equals(patch.getPatchUri())
                    && saved.getDescription().equals(patch.getDescription())));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void replacingOnlyImageRetainsReasonMetadataAndOtherObjects(boolean isTrack) throws Exception {
        assertEquals(HttpStatus.OK, edit(isTrack, null, image(), null).getStatusCode());
        verify(storage).delete("s3://bucket/old-image");
        verify(storage, never()).delete("s3://bucket/old-file");
        verify(storage, never()).delete("s3://bucket/old-audio");
        if (isTrack) {
            verify(tracks).save(argThat(saved -> saved.getProgramVersion().equals("13.2.1")
                    && saved.getTrackUri().equals(track.getTrackUri())
                    && saved.getAudioUri().equals(track.getAudioUri())
                    && saved.getAudioFilesize().equals(123456L)
                    && saved.getImageUri().equals("s3://bucket/new-track-images")));
        } else {
            verify(patches).save(argThat(saved -> saved.getPatchUri().equals(patch.getPatchUri())
                    && saved.getFilename().equals("old.thor")
                    && saved.getPatchImgUri().equals("s3://bucket/new-patch-images")));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void invalidOrMissingIdCannotSaveOrUpload(boolean isTrack) throws Exception {
        var mvc = standaloneSetup(isTrack ? trackController : patchController).build();
        for (long id : new long[]{0L, 99L}) {
            var request = multipart(HttpMethod.PUT, (isTrack ? "/api/tracks/" : "/api/patches/") + id)
                    .principal(auth).param("artistAlias", "Artist")
                    .param(isTrack ? "trackName" : "patchName", "Name").param("musicStyle", "Rock");
            mvc.perform(request).andExpect(id == 0 ? status().isBadRequest() : status().isNotFound());
        }
        verifyNoInteractions(storage);
        verify(tracks, never()).save(any());
        verify(patches, never()).save(any());
    }

    @Test
    void replacingOnlyReasonFileRetainsAudioFilesize() throws Exception {
        assertEquals(HttpStatus.OK, edit(true, file(true), null, null).getStatusCode());

        verify(tracks).save(argThat(saved -> saved.getAudioFilesize().equals(123456L)
                && saved.getAudioUri().equals("s3://bucket/old-audio")
                && saved.getFilename().equals("new.rps")));
        verify(storage, never()).delete("s3://bucket/old-audio");
    }

    @Test
    void replacingOnlyAudioUpdatesFilesizeAndRetainsReasonFile() throws Exception {
        var audio = audio();
        standaloneSetup(trackController).build()
                .perform(multipart(HttpMethod.PUT, path(true)).file(audio).principal(auth)
                        .param("artistAlias", "Artist").param("trackName", "Track")
                        .param("musicStyle", "Rock"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.audioFilesize").value(audio.getSize()));

        verify(tracks).save(argThat(saved -> saved.getAudioFilesize().equals(audio.getSize())
                && saved.getAudioUri().equals("s3://bucket/new-track-audio")
                && saved.getTrackUri().equals("s3://bucket/old-file")));
        verify(storage).delete("s3://bucket/old-audio");
        verify(storage, never()).delete("s3://bucket/old-file");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void replacesFilesAndDeletesOldObjectsOnlyAfterSave(boolean isTrack) throws Exception {
        assertEquals(HttpStatus.OK, edit(isTrack, file(isTrack), image(), audio()).getStatusCode());
        var order = inOrder(storage, tracks, patches);
        order.verify(storage, atLeastOnce()).upload(any(), any(), any());
        if (isTrack) {
            order.verify(tracks).save(argThat(saved -> saved.getTrackid().equals(42L)
                    && saved.getFilename().equals("new.rps") && saved.getFilesize().equals(12L)
                    && saved.getProgramVersion().equals("1.0.0")
                    && saved.getAudioFilesize().equals(audio().getSize())
                    && saved.getDateUploaded().equals(uploaded)));
        } else {
            order.verify(patches).save(argThat(saved -> saved.getPatchid().equals(42L)
                    && saved.getFilename().equals("new.cmb") && saved.getExt().equals(".cmb")
                    && saved.getFilesize().equals(4L) && saved.getDateUploaded().equals(uploaded)));
        }
        order.verify(storage).delete("s3://bucket/old-file");
        order.verify(storage).delete("s3://bucket/old-image");
        if (isTrack) {
            order.verify(storage).delete("s3://bucket/old-audio");
        }
        verify(storage, never()).delete(startsWith("s3://bucket/new-"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void failedSaveCleansEveryNewObjectEvenWhenCleanupFailsAndRetainsOld(boolean isTrack) throws Exception {
        var failure = new IllegalStateException("save failed");
        if (isTrack) {
            when(tracks.save(any())).thenThrow(failure);
        } else {
            when(patches.save(any())).thenThrow(failure);
        }
        doThrow(new IllegalStateException("cleanup failed")).when(storage).delete(newFileUri(isTrack));
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> edit(isTrack, file(isTrack), image(), audio())));
        verify(storage).delete(newFileUri(isTrack));
        verify(storage).delete("s3://bucket/new-" + (isTrack ? "track-images" : "patch-images"));
        if (isTrack) {
            verify(storage).delete("s3://bucket/new-track-audio");
        }
        verify(storage, never()).delete(startsWith("s3://bucket/old-"));
        assertEquals(1, failure.getSuppressed().length);
        assertEquals("s3://bucket/old-file", isTrack ? track.getTrackUri() : patch.getPatchUri());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void failedSecondUploadCleansNewFileButNotOldObjects(boolean isTrack) throws Exception {
        when(storage.upload(startsWith(isTrack ? "track-images/" : "patch-images/"), any(), any()))
                .thenThrow(new IllegalStateException("upload failed"));
        assertThrows(IllegalStateException.class, () -> edit(isTrack, file(isTrack), image(), null));
        verify(storage).delete(newFileUri(isTrack));
        verify(storage, never()).delete(startsWith("s3://bucket/old-"));
        verify(tracks, never()).save(any());
        verify(patches, never()).save(any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void oldObjectCleanupFailureDoesNotDeletePersistedReplacements(boolean isTrack) throws Exception {
        doThrow(new IllegalStateException("old deletion failed")).when(storage).delete("s3://bucket/old-file");
        assertThrows(IllegalStateException.class, () -> edit(isTrack, file(isTrack), image(), audio()));
        verify(storage).delete("s3://bucket/old-image");
        if (isTrack) {
            verify(storage).delete("s3://bucket/old-audio");
        }
        verify(storage, never()).delete(startsWith("s3://bucket/new-"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void nonOwnerCannotMutateOrReadReplacementBytes(boolean isTrack) throws Exception {
        track.setUserid(99L);
        patch.setUserid(99L);
        MultipartFile unreadable = mock(MultipartFile.class);
        assertEquals(HttpStatus.NOT_FOUND, edit(isTrack, unreadable, unreadable, unreadable).getStatusCode());
        verifyNoInteractions(storage, unreadable);
        verify(tracks, never()).save(any());
        verify(patches, never()).save(any());
        assertEquals("Artist", isTrack ? track.getArtistAlias() : patch.getArtistAlias());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void unverifiedOwnerCannotEdit(boolean isTrack) throws Exception {
        user.setEmailVerified(false);
        assertEquals(HttpStatus.FORBIDDEN, edit(isTrack, file(isTrack), null, null).getStatusCode());
        verifyNoInteractions(storage);
        verify(tracks, never()).save(any());
        verify(patches, never()).save(any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void invalidReplacementContentsRejectedBeforeStorage(boolean isTrack) throws Exception {
        var invalidFile = new MockMultipartFile("file", isTrack ? "new.reason" : "new.thor",
                null, new byte[]{1});
        assertEquals(HttpStatus.BAD_REQUEST, edit(isTrack, invalidFile, null, null).getStatusCode());
        var invalidImage = new MockMultipartFile("image", "bad.png", null, new byte[]{1});
        assertEquals(HttpStatus.BAD_REQUEST, edit(isTrack, file(isTrack), invalidImage, null).getStatusCode());
        if (isTrack) {
            var invalidAudio = new MockMultipartFile("audio", "bad.mp3", null, new byte[]{1});
            assertEquals(HttpStatus.BAD_REQUEST, edit(true, null, null, invalidAudio).getStatusCode());
        }
        verifyNoInteractions(storage);
        verify(tracks, never()).save(any());
        verify(patches, never()).save(any());
    }

    private org.springframework.http.ResponseEntity<?> edit(boolean isTrack, MultipartFile file,
                                                            MultipartFile image, MultipartFile audio) throws Exception {
        return isTrack
                ? trackController.updateTrack(auth, 42L, "Artist", "New", "Rock", file, null, image, audio)
                : patchController.updatePatch(auth, 42L, "Artist", "New", null, file, image);
    }

    private String path(boolean isTrack) {
        return isTrack ? "/api/tracks/42" : "/api/patches/42";
    }

    private String newFileUri(boolean isTrack) {
        return "s3://bucket/new-" + (isTrack ? "tracks" : "patches");
    }

    private MockMultipartFile file(boolean isTrack) {
        return new MockMultipartFile("file", isTrack ? "new.rps" : "new.cmb",
                "application/octet-stream", (isTrack ? "FORM0000RPS2" : "FORM").getBytes(StandardCharsets.US_ASCII));
    }

    private MockMultipartFile image() throws Exception {
        var output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", output);
        return new MockMultipartFile("image", "new.png", "image/png", output.toByteArray());
    }

    private MockMultipartFile audio() {
        byte[] bytes = new byte[417];
        bytes[0] = (byte) 0xff;
        bytes[1] = (byte) 0xfb;
        bytes[2] = (byte) 0x90;
        return new MockMultipartFile("audio", "new.mp3", "audio/mpeg", bytes);
    }
}

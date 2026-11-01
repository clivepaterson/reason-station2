package org.reasonstation.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.reasonstation.entity.Patch;
import org.reasonstation.entity.User;
import org.reasonstation.repository.PatchRepository;
import org.reasonstation.repository.UserRepository;
import org.reasonstation.service.S3StorageService;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.Authentication;

import java.io.IOException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PatchControllerTest {
    @Mock
    private PatchRepository patches;
    @Mock
    private UserRepository users;
    @Mock
    private S3StorageService storage;
    @Mock
    private Authentication authentication;

    private PatchController controller;

    @BeforeEach
    void setUp() {
        controller = new PatchController(patches, users, storage);
        when(authentication.getName()).thenReturn("listener");
    }

    @Test
    void uploadsSupportedPatchWhenEmailIsVerified() throws IOException {
        when(users.findByAlias("listener")).thenReturn(Optional.of(user(true)));
        when(storage.upload(any(), any(), any())).thenReturn("s3://tracks/patches/upload");
        when(patches.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var response = controller.uploadPatch(
                authentication, "Artist", "Warm Bass", "Warm_Bass", patchFile("bass.thor"));

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        ArgumentCaptor<Patch> savedPatch = ArgumentCaptor.forClass(Patch.class);
        verify(patches).save(savedPatch.capture());
        assertEquals(3L, savedPatch.getValue().getFilesize());
        assertEquals("Warm Bass", savedPatch.getValue().getPatchName());
        assertEquals("Warm_Bass", savedPatch.getValue().getDescription());
        assertEquals(".thor", savedPatch.getValue().getExt());
    }

    @Test
    void rejectsUnsupportedPatchExtension() throws IOException {
        when(users.findByAlias("listener")).thenReturn(Optional.of(user(true)));

        var response = controller.uploadPatch(
                authentication, "Artist", "Bass", "Warm_Bass", patchFile("bass.exe"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verify(storage, never()).upload(any(), any(), any());
        verify(patches, never()).save(any());
    }

    @Test
    void rejectsPatchUploadWhenEmailIsNotVerified() throws IOException {
        when(users.findByAlias("listener")).thenReturn(Optional.of(user(false)));

        var response = controller.uploadPatch(
                authentication, "Artist", "Bass", "Warm_Bass", patchFile("bass.thor"));

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        verify(storage, never()).upload(any(), any(), any());
        verify(patches, never()).save(any());
    }

    @Test
    void acceptsSpacesInsertedForDescriptionLineBreaks() throws IOException {
        when(users.findByAlias("listener")).thenReturn(Optional.of(user(true)));
        when(storage.upload(any(), any(), any())).thenReturn("s3://tracks/patches/upload");
        when(patches.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var response = controller.uploadPatch(
                authentication, "Artist", "Bass", "Warm Bass", patchFile("bass.thor"));

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
    }

    @Test
    void rejectsDescriptionWithUnsupportedCharacters() throws IOException {
        when(users.findByAlias("listener")).thenReturn(Optional.of(user(true)));

        var response = controller.uploadPatch(
                authentication, "Artist", "Bass", "Warm@Bass", patchFile("bass.thor"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verify(storage, never()).upload(any(), any(), any());
        verify(patches, never()).save(any());
    }

    @Test
    void allowsAnEmptyDescription() throws IOException {
        when(users.findByAlias("listener")).thenReturn(Optional.of(user(true)));
        when(storage.upload(any(), any(), any())).thenReturn("s3://tracks/patches/upload");
        when(patches.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var response = controller.uploadPatch(
                authentication, "Artist", "Bass", "", patchFile("bass.thor"));

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        ArgumentCaptor<Patch> savedPatch = ArgumentCaptor.forClass(Patch.class);
        verify(patches).save(savedPatch.capture());
        assertEquals(null, savedPatch.getValue().getDescription());
    }

    private User user(boolean emailVerified) {
        User user = new User(7L, "listener", "listener@example.com", "password");
        user.setEmailVerified(emailVerified);
        return user;
    }

    private MockMultipartFile patchFile(String filename) {
        return new MockMultipartFile(
                "file", filename, "application/octet-stream", new byte[]{1, 2, 3});
    }
}

package org.reasonstation.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.util.Optional;
import java.util.Locale;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

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
        lenient().when(authentication.getName()).thenReturn("listener");
    }

    @Test
    void listsAuthenticatedUsersPatchesWithCardMetadata() throws Exception {
        when(users.findByAlias("listener")).thenReturn(Optional.of(user(true)));
        Patch patch = new Patch(7L, "Artist", "Bass", "Warm bass",
                "bass.thor", ".thor", 3L, "s3://tracks/patches/bass");
        patch.setPatchid(4L);
        patch.setPatchImgUri("s3://tracks/patch-images/bass");
        when(patches.findByUseridOrderByDateUploadedDesc(7L)).thenReturn(List.of(patch));
        MockMvc mvc = standaloneSetup(controller).build();

        mvc.perform(get("/api/patches/me").principal(authentication))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].patchid").value(4))
                .andExpect(jsonPath("$[0].artistAlias").value("Artist"))
                .andExpect(jsonPath("$[0].patchName").value("Bass"))
                .andExpect(jsonPath("$[0].description").value("Warm bass"))
                .andExpect(jsonPath("$[0].filename").value("bass.thor"))
                .andExpect(jsonPath("$[0].filesize").value(3))
                .andExpect(jsonPath("$[0].ext").value(".thor"))
                .andExpect(jsonPath("$[0].dateUploaded").exists())
                .andExpect(jsonPath("$[0].patchImgUri").value("s3://tracks/patch-images/bass"))
                .andExpect(jsonPath("$[0].userid").doesNotExist());

        verify(patches).findByUseridOrderByDateUploadedDesc(7L);
    }

    @Test
    void listsNoPatchesWhenUserHasNotUploadedAny() {
        when(users.findByAlias("listener")).thenReturn(Optional.of(user(false)));
        when(patches.findByUseridOrderByDateUploadedDesc(7L)).thenReturn(List.of());

        var response = controller.myPatches(authentication);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(List.of(), response.getBody());
    }

    @Test
    void returnsNotFoundWhenCurrentUserIsMissing() {
        when(users.findByAlias("listener")).thenReturn(Optional.empty());

        assertEquals(HttpStatus.NOT_FOUND, controller.myPatches(authentication).getStatusCode());
        verify(patches, never()).findByUseridOrderByDateUploadedDesc(any());
    }

    @Test
    void redirectsPatchDownloadToObjectStorage() {
        Patch patch = new Patch(
                7L, "Artist", "Bass", null, "bass.thor", ".thor", 3L, "s3://patches/bass");
        when(patches.findById(4L)).thenReturn(Optional.of(patch));
        URI downloadUrl = URI.create("https://storage.example/patches/bass?signature=test");
        when(storage.createDownloadUrl("s3://patches/bass", "bass.thor")).thenReturn(downloadUrl);

        var response = controller.downloadPatch(4L);

        assertEquals(HttpStatus.FOUND, response.getStatusCode());
        assertEquals(downloadUrl, response.getHeaders().getLocation());
        assertEquals(null, response.getBody());
    }

    @Test
    void returnsNotFoundWhenDownloadingUnknownPatch() {
        when(patches.findById(4L)).thenReturn(Optional.empty());

        var response = controller.downloadPatch(4L);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        verify(storage, never()).createDownloadUrl(any(), any());
    }

    @Test
    void rejectsNonPositivePatchId() {
        var response = controller.downloadPatch(0L);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verify(patches, never()).findById(any());
    }

    @Test
    void uploadsSupportedPatchWhenEmailIsVerified() throws IOException {
        when(users.findByAlias("listener")).thenReturn(Optional.of(user(true)));
        when(storage.upload(any(), any(), any())).thenReturn("s3://tracks/patches/upload");
        when(patches.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var response = controller.uploadPatch(
                authentication, " Artist Name ", " Warm Bass ", "Warm_Bass", patchFile("bass.thor"), null);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        ArgumentCaptor<Patch> savedPatch = ArgumentCaptor.forClass(Patch.class);
        verify(patches).save(savedPatch.capture());
        assertEquals("Artist Name", savedPatch.getValue().getArtistAlias());
        assertEquals(3L, savedPatch.getValue().getFilesize());
        assertEquals("Warm Bass", savedPatch.getValue().getPatchName());
        assertEquals("Warm_Bass", savedPatch.getValue().getDescription());
        assertEquals(".thor", savedPatch.getValue().getExt());
        assertEquals(null, savedPatch.getValue().getPatchImgUri());
    }

    @ParameterizedTest
    @ValueSource(strings = {"   ", "Artist@Name", "Artist\tName"})
    void rejectsInvalidArtistAlias(String artistAlias) throws IOException {
        when(users.findByAlias("listener")).thenReturn(Optional.of(user(true)));

        var response = controller.uploadPatch(
                authentication, artistAlias, "Warm Bass", "", patchFile("bass.thor"), null);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verify(storage, never()).upload(any(), any(), any());
        verify(patches, never()).save(any());
    }

    @Test
    void rejectsUnsupportedPatchExtension() throws IOException {
        when(users.findByAlias("listener")).thenReturn(Optional.of(user(true)));

        var response = controller.uploadPatch(
                authentication, "Artist", "Bass", "Warm_Bass", patchFile("bass.exe"), null);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verify(storage, never()).upload(any(), any(), any());
        verify(patches, never()).save(any());
    }

    @Test
    void rejectsOverlongPatchName() throws IOException {
        when(users.findByAlias("listener")).thenReturn(Optional.of(user(true)));

        var response = controller.uploadPatch(
                authentication, "Artist", "P".repeat(256), "", patchFile("bass.thor"), null);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verify(storage, never()).upload(any(), any(), any());
        verify(patches, never()).save(any());
    }

    @Test
    void rejectsUnsafePatchFilename() throws IOException {
        when(users.findByAlias("listener")).thenReturn(Optional.of(user(true)));

        var response = controller.uploadPatch(
                authentication, "Artist", "Bass", "", patchFile("../bass.thor"), null);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verify(storage, never()).upload(any(), any(), any());
        verify(patches, never()).save(any());
    }

    @Test
    void rejectsPatchUploadWhenEmailIsNotVerified() throws IOException {
        when(users.findByAlias("listener")).thenReturn(Optional.of(user(false)));

        var response = controller.uploadPatch(
                authentication, "Artist", "Bass", "Warm_Bass", patchFile("bass.thor"), null);

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
                authentication, "Artist", "Bass", "Warm Bass", patchFile("bass.thor"), null);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
    }

    @Test
    void rejectsDescriptionWithUnsupportedCharacters() throws IOException {
        when(users.findByAlias("listener")).thenReturn(Optional.of(user(true)));

        var response = controller.uploadPatch(
                authentication, "Artist", "Bass", "Warm@Bass", patchFile("bass.thor"), null);

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
                authentication, "Artist", "Bass", "", patchFile("bass.thor"), null);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        ArgumentCaptor<Patch> savedPatch = ArgumentCaptor.forClass(Patch.class);
        verify(patches).save(savedPatch.capture());
        assertEquals(null, savedPatch.getValue().getDescription());
    }

    @ParameterizedTest
    @ValueSource(strings = {"jpg", "jpeg", "png", "bmp", "gif", "PNG"})
    void uploadsOptionalImageWithVerifiedContentType(String extension) throws IOException {
        when(users.findByAlias("listener")).thenReturn(Optional.of(user(true)));
        when(storage.upload(any(), any(), any()))
                .thenReturn("s3://tracks/patches/upload", "s3://tracks/patch-images/upload");
        when(patches.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        String format = extension.equals("jpg") ? "jpeg" : extension.toLowerCase(Locale.ROOT);
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), format, bytes);
        var image = new MockMultipartFile("image", "image." + extension, "text/html", bytes.toByteArray());

        var response = controller.uploadPatch(
                authentication, "Artist", "Bass", "", patchFile("bass.thor"), image);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        ArgumentCaptor<Patch> savedPatch = ArgumentCaptor.forClass(Patch.class);
        verify(patches).save(savedPatch.capture());
        assertEquals("s3://tracks/patch-images/upload", savedPatch.getValue().getPatchImgUri());
        verify(storage).upload(startsWith("patch-images/"), aryEq(bytes.toByteArray()), eq("image/" + format));
    }

    @ParameterizedTest
    @ValueSource(strings = {"image.svg", "image.exe", "../image.png", "image.png"})
    void rejectsInvalidImagesBeforeUploadingAnything(String filename) throws IOException {
        when(users.findByAlias("listener")).thenReturn(Optional.of(user(true)));
        var image = new MockMultipartFile("image", filename, "image/png", new byte[]{1, 2, 3});

        var response = controller.uploadPatch(
                authentication, "Artist", "Bass", "", patchFile("bass.thor"), image);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verify(storage, never()).upload(any(), any(), any());
        verify(patches, never()).save(any());
    }

    @Test
    void rejectsImageWhoseContentDoesNotMatchExtension() throws IOException {
        when(users.findByAlias("listener")).thenReturn(Optional.of(user(true)));
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", bytes);
        var image = new MockMultipartFile("image", "image.jpg", "image/jpeg", bytes.toByteArray());

        var response = controller.uploadPatch(
                authentication, "Artist", "Bass", "", patchFile("bass.thor"), image);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verify(storage, never()).upload(any(), any(), any());
    }

    @Test
    void redirectsImageToSignedStorageUrl() {
        Patch patch = new Patch();
        patch.setPatchImgUri("s3://tracks/patch-images/image");
        when(patches.findById(4L)).thenReturn(Optional.of(patch));
        URI url = URI.create("https://storage.example/image?signature=test");
        when(storage.createImageUrl(patch.getPatchImgUri())).thenReturn(url);

        var response = controller.patchImage(4L);

        assertEquals(HttpStatus.FOUND, response.getStatusCode());
        assertEquals(url, response.getHeaders().getLocation());
    }

    @Test
    void returnsNotFoundForPatchWithoutImage() {
        when(patches.findById(4L)).thenReturn(Optional.of(new Patch()));

        assertEquals(HttpStatus.NOT_FOUND, controller.patchImage(4L).getStatusCode());
        verify(storage, never()).createImageUrl(any());
    }

    @Test
    void returnsNotFoundForUnknownPatchImage() {
        when(patches.findById(4L)).thenReturn(Optional.empty());

        assertEquals(HttpStatus.NOT_FOUND, controller.patchImage(4L).getStatusCode());
    }

    @Test
    void rejectsNonPositiveImageId() {
        assertEquals(HttpStatus.BAD_REQUEST, controller.patchImage(0L).getStatusCode());
        verify(patches, never()).findById(any());
    }

    @Test
    void rejectsOversizedImageBeforeReadingOrUploadingIt() throws IOException {
        when(users.findByAlias("listener")).thenReturn(Optional.of(user(true)));
        MultipartFile image = mock(MultipartFile.class);
        when(image.getOriginalFilename()).thenReturn("image.png");
        when(image.getSize()).thenReturn(30L * 1024 * 1024 + 1);

        var response = controller.uploadPatch(
                authentication, "Artist", "Bass", "", patchFile("bass.thor"), image);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verify(image, never()).getBytes();
        verify(storage, never()).upload(any(), any(), any());
    }

    @Test
    void acceptsMultipartUploadWithoutOptionalImage() throws Exception {
        when(users.findByAlias("listener")).thenReturn(Optional.of(user(true)));
        when(storage.upload(any(), any(), any())).thenReturn("s3://tracks/patches/upload");
        when(patches.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        MockMvc mvc = standaloneSetup(controller).build();

        mvc.perform(multipart("/api/patches")
                        .file(patchFile("bass.thor"))
                        .param("artistAlias", "Artist")
                        .param("patchName", "Bass")
                        .principal(authentication))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.patchName").value("Bass"))
                .andExpect(jsonPath("$.patchImgUri").isEmpty());
    }

    @Test
    void acceptsMultipartUploadWithOptionalImageAndReturnsItsUri() throws Exception {
        when(users.findByAlias("listener")).thenReturn(Optional.of(user(true)));
        when(storage.upload(any(), any(), any()))
                .thenReturn("s3://tracks/patches/upload", "s3://tracks/patch-images/upload");
        when(patches.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", bytes);
        MockMvc mvc = standaloneSetup(controller).build();

        mvc.perform(multipart("/api/patches")
                        .file(patchFile("bass.thor"))
                        .file(new MockMultipartFile("image", "image.png", "image/png", bytes.toByteArray()))
                        .param("artistAlias", "Artist")
                        .param("patchName", "Bass")
                        .principal(authentication))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.patchImgUri").value("s3://tracks/patch-images/upload"));
    }

    @Test
    void removesUploadedFilesWhenSavingPatchFails() throws IOException {
        when(users.findByAlias("listener")).thenReturn(Optional.of(user(true)));
        when(storage.upload(any(), any(), any()))
                .thenReturn("s3://tracks/patches/upload", "s3://tracks/patch-images/upload");
        var failure = new IllegalStateException("Database unavailable");
        when(patches.save(any())).thenThrow(failure);
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", bytes);
        var image = new MockMultipartFile("image", "image.png", "image/png", bytes.toByteArray());

        assertEquals(failure, assertThrows(IllegalStateException.class,
                () -> controller.uploadPatch(
                        authentication, "Artist", "Bass", "", patchFile("bass.thor"), image)));

        verify(storage).delete("s3://tracks/patches/upload");
        verify(storage).delete("s3://tracks/patch-images/upload");
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

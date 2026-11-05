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
import org.reasonstation.entity.Patch;
import org.reasonstation.entity.User;
import org.reasonstation.repository.PatchRepository;
import org.reasonstation.repository.PatchWithRating;
import org.reasonstation.repository.PatchRatingRepository;
import org.reasonstation.repository.UserRepository;
import org.reasonstation.service.S3StorageService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

@ExtendWith(MockitoExtension.class)
class PatchControllerTest {
    @Mock
    private PatchRepository patches;
    @Mock
    private PatchRatingRepository ratings;
    @Mock
    private UserRepository users;
    @Mock
    private S3StorageService storage;
    @Mock
    private Authentication authentication;

    private PatchController controller;

    @BeforeEach
    void setUp() {
        controller = new PatchController(patches, ratings, users, storage);
        lenient().when(authentication.getName()).thenReturn("listener");
    }

    @Test
    void listsAuthenticatedUsersPatchesWithCardMetadata() throws Exception {
        when(users.findByAlias("listener")).thenReturn(Optional.of(user(true)));
        var patch = new PatchWithRating(4L, "Artist", "Bass", "Warm bass",
                "bass.thor", ".thor", 3L, "s3://tracks/patch-images/bass",
                java.time.Instant.now(), 8.5);
        when(patches.findWithRatingsByUserid(7L)).thenReturn(List.of(patch));
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
                .andExpect(jsonPath("$[0].averagePatchRating").value(8.5))
                .andExpect(jsonPath("$[0].dateUploaded").exists())
                .andExpect(jsonPath("$[0].patchImgUri").value("s3://tracks/patch-images/bass"))
                .andExpect(jsonPath("$[0].userid").doesNotExist());

        verify(patches).findWithRatingsByUserid(7L);
    }

    @Test
    void listsNoPatchesWhenUserHasNotUploadedAny() {
        when(users.findByAlias("listener")).thenReturn(Optional.of(user(false)));
        when(patches.findWithRatingsByUserid(7L)).thenReturn(List.of());

        var response = controller.myPatches(authentication);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(List.of(), response.getBody());
    }

    @Test
    void returnsNotFoundWhenCurrentUserIsMissing() {
        when(users.findByAlias("listener")).thenReturn(Optional.empty());

        assertEquals(HttpStatus.NOT_FOUND, controller.myPatches(authentication).getStatusCode());
        verify(patches, never()).findWithRatingsByUserid(any());
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

    @ParameterizedTest
    @ValueSource(ints = {1, 10})
    void savesPatchRatingWithoutSigningIn(int rating) throws Exception {
        when(patches.findById(4L)).thenReturn(Optional.of(new Patch()));
        when(ratings.recordRating(4L, "192.0.2.1", (short) rating)).thenReturn(1);
        MockMvc mvc = standaloneSetup(controller).build();

        mvc.perform(post("/api/patches/4/rating")
                        .with(request -> {
                            request.setRemoteAddr("192.0.2.1");
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":" + rating + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.patchid").value(4))
                .andExpect(jsonPath("$.rating").value(rating));

        verify(ratings).recordRating(4L, "192.0.2.1", (short) rating);
        verifyNoInteractions(users, storage);
    }

    @Test
    void reportsDuplicatePatchRatingWithoutClaimingItWasSaved() throws Exception {
        when(patches.findById(4L)).thenReturn(Optional.of(new Patch()));
        when(ratings.recordRating(4L, "192.0.2.1", (short) 8)).thenReturn(0);
        MockMvc mvc = standaloneSetup(controller).build();

        mvc.perform(post("/api/patches/4/rating")
                        .with(request -> {
                            request.setRemoteAddr("192.0.2.1");
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":8}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("This IP address has already rated this patch."));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "11", "-1", "1.5", "null"})
    void rejectsInvalidPatchRating(String rating) throws Exception {
        MockMvc mvc = standaloneSetup(controller).build();

        mvc.perform(post("/api/patches/4/rating")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":" + rating + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Rating must be an integer between 1 and 10."));

        verifyNoInteractions(patches, ratings);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null"})
    void rejectsMissingPatchRating(String body) throws Exception {
        MockMvc mvc = standaloneSetup(controller).build();

        mvc.perform(post("/api/patches/4/rating")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(patches, ratings);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void rejectsRatingForInvalidPatchId(int patchid) throws Exception {
        MockMvc mvc = standaloneSetup(controller).build();

        mvc.perform(post("/api/patches/" + patchid + "/rating")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":8}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Patch ID must be a positive integer."));

        verifyNoInteractions(patches, ratings);
    }

    @Test
    void rejectsRatingForUnknownPatch() throws Exception {
        when(patches.findById(4L)).thenReturn(Optional.empty());
        MockMvc mvc = standaloneSetup(controller).build();

        mvc.perform(post("/api/patches/4/rating")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":8}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Patch not found."));

        verifyNoInteractions(ratings);
    }

    @Test
    void rejectsPatchRatingWithoutIpAddress() throws Exception {
        when(patches.findById(4L)).thenReturn(Optional.of(new Patch()));
        MockMvc mvc = standaloneSetup(controller).build();

        mvc.perform(post("/api/patches/4/rating")
                        .with(request -> {
                            request.setRemoteAddr("");
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":8}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("An IP address is required."));

        verifyNoInteractions(ratings);
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
        assertEquals(4L, savedPatch.getValue().getFilesize());
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

    @ParameterizedTest
    @ValueSource(strings = {
            ".sxt", ".xwv", ".smp", ".drex", ".drum", ".rv7", ".sm4", ".echo",
            ".pulver", ".gator", ".cmb", ".thor", ".kong", ".drp", ".zyp",
            ".sf2", ".rx2", ".rcy", ".rex", ".THOR", ".patch", ".repatch", ".PATCH", ".REPATCH"
    })
    void uploadsMatchingPatchFormats(String extension) throws IOException {
        when(users.findByAlias("listener")).thenReturn(Optional.of(user(true)));
        when(storage.upload(any(), any(), any())).thenReturn("s3://tracks/patches/upload");
        when(patches.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        String content = ".patch".equalsIgnoreCase(extension) || ".repatch".equalsIgnoreCase(extension)
                ? "<?xml version=\"1.0\"?><JukeboxPatch deviceProductID=\"se.propellerheads.Mimic\"><Properties/></JukeboxPatch>"
                : "FORM";
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        var file = new MockMultipartFile("file", "bass" + extension, "application/octet-stream", bytes);

        var response = controller.uploadPatch(authentication, "Artist", "Bass", "", file, null);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        verify(storage).upload(startsWith("patches/"), aryEq(bytes), eq("application/octet-stream"));
        verify(patches).save(any());
    }

    @ParameterizedTest
    @CsvSource({
            ".thor, FOR",
            ".cmb, prefixFORM",
            ".sxt, form",
            ".patch, FORM",
            ".repatch, FORM",
            ".patch, <JukeboxPatch deviceProductID=\"se.propellerheads.Mimic\">",
            ".repatch, <JukeboxPatch deviceProductID=\"another.vendor\"/>"
    })
    void rejectsInvalidPatchFormatsBeforeUploadingAnything(String extension, String content) throws Exception {
        when(users.findByAlias("listener")).thenReturn(Optional.of(user(true)));
        MultipartFile image = mock(MultipartFile.class);
        var file = new MockMultipartFile("file", "bass" + extension, "application/octet-stream",
                content.getBytes(StandardCharsets.UTF_8));

        var response = controller.uploadPatch(authentication, "Artist", "Bass", "", file, image);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(java.util.Map.of("error", extension.equals(".patch") || extension.equals(".repatch")
                ? "The patch must be valid XML with a JukeboxPatch root element and a deviceProductID containing propellerheads."
                : "The patch file must start with FORM."), response.getBody());
        verifyNoInteractions(storage, patches, image);
    }

    @Test
    void returnsFormatErrorForInvalidMultipartUpload() throws Exception {
        when(users.findByAlias("listener")).thenReturn(Optional.of(user(true)));

        standaloneSetup(controller).build()
                .perform(multipart("/api/patches")
                        .file(new MockMultipartFile("file", "bass.thor", "application/octet-stream",
                                new byte[]{1, 2, 3}))
                        .param("artistAlias", "Artist")
                        .param("patchName", "Bass")
                        .principal(authentication))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("The patch file must start with FORM."));

        verifyNoInteractions(storage, patches);
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
                "file", filename, "application/octet-stream", "FORM".getBytes(StandardCharsets.US_ASCII));
    }
}

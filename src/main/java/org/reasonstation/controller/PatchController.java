package org.reasonstation.controller;

import org.reasonstation.entity.Patch;
import org.reasonstation.entity.User;
import org.reasonstation.repository.PatchRepository;
import org.reasonstation.repository.PatchRatingRepository;
import org.reasonstation.repository.UserRepository;
import org.reasonstation.service.S3StorageService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import jakarta.servlet.http.HttpServletRequest;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.imageio.IIOException;
import javax.imageio.ImageIO;

@RestController
@RequestMapping("/api/patches")
public class PatchController {
    private static final int MAX_ARTIST_ALIAS_LENGTH = 255;
    private static final int MAX_PATCH_NAME_LENGTH = 255;
    private static final int MAX_DESCRIPTION_LENGTH = 200;
    private static final int MAX_FILENAME_LENGTH = 255;
    private static final long MAX_FILE_SIZE = 30L * 1024 * 1024;
    private static final Map<String, String> IMAGE_TYPES = Map.of(
            ".jpg", "jpeg", ".jpeg", "jpeg", ".png", "png", ".bmp", "bmp", ".gif", "gif");
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            ".sxt", ".xwv", ".smp", ".drex", ".drum", ".rv7", ".sm4", ".echo",
            ".pulver", ".gator", ".cmb", ".thor", ".kong", ".drp", ".zyp", ".patch",
            ".repatch", ".sf2", ".rx2", ".rcy", ".rex");
    private static final Pattern PATCH_TEXT_PATTERN =
            Pattern.compile("[A-Za-z0-9()\\[\\]_.'+\\- ]+");
    private static final Pattern DESCRIPTION_PATTERN =
            Pattern.compile("[A-Za-z0-9()\\[\\]_.'+\\- ]+");

    private final PatchRepository patches;
    private final PatchRatingRepository ratings;
    private final UserRepository users;
    private final S3StorageService storage;

    public PatchController(PatchRepository patches, PatchRatingRepository ratings,
                           UserRepository users, S3StorageService storage) {
        this.patches = patches;
        this.ratings = ratings;
        this.users = users;
        this.storage = storage;
    }

    @GetMapping("/me")
    public ResponseEntity<?> myPatches(Authentication authentication) {
        return users.findByAlias(authentication.getName())
                .map(user -> ResponseEntity.ok(
                        patches.findWithRatingsByUserid(user.getUserid())))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/{patchid}/download")
    public ResponseEntity<?> downloadPatch(@PathVariable Long patchid) {
        if (patchid == null || patchid < 1) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Patch ID must be a positive integer."));
        }
        Optional<Patch> patch = patches.findById(patchid);
        if (patch.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Patch not found."));
        }

        var downloadUrl = storage.createDownloadUrl(
                patch.get().getPatchUri(), patch.get().getFilename());
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(downloadUrl)
                .build();
    }

    @GetMapping("/{patchid}/image")
    public ResponseEntity<?> patchImage(@PathVariable Long patchid) {
        if (patchid == null || patchid < 1) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Patch ID must be a positive integer."));
        }
        Optional<Patch> patch = patches.findById(patchid);
        if (patch.isEmpty() || patch.get().getPatchImgUri() == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Patch image not found."));
        }
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(storage.createImageUrl(patch.get().getPatchImgUri()))
                .build();
    }

    @PostMapping(value = "/{patchid}/rating", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> ratePatch(@PathVariable Long patchid, @RequestBody RatingRequest request,
                                       HttpServletRequest httpRequest) {
        if (patchid == null || patchid < 1) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Patch ID must be a positive integer."));
        }
        if (request == null || request.rating() == null
                || request.rating().stripTrailingZeros().scale() > 0
                || request.rating().compareTo(BigDecimal.ONE) < 0
                || request.rating().compareTo(BigDecimal.TEN) > 0) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Rating must be an integer between 1 and 10."));
        }
        if (patches.findById(patchid).isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Patch not found."));
        }
        String ipAddress = httpRequest.getRemoteAddr();
        if (ipAddress == null || ipAddress.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "An IP address is required."));
        }
        if (ratings.recordRating(patchid, ipAddress, request.rating().shortValue()) == 0) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "This IP address has already rated this patch."));
        }
        return ResponseEntity.ok(Map.of("patchid", patchid, "rating", request.rating()));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadPatch(
            Authentication authentication,
            @RequestParam("artistAlias") String artistAlias,
            @RequestParam("patchName") String patchName,
            @RequestParam(value = "description", defaultValue = "") String description,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "image", required = false) MultipartFile image) throws IOException {
        Optional<User> currentUser = users.findByAlias(authentication.getName());
        if (currentUser.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "The authenticated user was not found."));
        }
        if (!currentUser.get().isEmailVerified()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Verify your email address before uploading patches."));
        }

        String trimmedArtistAlias = artistAlias == null ? "" : artistAlias.trim();
        String trimmedPatchName = patchName == null ? "" : patchName.trim();
        String trimmedDescription = description == null ? "" : description.trim();
        if (trimmedArtistAlias.isEmpty() || trimmedPatchName.isEmpty()
                || file == null || file.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Artist alias, patch name, and a file are required."));
        }
        if (trimmedArtistAlias.length() > MAX_ARTIST_ALIAS_LENGTH
                || trimmedPatchName.length() > MAX_PATCH_NAME_LENGTH) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Artist alias and patch name must be 255 characters or fewer."));
        }
        if (!PATCH_TEXT_PATTERN.matcher(trimmedArtistAlias).matches()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Artist alias may contain only letters, numbers, spaces, and ()[]_.'+-."));
        }
        if (!PATCH_TEXT_PATTERN.matcher(trimmedPatchName).matches()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Patch name may contain only letters, numbers, spaces, and ()[]_.'+-."));
        }
        if (trimmedDescription.length() > MAX_DESCRIPTION_LENGTH
                || (!trimmedDescription.isEmpty()
                && !DESCRIPTION_PATTERN.matcher(trimmedDescription).matches())) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Description must be 200 characters or fewer and may contain only letters, numbers, and ()[]_.'+-."));
        }
        String filename = file.getOriginalFilename();
        if (filename == null || filename.isBlank() || filename.length() > MAX_FILENAME_LENGTH
                || filename.contains("/") || filename.contains("\\") || file.getSize() > MAX_FILE_SIZE) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "File name is invalid or the file exceeds 30 MB."));
        }
        String extension = getExtension(filename);
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "The selected file type is not a supported patch format."));
        }

        byte[] imageBytes = null;
        String imageFormat = null;
        if (image != null && !image.isEmpty()) {
            String imageFilename = image.getOriginalFilename();
            imageFormat = IMAGE_TYPES.get(getExtension(imageFilename));
            if (imageFilename == null || imageFilename.length() > MAX_FILENAME_LENGTH
                    || imageFilename.contains("/") || imageFilename.contains("\\")
                    || image.getSize() > MAX_FILE_SIZE || imageFormat == null) {
                return ResponseEntity.badRequest().body(Map.of("error",
                        "Choose a .jpg, .jpeg, .png, .bmp, or .gif image no larger than 30 MB."));
            }
            imageBytes = image.getBytes();
            try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(imageBytes))) {
                var readers = ImageIO.getImageReaders(input);
                if (!readers.hasNext()) {
                    return ResponseEntity.badRequest().body(Map.of("error", "The image file is invalid."));
                }
                var reader = readers.next();
                try {
                    if (!imageFormat.equalsIgnoreCase(reader.getFormatName())) {
                        return ResponseEntity.badRequest().body(Map.of("error",
                                "The image content must match its file extension."));
                    }
                    reader.setInput(input);
                    reader.getWidth(0);
                    reader.getHeight(0);
                } finally {
                    reader.dispose();
                }
            } catch (IIOException exception) {
                return ResponseEntity.badRequest().body(Map.of("error", "The image file is invalid."));
            }
        }

        String patchUri = storage.upload(
                "patches/" + UUID.randomUUID(), file.getBytes(), file.getContentType());
        Patch patch = new Patch(currentUser.get().getUserid(), trimmedArtistAlias, trimmedPatchName,
                trimmedDescription.isEmpty() ? null : trimmedDescription,
                filename, extension, file.getSize(), patchUri);
        try {
            if (imageBytes != null) {
                patch.setPatchImgUri(storage.upload(
                        "patch-images/" + UUID.randomUUID(), imageBytes, "image/" + imageFormat));
            }
            return ResponseEntity.status(HttpStatus.CREATED).body(PatchResponse.from(patches.save(patch)));
        } catch (RuntimeException exception) {
            try {
                storage.delete(patchUri);
                if (patch.getPatchImgUri() != null) {
                    storage.delete(patch.getPatchImgUri());
                }
            } catch (RuntimeException cleanupException) {
                exception.addSuppressed(cleanupException);
            }
            throw exception;
        }
    }

    private String getExtension(String filename) {
        if (filename == null) {
            return "";
        }

        int extensionStart = filename.lastIndexOf('.');
        return extensionStart >= 0
                ? filename.substring(extensionStart).toLowerCase(Locale.ROOT)
                : "";
    }

    private record RatingRequest(BigDecimal rating) {
    }

    private record PatchResponse(Long patchid, String artistAlias, String patchName, String description,
                                 String filename, String ext, Long filesize,
                                 String patchImgUri,
                                 java.time.Instant dateUploaded) {
        private static PatchResponse from(Patch patch) {
            return new PatchResponse(patch.getPatchid(), patch.getArtistAlias(), patch.getPatchName(),
                    patch.getDescription(), patch.getFilename(), patch.getExt(), patch.getFilesize(),
                    patch.getPatchImgUri(),
                    patch.getDateUploaded());
        }
    }
}

package org.reasonstation.controller;

import org.reasonstation.entity.Patch;
import org.reasonstation.entity.User;
import org.reasonstation.repository.PatchRepository;
import org.reasonstation.repository.UserRepository;
import org.reasonstation.service.S3StorageService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/patches")
public class PatchController {
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            ".sxt", ".xwv", ".smp", ".drex", ".drum", ".rv7", ".sm4", ".echo",
            ".pulver", ".gator", ".cmb", ".thor", ".kong", ".drp", ".zyp", ".patch",
            ".repatch", ".sf2", ".rx2", ".rcy", ".rex");
    private static final Pattern ARTIST_ALIAS_PATTERN =
            Pattern.compile("[A-Za-z0-9()\\[\\]_.'+\\-]+");
    private static final Pattern PATCH_TEXT_PATTERN =
            Pattern.compile("[A-Za-z0-9()\\[\\]_.'+\\- ]+");
    private static final Pattern DESCRIPTION_PATTERN =
            Pattern.compile("[A-Za-z0-9()\\[\\]_.'+\\- ]+");

    private final PatchRepository patches;
    private final UserRepository users;
    private final S3StorageService storage;

    public PatchController(PatchRepository patches, UserRepository users, S3StorageService storage) {
        this.patches = patches;
        this.users = users;
        this.storage = storage;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadPatch(
            Authentication authentication,
            @RequestParam("artistAlias") String artistAlias,
            @RequestParam("patchName") String patchName,
            @RequestParam(value = "description", defaultValue = "") String description,
            @RequestParam("file") MultipartFile file) throws IOException {
        Optional<User> currentUser = users.findByAlias(authentication.getName());
        if (currentUser.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "The authenticated user was not found."));
        }
        if (!currentUser.get().isEmailVerified()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Verify your email address before uploading patches."));
        }

        String trimmedArtistAlias = artistAlias.trim();
        String trimmedPatchName = patchName.trim();
        String trimmedDescription = description.trim();
        if (trimmedArtistAlias.isEmpty() || trimmedPatchName.isEmpty() || file.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Artist alias, patch name, and a file are required."));
        }
        if (!ARTIST_ALIAS_PATTERN.matcher(trimmedArtistAlias).matches()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Artist alias may contain only letters, numbers, and ()[]_.'+-."));
        }
        if (!PATCH_TEXT_PATTERN.matcher(trimmedPatchName).matches()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Patch name may contain only letters, numbers, spaces, and ()[]_.'+-."));
        }
        if (trimmedDescription.length() > 200
                || (!trimmedDescription.isEmpty()
                && !DESCRIPTION_PATTERN.matcher(trimmedDescription).matches())) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Description must be 200 characters or fewer and may contain only letters, numbers, and ()[]_.'+-."));
        }
        String extension = getExtension(file.getOriginalFilename());
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "The selected file type is not a supported patch format."));
        }

        String patchUri = storage.upload(
                "patches/" + UUID.randomUUID(), file.getBytes(), file.getContentType());
        Patch patch = new Patch(currentUser.get().getUserid(), trimmedArtistAlias, trimmedPatchName,
                trimmedDescription.isEmpty() ? null : trimmedDescription,
                file.getOriginalFilename(), extension, file.getSize(), patchUri);
        return ResponseEntity.status(HttpStatus.CREATED).body(PatchResponse.from(patches.save(patch)));
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

    private record PatchResponse(Long patchid, String artistAlias, String patchName, String description,
                                 String filename, String ext, Long filesize,
                                 java.time.Instant dateUploaded) {
        private static PatchResponse from(Patch patch) {
            return new PatchResponse(patch.getPatchid(), patch.getArtistAlias(), patch.getPatchName(),
                    patch.getDescription(), patch.getFilename(), patch.getExt(), patch.getFilesize(),
                    patch.getDateUploaded());
        }
    }
}

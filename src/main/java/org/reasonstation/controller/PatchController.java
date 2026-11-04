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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
    private static final int MAX_ARTIST_ALIAS_LENGTH = 255;
    private static final int MAX_PATCH_NAME_LENGTH = 255;
    private static final int MAX_DESCRIPTION_LENGTH = 200;
    private static final int MAX_FILENAME_LENGTH = 255;
    private static final long MAX_FILE_SIZE = 30L * 1024 * 1024;
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            ".sxt", ".xwv", ".smp", ".drex", ".drum", ".rv7", ".sm4", ".echo",
            ".pulver", ".gator", ".cmb", ".thor", ".kong", ".drp", ".zyp", ".patch",
            ".repatch", ".sf2", ".rx2", ".rcy", ".rex");
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

        String patchUri = storage.upload(
                "patches/" + UUID.randomUUID(), file.getBytes(), file.getContentType());
        Patch patch = new Patch(currentUser.get().getUserid(), trimmedArtistAlias, trimmedPatchName,
                trimmedDescription.isEmpty() ? null : trimmedDescription,
                filename, extension, file.getSize(), patchUri);
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

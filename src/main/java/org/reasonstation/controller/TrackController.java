package org.reasonstation.controller;

import org.reasonstation.entity.Track;
import org.reasonstation.entity.User;
import org.reasonstation.repository.TrackDownloadRepository;
import org.reasonstation.repository.TrackRatingRepository;
import org.reasonstation.repository.TrackRepository;
import org.reasonstation.repository.TrackWithRating;
import org.reasonstation.repository.UserRepository;
import org.reasonstation.service.S3StorageService;
import org.reasonstation.util.ReasonFileMetadata;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import jakarta.servlet.http.HttpServletRequest;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/tracks")
public class TrackController {
    private static final int MAX_ARTIST_ALIAS_LENGTH = 255;
    private static final int MAX_TRACK_NAME_LENGTH = 255;
    private static final int MAX_FILENAME_LENGTH = 255;
    private static final long MAX_FILE_SIZE = 80L * 1024 * 1024;
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(".rps", ".rns", ".reason");
    private static final Set<String> MUSIC_STYLES = Set.of(
            "Rock", "Alternative Rock", "Classic Rock", "Hard Rock", "Soft Rock",
            "Indie Rock", "Progressive Rock", "Psychedelic Rock", "Punk Rock",
            "Post-Punk", "Garage Rock", "Grunge", "Emo", "Pop Rock",
            "Southern Rock", "Blues Rock", "Folk Rock", "Industrial Rock",
            "Gothic Rock", "Post-Rock", "Math Rock", "Metal", "Heavy Metal",
            "Thrash Metal", "Death Metal", "Black Metal", "Doom Metal",
            "Power Metal", "Progressive Metal", "Symphonic Metal", "Folk Metal",
            "Nu Metal", "Metalcore", "Deathcore", "Industrial Metal",
            "Gothic Metal", "Sludge Metal", "Stoner Metal", "Pop", "Synthpop",
            "Electropop", "Dance Pop", "Indie Pop", "Dream Pop", "Art Pop",
            "Hyperpop", "K-Pop", "J-Pop", "Teen Pop", "Power Pop",
            "Bedroom Pop", "Pop Punk", "Electronic", "House", "Deep House",
            "Tech House", "Progressive House", "Electro House", "Techno",
            "Detroit Techno", "Trance", "Psytrance", "Drum & Bass", "Jungle",
            "Dubstep", "Garage", "Breakbeat", "Hardcore", "IDM", "Ambient",
            "Downtempo", "Chillout", "Synthwave", "Vaporwave", "Future Bass",
            "Glitch", "Electro", "Jazz", "Bebop", "Cool Jazz", "Swing",
            "Big Band", "Fusion", "Jazz Funk", "Smooth Jazz", "Free Jazz",
            "Avant-Garde Jazz", "Latin Jazz", "Acid Jazz", "Vocal Jazz",
            "Nu Jazz", "Blues", "Delta Blues", "Chicago Blues", "Electric Blues",
            "Country Blues", "Texas Blues", "Soul Blues", "Funk & Soul", "Funk",
            "P-Funk", "Soul", "Motown", "Neo-Soul", "Funk Rock", "Gospel Soul",
            "Southern Soul", "Disco", "Boogie", "Classical", "Baroque", "Romantic",
            "Modern Classical", "Contemporary Classical", "Minimalism",
            "Neoclassical", "Orchestral", "Chamber Music", "Opera", "Choral",
            "Film Score", "Video Game Music", "Folk", "Indie Folk", "Country",
            "Bluegrass", "Americana", "Celtic", "Irish Folk", "Flamenco",
            "Reggae", "Ska", "Dub", "Afrobeat", "Highlife", "Latin", "Salsa",
            "Samba", "Bossa Nova", "Cumbia", "Reggaeton", "Tango", "Klezmer",
            "Indian Classical", "Middle Eastern", "African Traditional",
            "Asian Traditional", "Hip-Hop", "Boom Bap", "East Coast Hip-Hop",
            "West Coast Hip-Hop", "Gangsta Rap", "Trap", "Drill", "Cloud Rap",
            "Lo-Fi Hip-Hop", "Conscious Hip-Hop", "Alternative Hip-Hop",
            "Jazz Rap", "Experimental Hip-Hop", "Old School Hip-Hop", "R&B",
            "Contemporary R&B", "Alternative R&B", "New Jack Swing", "Quiet Storm",
            "Gospel", "Dark Ambient", "Drone", "Ethereal", "Atmospheric",
            "Experimental", "Noise", "Industrial", "Minimalist", "Avant-Garde",
            "Soundscape", "Cinematic", "Darkwave", "Witch House", "Eurodance",
            "Dancehall", "EDM", "Future House", "Bass House", "UK Garage",
            "Epic", "Emotional", "Melancholic", "Uplifting", "Dark",
            "Aggressive", "Dreamy", "Relaxing", "Energetic",
            "Mysterious", "Haunting", "Futuristic", "Retro", "Vintage",
            "Minimal", "Acoustic", "Live", "Instrumental", "Vocal",
            "Female Vocal", "Male Vocal", "Other");
    private static final Pattern TRACK_TEXT_PATTERN =
            Pattern.compile("[A-Za-z0-9 ()\\[\\]_.'+\\-]+");
    private static final Pattern AUDIO_LINK_PATTERN =
            Pattern.compile("https://(?:(?:on\\.)?soundcloud|audiomack)\\.com/[^\\s\\\\]*");

    private final TrackRepository tracks;
    private final TrackDownloadRepository downloads;
    private final TrackRatingRepository ratings;
    private final UserRepository users;
    private final S3StorageService storage;

    public TrackController(TrackRepository tracks, TrackDownloadRepository downloads,
                           TrackRatingRepository ratings, UserRepository users, S3StorageService storage) {
        this.tracks = tracks;
        this.downloads = downloads;
        this.ratings = ratings;
        this.users = users;
        this.storage = storage;
    }

    @GetMapping("/me")
    public ResponseEntity<?> currentUserTracks(Authentication authentication) {
        return findCurrentUser(authentication)
                .<ResponseEntity<?>>map(user -> ResponseEntity.ok(
                        tracks.findByUseridWithRating(user.getUserid()).stream()
                                .map(TrackResponse::from)
                                .toList()))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "The authenticated user was not found.")));
    }

    @DeleteMapping("/{trackid}")
    public ResponseEntity<?> deleteTrack(Authentication authentication, @PathVariable Long trackid) {
        if (trackid == null || trackid < 1) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Track ID must be a positive integer."));
        }
        return findCurrentUser(authentication)
                .<ResponseEntity<?>>map(user -> {
                    if (tracks.findById(trackid)
                            .filter(track -> user.getUserid().equals(track.getUserid()))
                            .isEmpty()) {
                        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                                .body(Map.of("error", "Track not found."));
                    }

                    Track track = tracks.findById(trackid).orElseThrow();
                    storage.delete(track.getTrackUri());
                    tracks.deleteByTrackidAndUserid(trackid, user.getUserid());
                    return ResponseEntity.noContent().build();
                })
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "The authenticated user was not found.")));
    }

    @GetMapping("/{trackid}/download")
    public ResponseEntity<?> downloadTrack(@PathVariable Long trackid, HttpServletRequest request) {
        if (trackid == null || trackid < 1) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Track ID must be a positive integer."));
        }
        var track = tracks.findById(trackid);
        if (track.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Track not found."));
        }

        var downloadUrl = storage.createDownloadUrl(
                track.get().getTrackUri(), track.get().getFilename());
        downloads.recordDownload(trackid, request.getRemoteAddr());
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(downloadUrl)
                .build();
    }

    @PostMapping(value = "/{trackid}/rating", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> rateTrack(@PathVariable Long trackid, @RequestBody RatingRequest request,
                                       HttpServletRequest httpRequest) {
        if (trackid == null || trackid < 1) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Track ID must be a positive integer."));
        }
        if (request == null || request.rating() == null
                || request.rating() < 1 || request.rating() > 10) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Rating must be an integer between 1 and 10."));
        }
        if (tracks.findById(trackid).isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Track not found."));
        }

        String ipAddress = httpRequest.getRemoteAddr();
        if (ipAddress == null || ipAddress.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "An IP address is required."));
        }

        try {
            ratings.recordRating(trackid, ipAddress, request.rating().shortValue());
        } catch (DuplicateKeyException ignored) {
            // This IP address has already rated the track.
        }
        return ResponseEntity.ok(Map.of("trackid", trackid, "rating", request.rating()));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadTrack(
            Authentication authentication,
            @RequestParam("artistAlias") String artistAlias,
            @RequestParam("trackName") String trackName,
            @RequestParam("musicStyle") String musicStyle,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "audioLink", required = false) String audioLink) throws IOException {
        var currentUser = findCurrentUser(authentication);
        if (currentUser.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "The authenticated user was not found."));
        }
        if (!currentUser.get().isEmailVerified()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Verify your email address before uploading tracks."));
        }

        String trimmedArtistAlias = artistAlias == null ? "" : artistAlias.trim();
        String trimmedTrackName = trackName == null ? "" : trackName.trim();
        String trimmedMusicStyle = musicStyle == null ? "" : musicStyle.trim();
        String trimmedAudioLink = audioLink == null ? "" : audioLink.trim();
        if (!trimmedAudioLink.isEmpty() && !AUDIO_LINK_PATTERN.matcher(trimmedAudioLink).matches()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Audio link must start with https://soundcloud.com/, https://on.soundcloud.com/, or https://audiomack.com/ and contain no whitespace or backslashes."));
        }
        if (trimmedArtistAlias.isEmpty() || trimmedTrackName.isEmpty()
                || trimmedMusicStyle.isEmpty() || file == null || file.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Artist alias, track name, music style, and a file are required."));
        }
        if (trimmedArtistAlias.length() > MAX_ARTIST_ALIAS_LENGTH
                || trimmedTrackName.length() > MAX_TRACK_NAME_LENGTH) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Artist alias and track name must be 255 characters or fewer."));
        }
        if (!TRACK_TEXT_PATTERN.matcher(trimmedArtistAlias).matches()
                || !TRACK_TEXT_PATTERN.matcher(trimmedTrackName).matches()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Artist alias and track name may contain only letters, numbers, spaces, and ()[]_.'+-."));
        }
        String filename = file.getOriginalFilename();
        if (filename == null || filename.isBlank() || filename.length() > MAX_FILENAME_LENGTH
                || filename.contains("/") || filename.contains("\\") || file.getSize() > MAX_FILE_SIZE) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "File name is invalid or the file exceeds 80 MB."));
        }
        if (!hasAllowedExtension(filename)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Only .rps, .rns, and .reason files are allowed."));
        }
        if (!MUSIC_STYLES.contains(trimmedMusicStyle)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Choose a music style from the provided list."));
        }

        String objectKey = "tracks/" + UUID.randomUUID();
        String trackUri;
        byte[] content;
        try {
            content = file.getBytes();
            trackUri = storage.upload(objectKey, content, file.getContentType());
        } catch (IOException exception) {
            throw new TrackUploadException(exception);
        }

        Track track = new Track(currentUser.get().getUserid(), trimmedArtistAlias, trimmedTrackName,
                trimmedMusicStyle, filename, file.getSize(), trackUri);
        track.setAudioLink(trimmedAudioLink.isEmpty() ? null : trimmedAudioLink);
        String lowercaseFilename = filename.toLowerCase(Locale.ROOT);
        if (lowercaseFilename.endsWith(".rps")) {
            track.setProgramVersion(ReasonFileMetadata.programVersion(content).orElse("1.0.0"));
        } else if (lowercaseFilename.endsWith(".reason")) {
            track.setProgramVersion(ReasonFileMetadata.programVersion(content).orElse(null));
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(TrackResponse.from(tracks.save(track), 0));
    }

    private boolean hasAllowedExtension(String filename) {
        if (filename == null) {
            return false;
        }

        int extensionStart = filename.lastIndexOf('.');
        return extensionStart >= 0
                && ALLOWED_EXTENSIONS.contains(filename.substring(extensionStart).toLowerCase(Locale.ROOT));
    }

    private java.util.Optional<User> findCurrentUser(Authentication authentication) {
        return users.findByAlias(authentication.getName());
    }

    private record TrackResponse(Long trackid, String artistAlias, String trackName, String musicStyle,
                                 String filename, Long filesize, long downloadCount, Double averageTrackRating,
                                 java.time.Instant dateUploaded, String audioLink, String programVersion) {
        private static TrackResponse from(TrackWithRating track) {
            return new TrackResponse(track.trackid(), track.artistAlias(), track.trackName(),
                    track.musicStyle(), track.filename(), track.filesize(), track.downloadCount(),
                    track.averageTrackRating(), track.dateUploaded(), track.audioLink(), track.programVersion());
        }

        private static TrackResponse from(Track track, int downloadCount) {
            return new TrackResponse(track.getTrackid(), track.getArtistAlias(),
                    track.getTrackName(), track.getMusicStyle(), track.getFilename(),
                    track.getFilesize(), downloadCount, null, track.getDateUploaded(), track.getAudioLink(),
                    track.getProgramVersion());
        }
    }

    private record RatingRequest(Integer rating) {
    }

    private static class TrackUploadException extends RuntimeException {
        private TrackUploadException(IOException cause) {
            super("Unable to read uploaded track.", cause);
        }
    }
}

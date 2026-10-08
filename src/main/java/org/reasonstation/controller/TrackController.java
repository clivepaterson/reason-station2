package org.reasonstation.controller;

import org.reasonstation.entity.Track;
import org.reasonstation.entity.User;
import org.reasonstation.repository.TrackDownloadRepository;
import org.reasonstation.repository.TrackRatingRepository;
import org.reasonstation.repository.TrackRepository;
import org.reasonstation.repository.UserRepository;
import org.reasonstation.service.S3StorageService;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ContentDisposition;
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
            Pattern.compile("[A-Za-z0-9()\\[\\]_.'+\\-]+");

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
                        tracks.findByUserid(user.getUserid()).stream()
                        .map(track -> TrackResponse.from(track, downloads.countByTrackid(track.getTrackid())))
                                .toList()))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "The authenticated user was not found.")));
    }

    @DeleteMapping("/{trackid}")
    public ResponseEntity<?> deleteTrack(Authentication authentication, @PathVariable Long trackid) {
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
        var track = tracks.findById(trackid);
        if (track.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Track not found."));
        }

        byte[] content = storage.download(track.get().getTrackUri());
        downloads.recordDownload(trackid, request.getRemoteAddr());
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(track.get().getFilename())
                .build());
        return ResponseEntity.ok()
                .headers(headers)
                .body(content);
    }

    @PostMapping(value = "/{trackid}/rating", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> rateTrack(Authentication authentication, @PathVariable Long trackid,
                                       @RequestBody RatingRequest request) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Authentication is required."));
        }
        if (trackid == null || trackid < 1) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Track ID must be a positive integer."));
        }
        if (request == null || request.rating() == null
                || request.rating() < 1 || request.rating() > 5) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Rating must be an integer between 1 and 5."));
        }
        if (tracks.findById(trackid).isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Track not found."));
        }

        return findCurrentUser(authentication)
                .<ResponseEntity<?>>map(user -> {
                    ratings.upsertRating(trackid, user.getUserid(), request.rating().shortValue());
                    return ResponseEntity.ok(Map.of("trackid", trackid, "rating", request.rating()));
                })
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "The authenticated user was not found.")));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadTrack(
            Authentication authentication,
            @RequestParam("artistAlias") String artistAlias,
            @RequestParam("trackName") String trackName,
            @RequestParam("musicStyle") String musicStyle,
            @RequestParam("file") MultipartFile file) throws IOException {
        String trimmedArtistAlias = artistAlias.trim();
        String trimmedTrackName = trackName.trim();
        String trimmedMusicStyle = musicStyle.trim();
        if (trimmedArtistAlias.isEmpty() || trimmedTrackName.isEmpty()
                || trimmedMusicStyle.isEmpty() || file.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Artist alias, track name, music style, and a file are required."));
        }
        if (!TRACK_TEXT_PATTERN.matcher(artistAlias).matches()
                || !TRACK_TEXT_PATTERN.matcher(trackName).matches()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Artist alias and track name may contain only letters, numbers, and ()[]_.'+-."));
        }
        if (!hasAllowedExtension(file.getOriginalFilename())) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Only .rps, .rns, and .reason files are allowed."));
        }
        if (!MUSIC_STYLES.contains(trimmedMusicStyle)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Choose a music style from the provided list."));
        }

        return findCurrentUser(authentication)
                .<ResponseEntity<?>>map(user -> {
                    String objectKey = "tracks/" + UUID.randomUUID();
                    String trackUri;
                    try {
                        trackUri = storage.upload(objectKey, file.getBytes(), file.getContentType());
                    } catch (IOException exception) {
                        throw new TrackUploadException(exception);
                    }

                    Track track = new Track(user.getUserid(), trimmedArtistAlias, trimmedTrackName,
                            trimmedMusicStyle,
                            file.getOriginalFilename(), trackUri);
                    return ResponseEntity.status(HttpStatus.CREATED)
                            .body(TrackResponse.from(tracks.save(track), 0));
                })
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "The authenticated user was not found.")));
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
                                 String filename, int downloadCount, java.time.Instant dateUploaded) {
        private static TrackResponse from(Track track, int downloadCount) {
            return new TrackResponse(track.getTrackid(), track.getArtistAlias(),
                    track.getTrackName(), track.getMusicStyle(), track.getFilename(), downloadCount,
                    track.getDateUploaded());
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

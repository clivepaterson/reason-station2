package org.reasonstation.controller;

import org.reasonstation.entity.Track;
import org.reasonstation.repository.TrackDownloadRepository;
import org.reasonstation.repository.TrackRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;

@RestController
@RequestMapping("/api/discovery")
public class DiscoveryController {
    private final TrackRepository tracks;
    private final TrackDownloadRepository downloads;

    public DiscoveryController(TrackRepository tracks, TrackDownloadRepository downloads) {
        this.tracks = tracks;
        this.downloads = downloads;
    }

    @GetMapping("/artists")
    public Object randomArtists() {
        return tracks.findRandomArtistAliases(3).stream()
                .map(alias -> Map.of("artistAlias", alias))
                .toList();
    }

    @GetMapping("/artists-with-track-count")
    public Object randomArtistsWithTrackCount() {
        return tracks.findRandomArtistAliasesWithTrackCount(3).stream()
                .map(artist -> Map.of(
                        "artistAlias", artist.artistAlias(),
                        "trackCount", artist.trackCount(),
                        "dateCreated", artist.dateCreated()))
                .toList();
    }

    @GetMapping("/tracks")
    public Object randomTracks() {
        return tracks.findRandomTracks(10).stream()
                .map(track -> new TrackDiscoveryResponse(
                        track.trackid(),
                        track.artistAlias(),
                        track.trackName(),
                        track.musicStyle(),
                        track.filename(),
                        track.downloadCount(),
                        track.averageTrackRating(),
                        track.dateUploaded()))
                .toList();
    }

    @GetMapping("/popular-tracks")
    public Object popularTracksThisWeek() {
        return tracks.findMostDownloadedTracksThisWeek(5).stream()
                .map(track -> Map.of(
                        "trackid", track.trackid(),
                        "artistAlias", track.artistAlias(),
                        "trackName", track.trackName(),
                        "musicStyle", track.musicStyle(),
                        "filename", track.filename(),
                        "downloadCount", track.downloadCount()))
                .toList();
    }

    @GetMapping("/highest-rated-tracks")
    public Object highestRatedTracksThisWeek() {
        return tracks.findHighestRatedTracksThisWeek(3).stream()
                .map(track -> Map.of(
                        "trackid", track.trackid(),
                        "artistAlias", track.artistAlias(),
                        "trackName", track.trackName(),
                        "musicStyle", track.musicStyle(),
                        "filename", track.filename(),
                        "averageTrackRating", track.averageTrackRating(),
                        "ratingCount", track.ratingCount()))
                .toList();
    }

    @GetMapping("/popular-artists")
    public Object popularArtistsThisMonth() {
        return tracks.findMostDownloadedArtistsThisMonth(3).stream()
                .map(artist -> Map.of(
                        "artistAlias", artist.artistAlias(),
                        "downloadCount", artist.downloadCount()))
                .toList();
    }

    @GetMapping("/newest-tracks")
    public Object newestTracks() {
        return tracks.findNewestTracks(10).stream()
                .map(track -> Map.of(
                        "trackid", track.getTrackid(),
                        "artistAlias", track.getArtistAlias(),
                        "trackName", track.getTrackName(),
                        "musicStyle", track.getMusicStyle(),
                        "filename", track.getFilename(),
                        "dateUploaded", track.getDateUploaded()))
                .toList();
    }

    @GetMapping("/artist-tracks")
    public ResponseEntity<?> artistTracks(@RequestParam("artistAlias") String artistAlias) {
        return ResponseEntity.ok(tracks.findByArtistAlias(artistAlias).stream()
                .map(track -> new TrackDiscoveryResponse(
                        track.trackid(),
                        track.artistAlias(),
                        track.trackName(),
                        track.musicStyle(),
                        track.filename(),
                        track.downloadCount(),
                        track.averageTrackRating(),
                        track.dateUploaded()))
                .toList());
    }

    @GetMapping("/search")
    public ResponseEntity<?> searchTracks(@RequestParam("query") String query) {
        String searchTerm = query.trim();
        if (searchTerm.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "A search term is required."));
        }
        if (searchTerm.length() > 100) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Search terms must be 100 characters or fewer."));
        }

        return ResponseEntity.ok(tracks.searchByArtistAliasOrTrackName(searchTerm).stream()
                .map(track -> Map.of(
                        "trackid", track.getTrackid(),
                        "artistAlias", track.getArtistAlias(),
                        "trackName", track.getTrackName(),
                        "musicStyle", track.getMusicStyle(),
                        "filename", track.getFilename(),
                        "downloadCount", downloads.countByTrackid(track.getTrackid()),
                        "dateUploaded", track.getDateUploaded()))
                .toList());
    }

    private record TrackDiscoveryResponse(
            Long trackid,
            String artistAlias,
            String trackName,
            String musicStyle,
            String filename,
            long downloadCount,
            Double averageTrackRating,
            java.time.Instant dateUploaded) {
    }
}

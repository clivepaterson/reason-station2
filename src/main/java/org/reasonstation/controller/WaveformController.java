package org.reasonstation.controller;

import org.reasonstation.repository.TrackRepository;
import org.reasonstation.service.WaveformService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class WaveformController {
    private final TrackRepository tracks;
    private final WaveformService waveforms;

    public WaveformController(TrackRepository tracks, WaveformService waveforms) {
        this.tracks = tracks;
        this.waveforms = waveforms;
    }

    @GetMapping("/api/tracks/{trackid}/audio/waveform")
    public ResponseEntity<?> waveform(@PathVariable Long trackid) {
        if (trackid == null || trackid < 1) {
            return ResponseEntity.badRequest().body(Map.of("error", "Track ID must be a positive integer."));
        }
        var track = tracks.findById(trackid);
        if (track.isEmpty() || track.get().getAudioUri() == null || track.get().getAudioUri().isBlank()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Track audio not found."));
        }
        var result = waveforms.findOrSchedule(trackid, track.get().getAudioUri());
        return switch (result.state()) {
            case READY -> ResponseEntity.ok().cacheControl(CacheControl.noCache())
                    .contentType(MediaType.APPLICATION_JSON).body(result.content());
            case PENDING -> ResponseEntity.accepted().cacheControl(CacheControl.noStore())
                    .header("Retry-After", "3").body(Map.of("status", "processing"));
            case FAILED -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .cacheControl(CacheControl.noStore())
                    .body(Map.of("error", "Waveform unavailable. MP3 playback is unaffected."));
        };
    }
}

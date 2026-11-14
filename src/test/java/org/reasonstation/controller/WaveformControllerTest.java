package org.reasonstation.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.reasonstation.entity.Track;
import org.reasonstation.repository.TrackRepository;
import org.reasonstation.service.WaveformService;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class WaveformControllerTest {
    private final TrackRepository tracks = mock(TrackRepository.class);
    private final WaveformService waveforms = mock(WaveformService.class);
    private org.springframework.test.web.servlet.MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = standaloneSetup(new WaveformController(tracks, waveforms)).build();
    }

    @Test
    void rejectsInvalidTrackIdsAndMissingAudio() throws Exception {
        mvc.perform(get("/api/tracks/0/audio/waveform")).andExpect(status().isBadRequest());
        when(tracks.findById(42L)).thenReturn(Optional.empty());
        mvc.perform(get("/api/tracks/42/audio/waveform")).andExpect(status().isNotFound());
        var track = new Track(7L, "Artist", "Track", "song.reason", "s3://tracks/project");
        when(tracks.findById(42L)).thenReturn(Optional.of(track));
        mvc.perform(get("/api/tracks/42/audio/waveform")).andExpect(status().isNotFound());
        verifyNoInteractions(waveforms);
    }

    @Test
    void returnsJsonFromPrivateStorage() throws Exception {
        audio();
        byte[] json = "{\"version\":1,\"duration\":1,\"peaks\":[0.5]}".getBytes(StandardCharsets.UTF_8);
        when(waveforms.findOrSchedule(42L, "s3://tracks/audio"))
                .thenReturn(new WaveformService.Result(WaveformService.State.READY, json));
        mvc.perform(get("/api/tracks/42/audio/waveform")).andExpect(status().isOk())
                .andExpect(content().contentType("application/json"))
                .andExpect(jsonPath("$.peaks[0]").value(0.5))
                .andExpect(header().string("Cache-Control", "no-cache"));
    }

    @Test
    void returnsPendingWithRetryHintOrExplicitProcessingFailure() throws Exception {
        audio();
        when(waveforms.findOrSchedule(42L, "s3://tracks/audio")).thenReturn(
                new WaveformService.Result(WaveformService.State.PENDING, null),
                new WaveformService.Result(WaveformService.State.FAILED, null));
        mvc.perform(get("/api/tracks/42/audio/waveform")).andExpect(status().isAccepted())
                .andExpect(header().string("Retry-After", "3"))
                .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(get("/api/tracks/42/audio/waveform")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").exists());
    }

    private void audio() {
        var track = new Track(7L, "Artist", "Track", "song.reason", "s3://tracks/project");
        track.setAudioUri("s3://tracks/audio");
        when(tracks.findById(42L)).thenReturn(Optional.of(track));
    }
}

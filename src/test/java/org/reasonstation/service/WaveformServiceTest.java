package org.reasonstation.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.reasonstation.entity.Track;
import org.reasonstation.repository.TrackRepository;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.time.Duration;
import software.amazon.awssdk.services.s3.model.S3Exception;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WaveformServiceTest {
    private static final String AUDIO = "s3://tracks/track-audio/song";
    private static final String KEY = "track-audio/song.waveform-v1.json";
    private final S3StorageService storage = mock(S3StorageService.class);
    private final WaveformGenerator generator = mock(WaveformGenerator.class);
    private final TrackRepository tracks = mock(TrackRepository.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private WaveformService service;

    @BeforeEach
    void setUp() {
        service = new WaveformService(storage, generator, tracks, mapper);
    }

    @AfterEach
    void tearDown() {
        service.close();
    }

    @Test
    void readsExistingWaveformWithoutDownloadingOrDecodingAudio() throws Exception {
        byte[] json = "{\"version\":1,\"duration\":1,\"peaks\":[0.5]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        when(storage.readOptional(AUDIO + ".waveform-v1.json", 32768)).thenReturn(Optional.of(json));

        var result = service.findOrSchedule(42L, AUDIO);

        assertEquals(WaveformService.State.READY, result.state());
        assertArrayEquals(json, result.content());
        verifyNoInteractions(generator, tracks);
        verify(storage, never()).downloadTo(any(), any(), anyLong());
    }

    @Test
    void generatesMissingWaveformOnceAndStoresOnlySuccessfulJson() throws Exception {
        var release = new CountDownLatch(1);
        var started = new CountDownLatch(1);
        when(storage.readOptional(anyString(), anyInt())).thenReturn(Optional.empty());
        when(generator.generate(any(), any(), any())).thenAnswer(call -> {
            started.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return new WaveformGenerator.Waveform(1, 2.5, new double[]{0.25, 1});
        });
        currentAudio(AUDIO);
        try {
            assertEquals(WaveformService.State.PENDING, service.findOrSchedule(42L, AUDIO).state());
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertEquals(WaveformService.State.PENDING, service.findOrSchedule(42L, AUDIO).state());
        } finally {
            release.countDown();
        }
        var json = ArgumentCaptor.forClass(byte[].class);
        verify(storage, timeout(5000)).upload(eq(KEY), json.capture(), eq("application/json"));
        var waveform = mapper.readValue(json.getValue(), WaveformGenerator.Waveform.class);
        assertEquals(2.5, waveform.duration());
        assertArrayEquals(new double[]{0.25, 1}, waveform.peaks());
        verify(generator).generate(any(), any(), any());
    }

    @Test
    void failedDecodeStoresNothingReportsFailureAndCleansTemporaryFiles() throws Exception {
        var input = ArgumentCaptor.forClass(Path.class);
        when(generator.generate(input.capture(), any(), any())).thenThrow(new IOException("Invalid MP3"));

        service.schedule(42L, AUDIO);

        awaitState(WaveformService.State.FAILED);
        verify(storage, never()).upload(any(), any(), any());
        service.findOrSchedule(42L, AUDIO);
        verify(generator).generate(any(), any(), any());
        awaitDeleted(input.getValue().getParent());
    }

    @Test
    void storageReadFailuresAreNotTreatedAsMissingObjects() throws Exception {
        when(storage.readOptional(anyString(), anyInt())).thenThrow(new IOException("Storage unavailable"));

        assertEquals(WaveformService.State.FAILED, service.findOrSchedule(42L, AUDIO).state());

        verifyNoInteractions(generator, tracks);
        verify(storage, never()).downloadTo(any(), any(), anyLong());
    }

    @Test
    void slowWaveformReadDoesNotBlockSchedulingOtherUploads() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(storage.readOptional(eq(AUDIO + ".waveform-v1.json"), anyInt())).thenAnswer(call -> {
            started.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return Optional.empty();
        });
        when(generator.generate(any(), any(), any())).thenReturn(
                new WaveformGenerator.Waveform(1, 1, new double[]{0.5}));
        Thread reader = Thread.ofPlatform().start(() -> service.findOrSchedule(42L, AUDIO));
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertTimeoutPreemptively(Duration.ofSeconds(1),
                    () -> service.schedule(99L, "s3://tracks/track-audio/other"));
        } finally {
            release.countDown();
            reader.join(5000);
        }
        assertFalse(reader.isAlive());
    }

    @Test
    void optionalCleanupFailureDoesNotFailTrackChanges() {
        doThrow(S3Exception.builder().statusCode(503).message("Storage unavailable").build())
                .when(storage).delete(AUDIO + ".waveform-v1.json");

        assertDoesNotThrow(() -> service.delete(AUDIO));

        verify(storage).delete(AUDIO + ".waveform-v1.json");
    }

    @Test
    void removesWaveformIfAnotherInstanceReplacedAudioDuringPublication() throws Exception {
        when(generator.generate(any(), any(), any())).thenReturn(
                new WaveformGenerator.Waveform(1, 1, new double[]{0.5}));
        when(tracks.findById(42L)).thenReturn(Optional.of(track(AUDIO)), Optional.empty());

        service.schedule(42L, AUDIO);

        verify(storage, timeout(5000)).delete(AUDIO + ".waveform-v1.json");
        verify(storage).upload(eq(KEY), any(), eq("application/json"));
    }

    @Test
    void waveformUploadFailureIsReportedWithoutRepeatedDecoding() throws Exception {
        when(generator.generate(any(), any(), any())).thenReturn(
                new WaveformGenerator.Waveform(1, 1, new double[]{0.5}));
        currentAudio(AUDIO);
        when(storage.upload(anyString(), any(), eq("application/json")))
                .thenThrow(new IllegalStateException("S3 write failed"));

        service.schedule(42L, AUDIO);

        awaitState(WaveformService.State.FAILED);
        service.findOrSchedule(42L, AUDIO);
        verify(generator).generate(any(), any(), any());
        verify(storage).upload(eq(KEY), any(), eq("application/json"));
    }

    @Test
    void replacementDuringGenerationDoesNotStoreStaleWaveform() throws Exception {
        when(generator.generate(any(), any(), any())).thenReturn(
                new WaveformGenerator.Waveform(1, 1, new double[]{1}));
        currentAudio("s3://tracks/track-audio/replacement");
        var checked = new CountDownLatch(1);
        when(tracks.findById(42L)).thenAnswer(call -> {
            checked.countDown();
            return Optional.of(track("s3://tracks/track-audio/replacement"));
        });

        service.schedule(42L, AUDIO);

        assertTrue(checked.await(5, TimeUnit.SECONDS));
        verify(storage, never()).upload(any(), any(), any());
    }

    @Test
    void deletionCancelsInFlightWriteAndDeletesStoredWaveform() throws Exception {
        var release = new CountDownLatch(1);
        var started = new CountDownLatch(1);
        var finished = new CountDownLatch(1);
        when(generator.generate(any(), any(), any())).thenAnswer(call -> {
            started.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            finished.countDown();
            return new WaveformGenerator.Waveform(1, 1, new double[]{1});
        });
        service.schedule(42L, AUDIO);
        assertTrue(started.await(5, TimeUnit.SECONDS));
        service.delete(AUDIO);
        release.countDown();

        assertTrue(finished.await(5, TimeUnit.SECONDS));
        verify(storage).delete(AUDIO + ".waveform-v1.json");
        verify(storage, never()).upload(any(), any(), any());
    }

    private void currentAudio(String uri) {
        when(tracks.findById(42L)).thenReturn(Optional.of(track(uri)));
    }

    private Track track(String uri) {
        var track = new Track(7L, "Artist", "Track", "song.reason", "s3://tracks/project");
        track.setAudioUri(uri);
        return track;
    }

    private void awaitState(WaveformService.State expected) throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            if (service.findOrSchedule(42L, AUDIO).state() == expected) {
                return;
            }
            Thread.sleep(20);
        }
        fail("Waveform did not enter state " + expected);
    }

    private void awaitDeleted(Path path) throws InterruptedException {
        for (int attempt = 0; attempt < 100 && Files.exists(path); attempt++) {
            Thread.sleep(20);
        }
        assertFalse(Files.exists(path), "Temporary processing directory must be removed");
    }
}

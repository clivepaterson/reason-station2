package org.reasonstation.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class WaveformGeneratorTest {
    @TempDir Path directory;

    @Test
    @EnabledIfEnvironmentVariable(named = "WAVEFORM_FFMPEG", matches = ".+")
    void decodesRealMp3AndRejectsCorruptAudio() throws Exception {
        String executable = System.getenv("WAVEFORM_FFMPEG");
        Path audio = directory.resolve("sine.mp3");
        Process encode = new ProcessBuilder(executable, "-hide_banner", "-loglevel", "error", "-nostdin",
                "-f", "lavfi", "-i", "sine=frequency=440:duration=1", "-codec:a", "libmp3lame",
                audio.toString()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        try {
            assertTrue(encode.waitFor(15, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(0, encode.exitValue());
        } finally {
            if (encode.isAlive()) {
                encode.destroyForcibly();
                encode.waitFor();
            }
        }
        var generator = new WaveformGenerator(executable, Duration.ofSeconds(15));

        var waveform = generator.generate(audio, directory.resolve("audio.pcm"), directory.resolve("ffmpeg.log"));

        assertEquals(1.0, waveform.duration(), 0.05);
        assertEquals(1200, waveform.peaks().length);
        assertTrue(java.util.Arrays.stream(waveform.peaks()).max().orElseThrow() > 0.05);
        Files.write(audio, new byte[]{1, 2, 3});
        assertThrows(IOException.class, () -> generator.generate(audio,
                directory.resolve("invalid.pcm"), directory.resolve("invalid.log")));
    }

    @Test
    void reducesPcmToBoundedPeaksAndMeasuresDuration() throws Exception {
        ByteBuffer samples = ByteBuffer.allocate(16000).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 8000; i++) {
            samples.putShort((short) (i < 4000 ? -32768 : 16384));
        }
        Path pcm = directory.resolve("audio.pcm");
        Files.write(pcm, samples.array());

        var waveform = WaveformGenerator.readPeaks(pcm);

        assertEquals(1, waveform.version());
        assertEquals(1.0, waveform.duration());
        assertEquals(1200, waveform.peaks().length);
        assertEquals(1.0, waveform.peaks()[0]);
        assertEquals(0.5, waveform.peaks()[1199]);
        assertTrue(java.util.Arrays.stream(waveform.peaks()).allMatch(peak -> peak >= 0 && peak <= 1));
    }

    @Test
    void handlesShortAndSilentAudioWithoutInventingPeaks() throws Exception {
        Path pcm = directory.resolve("short.pcm");
        Files.write(pcm, new byte[4]);

        var waveform = WaveformGenerator.readPeaks(pcm);

        assertEquals(2.0 / 8000, waveform.duration());
        assertArrayEquals(new double[]{0, 0}, waveform.peaks());
    }

    @Test
    void rejectsEmptyAndIncompletePcm() throws Exception {
        Path pcm = directory.resolve("invalid.pcm");
        Files.write(pcm, new byte[0]);
        assertThrows(IOException.class, () -> WaveformGenerator.readPeaks(pcm));
        Files.write(pcm, new byte[]{1});
        assertThrows(IOException.class, () -> WaveformGenerator.readPeaks(pcm));
    }

    @Test
    void rejectsOutputThatReachedTheDecoderSizeLimit() throws Exception {
        Path pcm = directory.resolve("limited.pcm");
        try (var file = new java.io.RandomAccessFile(pcm.toFile(), "rw")) {
            file.setLength(WaveformGenerator.MAX_PCM_BYTES);
        }
        assertThrows(IOException.class, () -> WaveformGenerator.readPeaks(pcm));
    }

    @Test
    void missingDecoderIsAnExplicitFailure() {
        var generator = new WaveformGenerator(directory.resolve("missing-ffmpeg").toString(), Duration.ofSeconds(1));
        assertThrows(IOException.class, () -> generator.generate(directory.resolve("input.mp3"),
                directory.resolve("audio.pcm"), directory.resolve("ffmpeg.log")));
    }
}

package org.reasonstation.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.BufferedInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Component
public class WaveformGenerator {
    static final int SAMPLE_RATE = 8000;
    static final long MAX_PCM_BYTES = 64L * 1024 * 1024;
    private final String executable;
    private final Duration timeout;

    public WaveformGenerator(@Value("${app.audio.waveform.ffmpeg:${WAVEFORM_FFMPEG:ffmpeg}}") String executable,
                             @Value("${app.audio.waveform.timeout:${WAVEFORM_TIMEOUT:PT90S}}") Duration timeout) {
        this.executable = executable;
        this.timeout = timeout;
    }

    public Waveform generate(Path audio, Path pcm, Path errors) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(executable, "-hide_banner", "-loglevel", "error", "-nostdin", "-xerror",
                "-y", "-protocol_whitelist", "file,pipe", "-f", "mp3", "-i", audio.toString(),
                "-vn", "-ac", "1", "-ar", Integer.toString(SAMPLE_RATE),
                "-f", "s16le", "-fs", Long.toString(MAX_PCM_BYTES), pcm.toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(errors.toFile())
                .start();
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IOException("Waveform decoding timed out.");
            }
            if (process.exitValue() != 0) {
                throw new IOException("FFmpeg could not decode the MP3 (exit " + process.exitValue() + ").");
            }
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor();
            }
        }
        return readPeaks(pcm);
    }

    static Waveform readPeaks(Path pcm) throws IOException {
        long size = Files.size(pcm);
        if (size == 0 || size >= MAX_PCM_BYTES || size % 2 != 0) {
            throw new IOException("Decoded audio is empty, invalid, or exceeds the waveform size limit.");
        }
        long samples = size / 2;
        int count = (int) Math.min(1200, samples);
        double[] peaks = new double[count];
        try (var input = new BufferedInputStream(Files.newInputStream(pcm))) {
            for (long sample = 0; sample < samples; sample++) {
                int low = input.read();
                int high = input.read();
                if (low < 0 || high < 0) {
                    throw new IOException("Decoded audio ended unexpectedly.");
                }
                short value = (short) (low | high << 8);
                int bucket = (int) (sample * count / samples);
                peaks[bucket] = Math.max(peaks[bucket], Math.abs((int) value) / 32768.0);
            }
        }
        for (int i = 0; i < peaks.length; i++) {
            peaks[i] = Math.round(peaks[i] * 10000) / 10000.0;
        }
        return new Waveform(1, (double) samples / SAMPLE_RATE, peaks);
    }

    public record Waveform(int version, double duration, double[] peaks) {
    }
}

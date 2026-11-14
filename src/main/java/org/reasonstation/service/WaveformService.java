package org.reasonstation.service;

import jakarta.annotation.PreDestroy;
import org.reasonstation.repository.TrackRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.exception.SdkException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class WaveformService {
    private static final Logger log = LoggerFactory.getLogger(WaveformService.class);
    private final S3StorageService storage;
    private final WaveformGenerator generator;
    private final TrackRepository tracks;
    private final ObjectMapper mapper;
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(32), Thread.ofPlatform().name("audio-waveform-", 0).factory(),
            new ThreadPoolExecutor.AbortPolicy());
    private final ConcurrentHashMap<String, Job> jobs = new ConcurrentHashMap<>();
    private final ReentrantLock[] publicationLocks = java.util.stream.IntStream.range(0, 64)
            .mapToObj(index -> new ReentrantLock()).toArray(ReentrantLock[]::new);

    public WaveformService(S3StorageService storage, WaveformGenerator generator,
                           TrackRepository tracks, ObjectMapper mapper) {
        this.storage = storage;
        this.generator = generator;
        this.tracks = tracks;
        this.mapper = mapper;
    }

    public Result findOrSchedule(Long trackid, String audioUri) {
        try {
            removeExpiredFailures();
            Job job = jobs.get(audioUri);
            if (job != null) {
                return new Result(job.failed ? State.FAILED : State.PENDING, null);
            }
            Optional<byte[]> stored = storage.readOptional(waveformUri(audioUri), 32768);
            if (stored.isPresent()) {
                return new Result(State.READY, stored.get());
            }
            schedule(trackid, audioUri);
            return new Result(State.PENDING, null);
        } catch (IOException | RuntimeException exception) {
            log.warn("Unable to load waveform for track {}", trackid, exception);
            return new Result(State.FAILED, null);
        }
    }

    public void schedule(Long trackid, String audioUri) {
        // Failed jobs cool down before retrying, and never grow an unbounded in-memory cache.
        removeExpiredFailures();
        Job job = new Job();
        if (jobs.putIfAbsent(audioUri, job) != null) {
            return;
        }
        try {
            executor.execute(() -> process(trackid, audioUri, job));
        } catch (RejectedExecutionException exception) {
            jobs.remove(audioUri, job);
            log.warn("Waveform queue is full; generation deferred for track {}", trackid);
        }
    }

    private void removeExpiredFailures() {
        Instant cutoff = Instant.now().minusSeconds(300);
        jobs.entrySet().removeIf(entry -> entry.getValue().failed
                && entry.getValue().finished.isBefore(cutoff));
    }

    private void process(Long trackid, String audioUri, Job job) {
        Path directory = null;
        try {
            // A concurrent request may have queued this job just as another one finished.
            if (job.cancelled || storage.readOptional(waveformUri(audioUri), 32768).isPresent()) {
                jobs.remove(audioUri, job);
                return;
            }
            directory = Files.createTempDirectory("reasonstation-waveform-");
            Path audio = directory.resolve("input.mp3");
            storage.downloadTo(audioUri, audio, 80L * 1024 * 1024);
            var waveform = generator.generate(audio, directory.resolve("audio.pcm"), directory.resolve("ffmpeg.log"));
            byte[] content = mapper.writeValueAsBytes(waveform);
            ReentrantLock publication = publicationLock(audioUri);
            publication.lock();
            try {
                // Replacements have unique audio URIs; never attach old peaks to new audio.
                if (!job.cancelled && tracks.findById(trackid)
                        .filter(track -> audioUri.equals(track.getAudioUri())).isPresent()) {
                    String key = java.net.URI.create(waveformUri(audioUri)).getPath().substring(1);
                    storage.upload(key, content, "application/json");
                    // Another application instance may have replaced/deleted the track during the write.
                    if (tracks.findById(trackid).filter(track -> audioUri.equals(track.getAudioUri())).isEmpty()) {
                        storage.delete(waveformUri(audioUri));
                    }
                }
                jobs.remove(audioUri, job);
            } finally {
                publication.unlock();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            fail(trackid, audioUri, job, exception);
        } catch (IOException | RuntimeException exception) {
            fail(trackid, audioUri, job, exception);
        } finally {
            if (directory != null) {
                for (String filename : new String[]{"input.mp3", "audio.pcm", "ffmpeg.log"}) {
                    deleteTemp(directory.resolve(filename));
                }
                deleteTemp(directory);
            }
        }
    }

    private void fail(Long trackid, String audioUri, Job job, Exception exception) {
        job.finished = Instant.now();
        job.failed = true;
        if (job.cancelled || jobs.size() > 64) {
            jobs.remove(audioUri, job);
        }
        log.warn("Waveform generation failed for track {}; MP3 playback is unaffected", trackid, exception);
    }

    public void delete(String audioUri) {
        ReentrantLock publication = publicationLock(audioUri);
        publication.lock();
        try {
            Job job = jobs.remove(audioUri);
            if (job != null) {
                job.cancelled = true;
            }
            storage.delete(waveformUri(audioUri));
        } catch (SdkException exception) {
            log.warn("Unable to delete optional waveform for {}; track changes remain saved", audioUri, exception);
        } finally {
            publication.unlock();
        }
    }

    private ReentrantLock publicationLock(String audioUri) {
        return publicationLocks[Math.floorMod(audioUri.hashCode(), publicationLocks.length)];
    }

    static String waveformUri(String audioUri) {
        return audioUri + ".waveform-v1.json";
    }

    private void deleteTemp(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            log.warn("Unable to delete waveform temporary file {}", path, exception);
        }
    }

    @PreDestroy
    public void close() {
        executor.shutdownNow();
    }

    public enum State { READY, PENDING, FAILED }
    public record Result(State state, byte[] content) { }

    private static final class Job {
        private volatile boolean failed;
        private volatile boolean cancelled;
        private volatile Instant finished;
    }
}

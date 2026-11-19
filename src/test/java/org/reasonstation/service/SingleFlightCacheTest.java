package org.reasonstation.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class SingleFlightCacheTest {
    @Test
    void expiresAtTtlBoundaryMeasuredFromLoadCompletion() {
        AtomicLong now = new AtomicLong();
        var cache = new SingleFlightCache<String, String>(30, now::get);

        assertEquals("first", cache.get("feed", () -> {
            now.set(TimeUnit.MILLISECONDS.toNanos(100));
            return "first";
        }));
        now.set(TimeUnit.MILLISECONDS.toNanos(129));
        assertEquals("first", cache.get("feed", () -> fail("Not yet expired")));
        now.set(TimeUnit.MILLISECONDS.toNanos(130));
        assertEquals("second", cache.get("feed", () -> "second"));
    }

    @Test
    void retainsOnlyOneKeyAndDoesNotMixResultsForDifferentLimits() {
        var cache = new SingleFlightCache<Integer, String>(30_000);

        assertEquals("three", cache.get(3, () -> "three"));
        assertEquals("ten", cache.get(10, () -> "ten"));
        assertEquals("three again", cache.get(3, () -> "three again"));
    }

    @Test
    void zeroTtlDisablesReuseAndNegativeTtlIsRejected() {
        var cache = new SingleFlightCache<Integer, String>(0);

        assertEquals("first", cache.get(3, () -> "first"));
        assertEquals("second", cache.get(3, () -> "second"));
        assertThrows(IllegalArgumentException.class, () -> new SingleFlightCache<>(-1));
    }

    @Test
    void sharesInFlightLoadEvenWhenTtlIsZero() throws Exception {
        var cache = new SingleFlightCache<Integer, String>(0);
        CountDownLatch loading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Thread> waitingThread = new AtomicReference<>();
        AtomicInteger loads = new AtomicInteger();

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> cache.get(3, () -> {
                loads.incrementAndGet();
                loading.countDown();
                await(release);
                return "shared";
            }));
            try {
                assertTrue(loading.await(5, TimeUnit.SECONDS));
                var second = executor.submit(() -> {
                    waitingThread.set(Thread.currentThread());
                    return cache.get(3, () -> fail("Must share the active load"));
                });
                awaitWaiting(waitingThread);
                release.countDown();
                assertEquals("shared", first.get(5, TimeUnit.SECONDS));
                assertEquals("shared", second.get(5, TimeUnit.SECONDS));
                assertEquals(1, loads.get());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void sharesFailureWithoutCachingItAndAllowsRetry() throws Exception {
        var cache = new SingleFlightCache<Integer, String>(30_000);
        CountDownLatch loading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Thread> waitingThread = new AtomicReference<>();
        var failure = new IllegalStateException("database unavailable");

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> cache.get(3, () -> {
                loading.countDown();
                await(release);
                throw failure;
            }));
            try {
                assertTrue(loading.await(5, TimeUnit.SECONDS));
                var second = executor.submit(() -> {
                    waitingThread.set(Thread.currentThread());
                    return cache.get(3, () -> fail("Must share the active failure"));
                });
                awaitWaiting(waitingThread);
                release.countDown();
                assertSame(failure, assertThrows(ExecutionException.class,
                        () -> first.get(5, TimeUnit.SECONDS)).getCause());
                assertSame(failure, assertThrows(ExecutionException.class,
                        () -> second.get(5, TimeUnit.SECONDS)).getCause());
                assertEquals("recovered", cache.get(3, () -> "recovered"));
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void expiredValueIsNotReturnedWhenRefreshFails() {
        AtomicLong now = new AtomicLong();
        var cache = new SingleFlightCache<Integer, String>(30, now::get);
        cache.get(3, () -> "old");
        now.set(TimeUnit.MILLISECONDS.toNanos(30));
        var failure = new IllegalStateException("database unavailable");

        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> cache.get(3, () -> {
                    throw failure;
                })));
        assertEquals("new", cache.get(3, () -> "new"));
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static void awaitWaiting(AtomicReference<Thread> thread) {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            while (thread.get() == null || thread.get().getState() != Thread.State.WAITING) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new AssertionError("Timed out waiting for the shared load.");
                }
                Thread.onSpinWait();
            }
        });
    }
}

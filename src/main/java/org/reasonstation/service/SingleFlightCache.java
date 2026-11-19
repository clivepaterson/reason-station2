package org.reasonstation.service;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * A single-entry cache that shares an active load and never retains failed loads.
 * Different keys replace the one cached entry rather than growing the cache.
 */
public final class SingleFlightCache<K, V> {
    private final long ttlNanos;
    private final LongSupplier ticker;
    private Entry<K, V> cached;
    private Flight<K, V> inFlight;

    public SingleFlightCache(long ttlMillis) {
        this(ttlMillis, System::nanoTime);
    }

    SingleFlightCache(long ttlMillis, LongSupplier ticker) {
        if (ttlMillis < 0) {
            throw new IllegalArgumentException("Cache TTL must not be negative.");
        }
        this.ttlNanos = TimeUnit.MILLISECONDS.toNanos(ttlMillis);
        this.ticker = ticker;
    }

    public V get(K key, Supplier<V> loader) {
        while (true) {
            Flight<K, V> flight;
            boolean owner = false;
            synchronized (this) {
                if (cached != null && Objects.equals(cached.key(), key)
                        && ticker.getAsLong() - cached.loadedAt() < ttlNanos) {
                    return cached.value();
                }
                if (inFlight == null) {
                    inFlight = new Flight<>(key, new CompletableFuture<>());
                    owner = true;
                }
                flight = inFlight;
            }

            if (owner) {
                try {
                    V value = loader.get();
                    synchronized (this) {
                        cached = new Entry<>(key, value, ticker.getAsLong());
                        inFlight = null;
                        flight.result().complete(value);
                    }
                    return value;
                } catch (RuntimeException | Error failure) {
                    synchronized (this) {
                        inFlight = null;
                        flight.result().completeExceptionally(failure);
                    }
                    throw failure;
                }
            }

            try {
                V value = flight.result().join();
                if (Objects.equals(flight.key(), key)) {
                    return value;
                }
            } catch (CompletionException failure) {
                if (Objects.equals(flight.key(), key)) {
                    if (failure.getCause() instanceof RuntimeException cause) {
                        throw cause;
                    }
                    if (failure.getCause() instanceof Error cause) {
                        throw cause;
                    }
                    throw failure;
                }
            }
        }
    }

    private record Entry<K, V>(K key, V value, long loadedAt) {
    }

    private record Flight<K, V>(K key, CompletableFuture<V> result) {
    }
}

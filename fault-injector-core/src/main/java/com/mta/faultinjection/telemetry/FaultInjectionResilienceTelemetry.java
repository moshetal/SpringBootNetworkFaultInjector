package com.mta.faultinjection.telemetry;

import com.mta.faultinjection.core.FaultDecision;
import java.net.URI;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.HttpMethod;

/**
 * Sliding-window resilience-signal sink.
 * <p>
 * Sibling to {@link FaultInjectionTelemetry}; tracks how the calling service
 * behaves under injected faults (retries, circuit breaker, observed delay)
 * rather than how the injector itself fired.
 * <p>
 * All state is lock-free, mirroring the {@link FaultInjectionTelemetry}
 * concurrency model: {@link ConcurrentHashMap} for keyed buckets,
 * {@link AtomicInteger} for counters, {@link ConcurrentLinkedDeque} for the
 * bounded ring buffers of finalized observations.
 */
public class FaultInjectionResilienceTelemetry {

    private final long retryWindowMs;
    private final int cbThreshold;
    private final long cbWindowMs;
    private final int bufferSize;
    private final Clock clock;

    private final ConcurrentHashMap<TargetKey, OpenRetry> openRetries = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<HostMethodKey, AtomicInteger> consecutiveErrors = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<HostMethodKey, OpenCb> openCbs = new ConcurrentHashMap<>();

    private final ConcurrentLinkedDeque<RetryObservation> finalizedRetries = new ConcurrentLinkedDeque<>();
    private final ConcurrentLinkedDeque<CircuitBreakerObservation> finalizedCbs = new ConcurrentLinkedDeque<>();
    private final ConcurrentLinkedDeque<DelayObservation> delays = new ConcurrentLinkedDeque<>();

    public FaultInjectionResilienceTelemetry(
            long retryWindowMs, int cbThreshold, long cbWindowMs, int bufferSize, Clock clock) {
        if (retryWindowMs <= 0 || cbWindowMs <= 0) {
            throw new IllegalArgumentException("windows must be positive");
        }
        if (cbThreshold <= 0) {
            throw new IllegalArgumentException("cbThreshold must be positive");
        }
        if (bufferSize <= 0) {
            throw new IllegalArgumentException("bufferSize must be positive");
        }
        this.retryWindowMs = retryWindowMs;
        this.cbThreshold = cbThreshold;
        this.cbWindowMs = cbWindowMs;
        this.bufferSize = bufferSize;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    // ----- hooks (filled in by Tasks 5-7) -----

    public void observeOutbound(HttpMethod method, URI uri, FaultDecision decision) {
        // Implemented in Tasks 5 and 6.
    }

    public void noteObservedDelay(
            String ruleName, HttpMethod method, URI uri,
            long injectedDelayMs, long observedWaitMs, boolean completedSuccessfully) {
        // Implemented in Task 7.
    }

    // ----- snapshots -----

    public List<RetryObservation> retryObservations() {
        finalizeExpired(clock.millis());
        List<RetryObservation> out = new ArrayList<>(finalizedRetries);
        Collections.reverse(out); // newest-first
        return out;
    }

    public List<CircuitBreakerObservation> circuitBreakerObservations() {
        finalizeExpired(clock.millis());
        List<CircuitBreakerObservation> out = new ArrayList<>(finalizedCbs);
        Collections.reverse(out);
        return out;
    }

    public List<DelayObservation> delayObservations() {
        List<DelayObservation> out = new ArrayList<>(delays);
        Collections.reverse(out);
        return out;
    }

    public void resetAll() {
        openRetries.clear();
        consecutiveErrors.clear();
        openCbs.clear();
        finalizedRetries.clear();
        finalizedCbs.clear();
        delays.clear();
    }

    // ----- internals -----

    private void finalizeExpired(long now) {
        // Implemented incrementally in Tasks 5 and 6.
    }

    static final class OpenRetry {
        final String ruleName;
        final TargetKey key;
        final long startEpochMs;
        final AtomicInteger depth = new AtomicInteger();

        OpenRetry(String ruleName, TargetKey key, long startEpochMs) {
            this.ruleName = ruleName;
            this.key = key;
            this.startEpochMs = startEpochMs;
        }
    }

    static final class OpenCb {
        final HostMethodKey key;
        final long startEpochMs;
        final AtomicInteger postWindowCallCount = new AtomicInteger();

        OpenCb(HostMethodKey key, long startEpochMs) {
            this.key = key;
            this.startEpochMs = startEpochMs;
        }
    }
}

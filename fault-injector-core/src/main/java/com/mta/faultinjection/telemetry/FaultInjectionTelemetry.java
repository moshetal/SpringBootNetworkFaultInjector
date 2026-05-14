package com.mta.faultinjection.telemetry;

import com.mta.faultinjection.config.FaultInjectionProperties.Rule;
import com.mta.faultinjection.core.FaultDecision;
import java.net.URI;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import org.springframework.http.HttpMethod;

/**
 * Live telemetry sink for the bundled fault-injection UI.
 * <p>
 * Holds two thread-safe data structures:
 * <ul>
 *   <li>A bounded ring buffer of recent {@link FaultInjectionEvent} records, drained
 *       newest-first by the UI's "Recent Events" table.</li>
 *   <li>A circular array of fixed-width time buckets aggregating per-rule and total
 *       match/trigger counts, drained oldest-first by the UI's chart.</li>
 * </ul>
 * <p>
 * The strategy calls {@link #recordMatch} and {@link #recordTrigger} from any
 * thread that handled an outbound request. All operations are wait-free in the
 * common case (no locking).
 */
public class FaultInjectionTelemetry {

    private final int eventBufferSize;
    private final long bucketWidthMs;
    private final int bucketCount;
    private final Clock clock;

    private final ConcurrentLinkedDeque<FaultInjectionEvent> events = new ConcurrentLinkedDeque<>();
    private final AtomicLong eventCount = new AtomicLong();

    /** Slots reused round-robin; {@link Bucket#startEpochMs} is updated via CAS to claim a slot. */
    private final Bucket[] buckets;

    public FaultInjectionTelemetry(int eventBufferSize, long bucketWidthMs, int bucketCount) {
        this(eventBufferSize, bucketWidthMs, bucketCount, Clock.systemUTC());
    }

    /** Test-friendly constructor that accepts a deterministic clock. */
    public FaultInjectionTelemetry(int eventBufferSize, long bucketWidthMs, int bucketCount, Clock clock) {
        if (eventBufferSize <= 0) {
            throw new IllegalArgumentException("eventBufferSize must be positive");
        }
        if (bucketWidthMs <= 0) {
            throw new IllegalArgumentException("bucketWidthMs must be positive");
        }
        if (bucketCount <= 0) {
            throw new IllegalArgumentException("bucketCount must be positive");
        }
        this.eventBufferSize = eventBufferSize;
        this.bucketWidthMs = bucketWidthMs;
        this.bucketCount = bucketCount;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.buckets = new Bucket[bucketCount];
        for (int i = 0; i < bucketCount; i++) {
            this.buckets[i] = new Bucket();
        }
    }

    // ----- recording -----

    public void recordMatch(Rule rule, HttpMethod method, URI uri) {
        long now = clock.millis();
        Bucket b = bucketFor(now);
        b.matches.increment();
        b.matchesPerRule(safeName(rule)).increment();
        appendEvent(new FaultInjectionEvent(
                now,
                safeName(rule),
                method == null ? null : method.name(),
                uri == null ? null : uri.toString(),
                uri == null ? null : uri.getHost(),
                FaultInjectionEvent.Outcome.MATCH_NO_FIRE,
                null,
                0L,
                0));
    }

    public void recordTrigger(Rule rule, HttpMethod method, URI uri, FaultDecision decision) {
        long now = clock.millis();
        Bucket b = bucketFor(now);
        b.triggers.increment();
        b.triggersPerRule(safeName(rule)).increment();

        // The match counter for this trigger has already been incremented by the
        // preceding recordMatch call from the strategy, so we don't double-count.

        long delayMs =
                decision != null && decision.hasDelay() ? decision.delay().toMillis() : 0L;
        int errorStatus = decision != null && decision.hasError() ? decision.errorStatus() : 0;
        String faultType =
                rule != null && rule.getFault() != null ? rule.getFault().name() : null;

        appendEvent(new FaultInjectionEvent(
                now,
                safeName(rule),
                method == null ? null : method.name(),
                uri == null ? null : uri.toString(),
                uri == null ? null : uri.getHost(),
                FaultInjectionEvent.Outcome.FIRED,
                faultType,
                delayMs,
                errorStatus));
    }

    // ----- queries -----

    /**
     * @param limit upper bound on returned events; {@code <= 0} returns the full buffer
     * @return events newest-first
     */
    public List<FaultInjectionEvent> recentEvents(int limit) {
        List<FaultInjectionEvent> out = new ArrayList<>(events);
        Collections.reverse(out); // newest-first
        if (limit > 0 && out.size() > limit) {
            return out.subList(0, limit);
        }
        return out;
    }

    /**
     * @return time-series snapshots oldest-first, covering the last
     *         {@code bucketCount * bucketWidthMs} milliseconds. Stale slots whose
     *         start time falls outside that window are returned as zeroed buckets
     *         so the chart timeline is contiguous.
     */
    public List<TimeSeriesBucket> timeSeries() {
        long now = clock.millis();
        long currentSlotStart = (now / bucketWidthMs) * bucketWidthMs;
        long oldestStart = currentSlotStart - (long) (bucketCount - 1) * bucketWidthMs;

        List<TimeSeriesBucket> out = new ArrayList<>(bucketCount);
        for (int i = 0; i < bucketCount; i++) {
            long start = oldestStart + (long) i * bucketWidthMs;
            int idx = (int) Math.floorMod(start / bucketWidthMs, (long) bucketCount);
            Bucket slot = buckets[idx];
            if (slot.startEpochMs.get() == start) {
                out.add(slot.snapshot(start, bucketWidthMs));
            } else {
                out.add(TimeSeriesBucket.empty(start, bucketWidthMs));
            }
        }
        return out;
    }

    public void resetAll() {
        events.clear();
        eventCount.set(0);
        for (Bucket b : buckets) {
            b.reset();
        }
    }

    public void resetRule(String ruleName) {
        if (ruleName == null) {
            return;
        }
        events.removeIf(e -> ruleName.equals(e.ruleName()));
        // Recompute eventCount to stay consistent with the buffer's actual size.
        eventCount.set(events.size());
        for (Bucket b : buckets) {
            b.matchesByRule.remove(ruleName);
            b.triggersByRule.remove(ruleName);
        }
    }

    // ----- internals -----

    private Bucket bucketFor(long now) {
        long start = (now / bucketWidthMs) * bucketWidthMs;
        int idx = (int) Math.floorMod(start / bucketWidthMs, (long) bucketCount);
        Bucket b = buckets[idx];
        long current = b.startEpochMs.get();
        if (current != start) {
            // Lost CAS races still end up with the right startEpochMs because the
            // winner publishes it before either thread increments a counter.
            if (b.startEpochMs.compareAndSet(current, start)) {
                b.matches.reset();
                b.triggers.reset();
                b.matchesByRule.clear();
                b.triggersByRule.clear();
            }
        }
        return b;
    }

    private void appendEvent(FaultInjectionEvent event) {
        events.addLast(event);
        long size = eventCount.incrementAndGet();
        // Trim oldest entries until we're back at capacity. Concurrent writers may
        // push size briefly above the cap, but the loop guarantees convergence.
        while (size > eventBufferSize) {
            if (events.pollFirst() != null) {
                size = eventCount.decrementAndGet();
            } else {
                break;
            }
        }
    }

    private static String safeName(Rule rule) {
        if (rule == null || rule.getName() == null) {
            return "(unnamed)";
        }
        return rule.getName();
    }

    /** Single mutable slot in the circular bucket array. */
    private static final class Bucket {
        final AtomicLong startEpochMs = new AtomicLong(Long.MIN_VALUE);
        final LongAdder matches = new LongAdder();
        final LongAdder triggers = new LongAdder();
        final ConcurrentHashMap<String, LongAdder> matchesByRule = new ConcurrentHashMap<>();
        final ConcurrentHashMap<String, LongAdder> triggersByRule = new ConcurrentHashMap<>();

        LongAdder matchesPerRule(String name) {
            return matchesByRule.computeIfAbsent(name, k -> new LongAdder());
        }

        LongAdder triggersPerRule(String name) {
            return triggersByRule.computeIfAbsent(name, k -> new LongAdder());
        }

        void reset() {
            startEpochMs.set(Long.MIN_VALUE);
            matches.reset();
            triggers.reset();
            matchesByRule.clear();
            triggersByRule.clear();
        }

        TimeSeriesBucket snapshot(long expectedStart, long widthMs) {
            // Re-check startEpochMs after reading counters; if a concurrent rotation
            // happened we fall back to an empty bucket for this window.
            if (startEpochMs.get() != expectedStart) {
                return TimeSeriesBucket.empty(expectedStart, widthMs);
            }
            long matchTotal = matches.sum();
            long triggerTotal = triggers.sum();
            Map<String, long[]> perRule = new LinkedHashMap<>();
            matchesByRule.forEach((rule, adder) -> perRule.computeIfAbsent(rule, k -> new long[2])[0] = adder.sum());
            triggersByRule.forEach((rule, adder) -> perRule.computeIfAbsent(rule, k -> new long[2])[1] = adder.sum());
            return new TimeSeriesBucket(expectedStart, widthMs, matchTotal, triggerTotal, perRule);
        }
    }

    /**
     * Immutable view of a single time-series bucket, returned by {@link #timeSeries()}.
     *
     * @param startEpochMs        inclusive start time of the bucket
     * @param widthMs             bucket width in milliseconds
     * @param matches             total match count across all rules in this bucket
     * @param triggers            total trigger count across all rules in this bucket
     * @param perRule             rule-name → {matches, triggers}
     */
    public record TimeSeriesBucket(
            long startEpochMs, long widthMs, long matches, long triggers, Map<String, long[]> perRule) {
        static TimeSeriesBucket empty(long startEpochMs, long widthMs) {
            return new TimeSeriesBucket(startEpochMs, widthMs, 0L, 0L, Collections.emptyMap());
        }
    }
}

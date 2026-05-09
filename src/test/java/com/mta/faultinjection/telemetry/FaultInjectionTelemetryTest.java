package com.mta.faultinjection.telemetry;

import com.mta.faultinjection.config.FaultInjectionProperties.Rule;
import com.mta.faultinjection.core.FaultDecision;
import com.mta.faultinjection.core.FaultType;
import com.mta.faultinjection.telemetry.FaultInjectionTelemetry.TimeSeriesBucket;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class FaultInjectionTelemetryTest {

    private static final URI URI_A = URI.create("https://api.example.com/orders/42");

    @Test
    void ringBufferEvictsOldestWhenOverCapacity() {
        FaultInjectionTelemetry t = new FaultInjectionTelemetry(3, 1_000L, 4);

        for (int i = 0; i < 5; i++) {
            t.recordMatch(rule("r" + i), HttpMethod.GET, URI_A);
        }

        List<FaultInjectionEvent> events = t.recentEvents(0);
        assertThat(events).hasSize(3);
        // newest-first ordering
        assertThat(events.get(0).ruleName()).isEqualTo("r4");
        assertThat(events.get(1).ruleName()).isEqualTo("r3");
        assertThat(events.get(2).ruleName()).isEqualTo("r2");
    }

    @Test
    void firedEventsCarryFaultDetails() {
        FaultInjectionTelemetry t = new FaultInjectionTelemetry(10, 1_000L, 4);
        FaultDecision delayThenError = FaultDecision.delayThenError(Duration.ofMillis(75), 502, "boom");

        t.recordTrigger(rule("flaky"), HttpMethod.POST, URI_A, delayThenError);

        FaultInjectionEvent e = t.recentEvents(0).get(0);
        assertThat(e.outcome()).isEqualTo(FaultInjectionEvent.Outcome.FIRED);
        assertThat(e.delayMs()).isEqualTo(75L);
        assertThat(e.errorStatus()).isEqualTo(502);
        assertThat(e.method()).isEqualTo("POST");
        assertThat(e.host()).isEqualTo("api.example.com");
    }

    @Test
    void bucketsRotateAcrossWindow() {
        long bucketWidthMs = 1_000L;
        int bucketCount = 4;
        MutableClock clock = new MutableClock(0L);
        FaultInjectionTelemetry t = new FaultInjectionTelemetry(100, bucketWidthMs, bucketCount, clock);

        // bucket 0 → 2 matches
        clock.set(500L);
        t.recordMatch(rule("a"), HttpMethod.GET, URI_A);
        t.recordMatch(rule("a"), HttpMethod.GET, URI_A);

        // bucket 1 → 1 trigger
        clock.set(1_500L);
        t.recordTrigger(rule("a"), HttpMethod.GET, URI_A, FaultDecision.delay(Duration.ofMillis(10)));

        // bucket 3 → 1 match (skipping bucket 2 entirely)
        clock.set(3_500L);
        t.recordMatch(rule("a"), HttpMethod.GET, URI_A);

        List<TimeSeriesBucket> series = t.timeSeries();
        assertThat(series).hasSize(bucketCount);
        // ordered oldest → newest, contiguous
        for (int i = 1; i < series.size(); i++) {
            assertThat(series.get(i).startEpochMs())
                    .isEqualTo(series.get(i - 1).startEpochMs() + bucketWidthMs);
        }

        long totalMatches  = series.stream().mapToLong(TimeSeriesBucket::matches).sum();
        long totalTriggers = series.stream().mapToLong(TimeSeriesBucket::triggers).sum();
        assertThat(totalMatches).isEqualTo(3);
        assertThat(totalTriggers).isEqualTo(1);
    }

    @Test
    void staleBucketResetsBeforeNextWindow() {
        long bucketWidthMs = 1_000L;
        int bucketCount = 2;
        MutableClock clock = new MutableClock(0L);
        FaultInjectionTelemetry t = new FaultInjectionTelemetry(100, bucketWidthMs, bucketCount, clock);

        // Fill slot 0 at t=500, then advance long enough that the same physical
        // slot is reclaimed for a different start epoch.
        clock.set(500L);
        t.recordMatch(rule("a"), HttpMethod.GET, URI_A);

        clock.set(2_500L); // same physical slot, but a new logical bucket
        t.recordMatch(rule("a"), HttpMethod.GET, URI_A);

        // The series window is [now - count*width + 1 .. now]. The old t=0 bucket
        // is now outside the window, so we should see only the latest match.
        List<TimeSeriesBucket> series = t.timeSeries();
        long total = series.stream().mapToLong(TimeSeriesBucket::matches).sum();
        assertThat(total).isEqualTo(1);
    }

    @Test
    void resetAllClearsEverything() {
        FaultInjectionTelemetry t = new FaultInjectionTelemetry(10, 1_000L, 4);
        t.recordMatch(rule("a"), HttpMethod.GET, URI_A);
        t.recordTrigger(rule("a"), HttpMethod.GET, URI_A, FaultDecision.error(503, "x"));

        t.resetAll();

        assertThat(t.recentEvents(0)).isEmpty();
        assertThat(t.timeSeries().stream().mapToLong(TimeSeriesBucket::matches).sum()).isZero();
    }

    @Test
    void resetRuleScrubsTargetedRuleOnly() {
        FaultInjectionTelemetry t = new FaultInjectionTelemetry(10, 1_000L, 4);
        t.recordMatch(rule("a"), HttpMethod.GET, URI_A);
        t.recordMatch(rule("b"), HttpMethod.GET, URI_A);

        t.resetRule("a");

        List<FaultInjectionEvent> remaining = t.recentEvents(0);
        assertThat(remaining).hasSize(1);
        assertThat(remaining.get(0).ruleName()).isEqualTo("b");
    }

    @Test
    void concurrentRecordersStayConsistent() throws Exception {
        FaultInjectionTelemetry t = new FaultInjectionTelemetry(100_000, 60_000L, 4);
        int threads = 8;
        int perThread = 5_000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    for (int j = 0; j < perThread; j++) {
                        t.recordMatch(rule("r"), HttpMethod.GET, URI_A);
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        long total = t.timeSeries().stream().mapToLong(TimeSeriesBucket::matches).sum();
        assertThat(total).isEqualTo((long) threads * perThread);
    }

    private static Rule rule(String name) {
        Rule r = new Rule();
        r.setName(name);
        r.setFault(FaultType.DELAY);
        return r;
    }

    /** Adjustable Clock wrapper for tests. */
    private static final class MutableClock extends Clock {
        private volatile long millis;
        MutableClock(long initial) { this.millis = initial; }
        void set(long millis) { this.millis = millis; }
        @Override public long millis() { return millis; }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis); }
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
    }
}

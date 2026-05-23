package com.mta.faultinjection.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mta.faultinjection.core.FaultDecision;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

class FaultInjectionResilienceTelemetryTest {

    private static final Instant T0 = Instant.parse("2026-05-23T12:00:00Z");

    @Test
    void initialSnapshotsAreEmpty() {
        FaultInjectionResilienceTelemetry t =
                new FaultInjectionResilienceTelemetry(30_000L, 5, 30_000L, 100, Clock.fixed(T0, ZoneOffset.UTC));
        assertThat(t.retryObservations()).isEmpty();
        assertThat(t.circuitBreakerObservations()).isEmpty();
        assertThat(t.delayObservations()).isEmpty();
    }

    @Test
    void rejectsNonPositiveConfig() {
        assertThatThrownBy(() ->
                new FaultInjectionResilienceTelemetry(0L, 5, 30_000L, 100, Clock.systemUTC()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                new FaultInjectionResilienceTelemetry(30_000L, 0, 30_000L, 100, Clock.systemUTC()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                new FaultInjectionResilienceTelemetry(30_000L, 5, 0L, 100, Clock.systemUTC()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                new FaultInjectionResilienceTelemetry(30_000L, 5, 30_000L, 0, Clock.systemUTC()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Mutable clock idiom mirrored from {@code FaultInjectionTelemetryTest.MutableClock}. */
    static final class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advanceMillis(long ms) {
            this.now = now.plusMillis(ms);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}

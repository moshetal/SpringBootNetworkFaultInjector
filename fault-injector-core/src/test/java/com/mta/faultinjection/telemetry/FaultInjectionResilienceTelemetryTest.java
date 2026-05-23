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
    void errorTriggerOpensRetryObservationAndCountsSubsequentCalls() {
        MutableClock c = new MutableClock(T0);
        FaultInjectionResilienceTelemetry t =
                new FaultInjectionResilienceTelemetry(30_000L, 5, 30_000L, 100, c);

        URI uri = URI.create("https://api.example.com/orders/42");
        FaultDecision err = FaultDecision.error(503, "boom").withRuleName("flaky-orders");

        t.observeOutbound(HttpMethod.GET, uri, err);
        c.advanceMillis(2_000L);
        t.observeOutbound(HttpMethod.GET, uri, FaultDecision.pass());
        c.advanceMillis(2_000L);
        t.observeOutbound(HttpMethod.GET, uri, FaultDecision.pass());

        // window not yet expired → observation still open, finalized list empty
        assertThat(t.retryObservations()).isEmpty();

        c.advanceMillis(40_000L);
        List<RetryObservation> obs = t.retryObservations();
        assertThat(obs).hasSize(1);
        assertThat(obs.get(0).ruleName()).isEqualTo("flaky-orders");
        assertThat(obs.get(0).host()).isEqualTo("api.example.com");
        assertThat(obs.get(0).urlPath()).isEqualTo("/orders/42");
        assertThat(obs.get(0).observedRetries()).isEqualTo(2);
    }

    @Test
    void passOutboundWithNoOpenObservationIsANoop() {
        MutableClock c = new MutableClock(T0);
        FaultInjectionResilienceTelemetry t =
                new FaultInjectionResilienceTelemetry(30_000L, 5, 30_000L, 100, c);
        t.observeOutbound(HttpMethod.GET, URI.create("https://h/p"), FaultDecision.pass());
        assertThat(t.retryObservations()).isEmpty();
    }

    @Test
    void newErrorTriggerFinalizesPreviousObservationForSameTarget() {
        MutableClock c = new MutableClock(T0);
        FaultInjectionResilienceTelemetry t =
                new FaultInjectionResilienceTelemetry(30_000L, 5, 30_000L, 100, c);
        URI uri = URI.create("https://h/p");
        FaultDecision err = FaultDecision.error(503, "x").withRuleName("r");

        t.observeOutbound(HttpMethod.GET, uri, err);
        t.observeOutbound(HttpMethod.GET, uri, FaultDecision.pass()); // retry depth 1
        t.observeOutbound(HttpMethod.GET, uri, err); // restarts; finalizes prior

        assertThat(t.retryObservations()).hasSize(1);
        assertThat(t.retryObservations().get(0).observedRetries()).isEqualTo(1);
    }

    @Test
    void retryBufferIsBounded() {
        MutableClock c = new MutableClock(T0);
        FaultInjectionResilienceTelemetry t =
                new FaultInjectionResilienceTelemetry(1L, 5, 30_000L, 3, c);
        FaultDecision err = FaultDecision.error(503, "x").withRuleName("r");
        for (int i = 0; i < 10; i++) {
            t.observeOutbound(HttpMethod.GET, URI.create("https://h/p" + i), err);
            c.advanceMillis(5L);
        }
        assertThat(t.retryObservations()).hasSize(3);
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

package com.mta.faultinjection.interceptor;

import static org.assertj.core.api.Assertions.assertThat;

import com.mta.faultinjection.core.FaultDecision;
import com.mta.faultinjection.core.FaultDecisionStrategy;
import com.mta.faultinjection.telemetry.FaultInjectionResilienceTelemetry;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class FaultInjectionFilterTest {

    private final ClientRequest request = ClientRequest.create(HttpMethod.GET, URI.create("https://api.example.com/x"))
            .build();

    @Test
    void passDelegatesToNext() {
        FaultInjectionFilter filter = new FaultInjectionFilter(strategy(FaultDecision.pass()));
        AtomicBoolean called = new AtomicBoolean();
        ExchangeFunction next = req -> {
            called.set(true);
            return Mono.just(ClientResponse.create(org.springframework.http.HttpStatus.OK)
                    .build());
        };
        StepVerifier.create(filter.filter(request, next))
                .assertNext(resp -> assertThat(resp.statusCode().value()).isEqualTo(200))
                .verifyComplete();
        assertThat(called).isTrue();
    }

    @Test
    void delayInjectsDelayUsingVirtualTime() {
        FaultInjectionFilter filter = new FaultInjectionFilter(strategy(FaultDecision.delay(Duration.ofSeconds(5))));
        ExchangeFunction next = req -> Mono.just(
                ClientResponse.create(org.springframework.http.HttpStatus.OK).build());

        StepVerifier.withVirtualTime(() -> filter.filter(request, next))
                .expectSubscription()
                .expectNoEvent(Duration.ofSeconds(4))
                .thenAwait(Duration.ofSeconds(1))
                .assertNext(resp -> assertThat(resp.statusCode().value()).isEqualTo(200))
                .verifyComplete();
    }

    @Test
    void errorShortCircuitsWithoutCallingNext() {
        FaultInjectionFilter filter = new FaultInjectionFilter(strategy(FaultDecision.error(502, "nope")));
        AtomicBoolean called = new AtomicBoolean();
        ExchangeFunction next = req -> {
            called.set(true);
            return Mono.just(ClientResponse.create(org.springframework.http.HttpStatus.OK)
                    .build());
        };
        StepVerifier.create(filter.filter(request, next))
                .assertNext(resp -> assertThat(resp.statusCode().value()).isEqualTo(502))
                .verifyComplete();
        assertThat(called).isFalse();
    }

    @Test
    void observeOutboundIsCalledOnceForPass() {
        RecordingResilience r = new RecordingResilience();
        FaultInjectionFilter filter = new FaultInjectionFilter(strategy(FaultDecision.pass()), r);
        ExchangeFunction next = req -> Mono.just(
                ClientResponse.create(org.springframework.http.HttpStatus.OK).build());

        StepVerifier.create(filter.filter(request, next)).expectNextCount(1).verifyComplete();
        assertThat(r.observed).hasSize(1);
        assertThat(r.delays).isEmpty();
    }

    @Test
    void noteObservedDelayCalledWithLessThanInjectedWhenCallerTimesOut() {
        RecordingResilience r = new RecordingResilience();
        FaultDecision decision = FaultDecision.delay(Duration.ofSeconds(2)).withRuleName("slow");
        FaultInjectionFilter filter = new FaultInjectionFilter(strategy(decision), r);
        ExchangeFunction next = req -> Mono.just(
                ClientResponse.create(org.springframework.http.HttpStatus.OK).build());

        StepVerifier.create(filter.filter(request, next).timeout(Duration.ofMillis(100)))
                .expectError(TimeoutException.class)
                .verify();

        assertThat(r.delays).hasSize(1);
        RecordingResilience.DelayCall d = r.delays.get(0);
        assertThat(d.ruleName()).isEqualTo("slow");
        assertThat(d.injectedMs()).isEqualTo(2_000L);
        assertThat(d.observedMs()).isLessThan(2_000L);
        assertThat(d.completedSuccessfully()).isFalse();
    }

    @Test
    void noteObservedDelayCalledOnSuccessWhenDelayCompletes() {
        RecordingResilience r = new RecordingResilience();
        FaultDecision decision = FaultDecision.delay(Duration.ofMillis(5)).withRuleName("brief");
        FaultInjectionFilter filter = new FaultInjectionFilter(strategy(decision), r);
        ExchangeFunction next = req -> Mono.just(
                ClientResponse.create(org.springframework.http.HttpStatus.OK).build());

        StepVerifier.create(filter.filter(request, next)).expectNextCount(1).verifyComplete();
        assertThat(r.delays).hasSize(1);
        assertThat(r.delays.get(0).completedSuccessfully()).isTrue();
    }

    private static FaultDecisionStrategy strategy(FaultDecision d) {
        return (method, uri) -> d;
    }

    /** Hand-rolled test double; project does not use Mockito. */
    static final class RecordingResilience extends FaultInjectionResilienceTelemetry {
        record ObserveCall(HttpMethod method, URI uri, FaultDecision decision) {}

        record DelayCall(String ruleName, long injectedMs, long observedMs, boolean completedSuccessfully) {}

        final List<ObserveCall> observed = new ArrayList<>();
        final List<DelayCall> delays = new ArrayList<>();

        RecordingResilience() {
            super(30_000L, 5, 30_000L, 100, Clock.systemUTC());
        }

        @Override
        public void observeOutbound(HttpMethod method, URI uri, FaultDecision decision) {
            observed.add(new ObserveCall(method, uri, decision));
        }

        @Override
        public void noteObservedDelay(
                String ruleName, HttpMethod method, URI uri,
                long injectedDelayMs, long observedWaitMs, boolean completedSuccessfully) {
            delays.add(new DelayCall(ruleName, injectedDelayMs, observedWaitMs, completedSuccessfully));
        }
    }
}

package com.mta.faultinjection.interceptor;

import com.mta.faultinjection.core.FaultDecision;
import com.mta.faultinjection.core.FaultDecisionStrategy;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class FaultInjectionFilterTest {

    private final ClientRequest request = ClientRequest
            .create(HttpMethod.GET, URI.create("https://api.example.com/x"))
            .build();

    @Test
    void passDelegatesToNext() {
        FaultInjectionFilter filter = new FaultInjectionFilter(strategy(FaultDecision.pass()));
        AtomicBoolean called = new AtomicBoolean();
        ExchangeFunction next = req -> {
            called.set(true);
            return Mono.just(ClientResponse.create(org.springframework.http.HttpStatus.OK).build());
        };
        StepVerifier.create(filter.filter(request, next))
                .assertNext(resp -> assertThat(resp.statusCode().value()).isEqualTo(200))
                .verifyComplete();
        assertThat(called).isTrue();
    }

    @Test
    void delayInjectsDelayUsingVirtualTime() {
        FaultInjectionFilter filter = new FaultInjectionFilter(strategy(FaultDecision.delay(Duration.ofSeconds(5))));
        ExchangeFunction next = req -> Mono.just(ClientResponse.create(org.springframework.http.HttpStatus.OK).build());

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
            return Mono.just(ClientResponse.create(org.springframework.http.HttpStatus.OK).build());
        };
        StepVerifier.create(filter.filter(request, next))
                .assertNext(resp -> assertThat(resp.statusCode().value()).isEqualTo(502))
                .verifyComplete();
        assertThat(called).isFalse();
    }

    private static FaultDecisionStrategy strategy(FaultDecision d) {
        return (method, uri) -> d;
    }
}

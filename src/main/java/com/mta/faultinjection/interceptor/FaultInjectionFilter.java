package com.mta.faultinjection.interceptor;

import com.mta.faultinjection.core.FaultDecision;
import com.mta.faultinjection.core.FaultDecisionStrategy;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Objects;

/**
 * {@link ExchangeFilterFunction} used by WebClient to apply fault-injection
 * behavior to reactive HTTP exchanges.
 * <p>
 * Never blocks a reactive thread: delays are scheduled via
 * {@link Mono#delay(Duration)} rather than {@link Thread#sleep(long)}.
 */
public class FaultInjectionFilter implements ExchangeFilterFunction {

    private final FaultDecisionStrategy strategy;

    public FaultInjectionFilter(FaultDecisionStrategy strategy) {
        this.strategy = Objects.requireNonNull(strategy, "strategy");
    }

    @Override
    public Mono<ClientResponse> filter(ClientRequest request, ExchangeFunction next) {
        FaultDecision decision = strategy.decide(request.method(), request.url());
        if (decision == null || decision.instruction() == FaultDecision.Instruction.PASS) {
            return next.exchange(request);
        }

        Mono<Long> gate = decision.hasDelay()
                ? Mono.delay(decision.delay())
                : Mono.just(0L);

        if (decision.hasError()) {
            return gate.then(Mono.fromSupplier(() -> syntheticError(decision)));
        }
        return gate.then(next.exchange(request));
    }

    private static ClientResponse syntheticError(FaultDecision decision) {
        HttpStatus resolved = HttpStatus.resolve(decision.errorStatus());
        HttpStatusCode status = resolved != null ? resolved : HttpStatusCode.valueOf(decision.errorStatus());
        return ClientResponse.create(status)
                .body(decision.errorMessage() == null ? "" : decision.errorMessage())
                .build();
    }
}

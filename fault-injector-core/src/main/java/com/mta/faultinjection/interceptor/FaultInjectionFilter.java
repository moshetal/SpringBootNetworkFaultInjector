package com.mta.faultinjection.interceptor;

import com.mta.faultinjection.core.FaultDecision;
import com.mta.faultinjection.core.FaultDecisionStrategy;
import com.mta.faultinjection.telemetry.FaultInjectionResilienceTelemetry;
import java.time.Duration;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import reactor.core.publisher.Mono;

/**
 * {@link ExchangeFilterFunction} used by WebClient to apply fault-injection
 * behavior to reactive HTTP exchanges.
 * <p>
 * Never blocks a reactive thread: delays are scheduled via
 * {@link Mono#delay(Duration)} rather than {@link Thread#sleep(long)}.
 * Optionally reports observed-outbound and observed-delay samples to a
 * {@link FaultInjectionResilienceTelemetry} sink.
 */
public class FaultInjectionFilter implements ExchangeFilterFunction {

    private final FaultDecisionStrategy strategy;
    private final FaultInjectionResilienceTelemetry resilience;

    public FaultInjectionFilter(FaultDecisionStrategy strategy) {
        this(strategy, null);
    }

    public FaultInjectionFilter(FaultDecisionStrategy strategy, FaultInjectionResilienceTelemetry resilience) {
        this.strategy = Objects.requireNonNull(strategy, "strategy");
        this.resilience = resilience;
    }

    @Override
    public Mono<ClientResponse> filter(ClientRequest request, ExchangeFunction next) {
        FaultDecision decision = strategy.decide(request.method(), request.url());
        if (resilience != null) {
            resilience.observeOutbound(request.method(), request.url(), decision);
        }
        if (decision == null || decision.instruction() == FaultDecision.Instruction.PASS) {
            return next.exchange(request);
        }

        Mono<Long> gate = decision.hasDelay() ? Mono.delay(decision.delay()) : Mono.just(0L);
        Mono<ClientResponse> body = decision.hasError()
                ? gate.then(Mono.fromSupplier(() -> syntheticError(decision)))
                : gate.then(next.exchange(request));

        if (!decision.hasDelay() || resilience == null) {
            return body;
        }
        long startNanos = System.nanoTime();
        return body
                .doOnSuccess(r -> recordDelay(request, decision, startNanos, true))
                .doOnError(e -> recordDelay(request, decision, startNanos, false))
                .doOnCancel(() -> recordDelay(request, decision, startNanos, false));
    }

    private void recordDelay(ClientRequest request, FaultDecision decision, long startNanos, boolean success) {
        long observedMs = (System.nanoTime() - startNanos) / 1_000_000L;
        resilience.noteObservedDelay(
                decision.ruleName(), request.method(), request.url(),
                decision.delay().toMillis(), observedMs, success);
    }

    private static ClientResponse syntheticError(FaultDecision decision) {
        HttpStatus resolved = HttpStatus.resolve(decision.errorStatus());
        HttpStatusCode status = resolved != null ? resolved : HttpStatusCode.valueOf(decision.errorStatus());
        return ClientResponse.create(status)
                .body(decision.errorMessage() == null ? "" : decision.errorMessage())
                .build();
    }
}

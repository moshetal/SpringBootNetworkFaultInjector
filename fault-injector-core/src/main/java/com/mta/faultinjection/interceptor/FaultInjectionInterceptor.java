package com.mta.faultinjection.interceptor;

import com.mta.faultinjection.core.FaultDecision;
import com.mta.faultinjection.core.FaultDecisionStrategy;
import com.mta.faultinjection.telemetry.FaultInjectionResilienceTelemetry;
import com.mta.faultinjection.util.Sleeper;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Objects;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * {@link ClientHttpRequestInterceptor} shared by RestTemplate and RestClient.
 * <p>
 * Consults the configured {@link FaultDecisionStrategy} and either delays,
 * short-circuits with a synthetic error, or passes the request through.
 * Optionally reports observed-outbound and observed-delay samples to a
 * {@link FaultInjectionResilienceTelemetry} sink.
 */
public class FaultInjectionInterceptor implements ClientHttpRequestInterceptor {

    private final FaultDecisionStrategy strategy;
    private final Sleeper sleeper;
    private final FaultInjectionResilienceTelemetry resilience;

    public FaultInjectionInterceptor(FaultDecisionStrategy strategy) {
        this(strategy, Sleeper.DEFAULT, null);
    }

    public FaultInjectionInterceptor(FaultDecisionStrategy strategy, Sleeper sleeper) {
        this(strategy, sleeper, null);
    }

    public FaultInjectionInterceptor(
            FaultDecisionStrategy strategy, Sleeper sleeper, FaultInjectionResilienceTelemetry resilience) {
        this.strategy = Objects.requireNonNull(strategy, "strategy");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
        this.resilience = resilience; // nullable: feature is opt-in
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        FaultDecision decision = strategy.decide(request.getMethod(), request.getURI());
        if (resilience != null) {
            resilience.observeOutbound(request.getMethod(), request.getURI(), decision);
        }

        if (decision == null || decision.instruction() == FaultDecision.Instruction.PASS) {
            return execution.execute(request, body);
        }

        long startNanos = System.nanoTime();
        boolean completed = false;
        try {
            if (decision.hasDelay()) {
                sleepOrInterrupt(decision.delay().toMillis());
            }
            if (decision.hasError()) {
                completed = true;
                return new InjectedErrorResponse(decision.errorStatus(), decision.errorMessage());
            }
            ClientHttpResponse resp = execution.execute(request, body);
            completed = true;
            return resp;
        } finally {
            if (resilience != null && decision.hasDelay()) {
                long observedMs = (System.nanoTime() - startNanos) / 1_000_000L;
                resilience.noteObservedDelay(
                        decision.ruleName(),
                        request.getMethod(),
                        request.getURI(),
                        decision.delay().toMillis(),
                        observedMs,
                        completed);
            }
        }
    }

    private void sleepOrInterrupt(long millis) throws IOException {
        if (millis <= 0) {
            return;
        }
        try {
            sleeper.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            InterruptedIOException ioe = new InterruptedIOException("Fault-injection delay interrupted");
            ioe.initCause(e);
            throw ioe;
        }
    }
}

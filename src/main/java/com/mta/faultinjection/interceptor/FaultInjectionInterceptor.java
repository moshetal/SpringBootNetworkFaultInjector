package com.mta.faultinjection.interceptor;

import com.mta.faultinjection.core.FaultDecision;
import com.mta.faultinjection.core.FaultDecisionStrategy;
import com.mta.faultinjection.util.Sleeper;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Objects;

/**
 * {@link ClientHttpRequestInterceptor} shared by RestTemplate and RestClient.
 * <p>
 * Consults the configured {@link FaultDecisionStrategy} and either delays,
 * short-circuits with a synthetic error, or passes the request through.
 */
public class FaultInjectionInterceptor implements ClientHttpRequestInterceptor {

    private final FaultDecisionStrategy strategy;
    private final Sleeper sleeper;

    public FaultInjectionInterceptor(FaultDecisionStrategy strategy) {
        this(strategy, Sleeper.DEFAULT);
    }

    public FaultInjectionInterceptor(FaultDecisionStrategy strategy, Sleeper sleeper) {
        this.strategy = Objects.requireNonNull(strategy, "strategy");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
        FaultDecision decision = strategy.decide(request.getMethod(), request.getURI());
        if (decision == null || decision.instruction() == FaultDecision.Instruction.PASS) {
            return execution.execute(request, body);
        }

        if (decision.hasDelay()) {
            sleepOrInterrupt(decision.delay().toMillis());
        }
        if (decision.hasError()) {
            return new InjectedErrorResponse(decision.errorStatus(), decision.errorMessage());
        }
        return execution.execute(request, body);
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

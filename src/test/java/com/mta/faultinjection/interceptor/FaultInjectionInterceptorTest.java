package com.mta.faultinjection.interceptor;

import com.mta.faultinjection.core.FaultDecision;
import com.mta.faultinjection.core.FaultDecisionStrategy;
import com.mta.faultinjection.util.Sleeper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpResponse;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FaultInjectionInterceptorTest {

    private final HttpRequest request = new StubRequest(HttpMethod.GET, URI.create("https://api.example.com/x"));
    private final byte[] body = new byte[0];

    @Test
    void passDelegatesToExecution() throws Exception {
        FaultInjectionInterceptor interceptor = new FaultInjectionInterceptor(strategyReturning(FaultDecision.pass()));
        AtomicBoolean called = new AtomicBoolean();
        ClientHttpRequestExecution exec = (req, b) -> {
            called.set(true);
            return new MockClientHttpResponse(new byte[0], 200);
        };
        ClientHttpResponse resp = interceptor.intercept(request, body, exec);
        assertThat(called).isTrue();
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void delaySleepsThenExecutes() throws Exception {
        AtomicLong sleptFor = new AtomicLong();
        Sleeper capturing = millis -> sleptFor.set(millis);
        FaultInjectionInterceptor interceptor = new FaultInjectionInterceptor(
                strategyReturning(FaultDecision.delay(Duration.ofMillis(123))), capturing);
        AtomicBoolean called = new AtomicBoolean();
        ClientHttpRequestExecution exec = (req, b) -> {
            called.set(true);
            return new MockClientHttpResponse(new byte[0], 200);
        };
        interceptor.intercept(request, body, exec);
        assertThat(sleptFor).hasValue(123L);
        assertThat(called).isTrue();
    }

    @Test
    void errorReturnsSyntheticResponseWithoutCallingExecution() throws Exception {
        FaultInjectionInterceptor interceptor = new FaultInjectionInterceptor(
                strategyReturning(FaultDecision.error(504, "Gateway Ouch")));
        AtomicBoolean called = new AtomicBoolean();
        ClientHttpRequestExecution exec = (req, b) -> {
            called.set(true);
            return new MockClientHttpResponse(new byte[0], 200);
        };
        ClientHttpResponse resp = interceptor.intercept(request, body, exec);
        assertThat(called).isFalse();
        assertThat(resp.getStatusCode().value()).isEqualTo(504);
        String body = new String(resp.getBody().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(body).isEqualTo("Gateway Ouch");
    }

    @Test
    void interruptedSleepIsReThrownAndInterruptFlagPreserved() {
        Sleeper interrupting = millis -> {
            throw new InterruptedException("simulated");
        };
        FaultInjectionInterceptor interceptor = new FaultInjectionInterceptor(
                strategyReturning(FaultDecision.delay(Duration.ofMillis(10))), interrupting);
        try {
            assertThatThrownBy(() -> interceptor.intercept(request, body, (req, b) -> {
                throw new IllegalStateException("should not be called");
            })).isInstanceOf(InterruptedIOException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            // Clear the interrupt flag so it does not leak into other tests.
            Thread.interrupted();
        }
    }

    private static FaultDecisionStrategy strategyReturning(FaultDecision decision) {
        return (method, uri) -> decision;
    }

    private static final class StubRequest implements HttpRequest {
        private final HttpMethod method;
        private final URI uri;
        private final org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();

        StubRequest(HttpMethod method, URI uri) {
            this.method = method;
            this.uri = uri;
        }

        @Override
        public HttpMethod getMethod() {
            return method;
        }

        @Override
        public URI getURI() {
            return uri;
        }

        @Override
        public org.springframework.http.HttpHeaders getHeaders() {
            return headers;
        }
    }
}

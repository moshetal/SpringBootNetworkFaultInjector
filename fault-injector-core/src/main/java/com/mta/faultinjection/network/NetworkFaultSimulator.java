package com.mta.faultinjection.network;

import com.mta.faultinjection.core.NetworkFaultType;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * Factory that creates the {@link IOException} subclass corresponding to a
 * given {@link NetworkFaultType}.
 *
 * <p>The produced exceptions are indistinguishable from the exceptions a real
 * HTTP client would receive, so downstream error-handling code (retry logic,
 * circuit breakers, etc.) is exercised exactly as it would be under genuine
 * network failures.
 */
public final class NetworkFaultSimulator {

    private NetworkFaultSimulator() {}

    /**
     * Build the exception that should be thrown (sync path) or wrapped in
     * {@link reactor.core.publisher.Mono#error} (reactive path) for the given
     * {@code type}.
     *
     * @param type the network fault to simulate; must not be {@code null}
     * @param uri  the request URI — used to populate the exception message with
     *             the target host; may be {@code null}
     * @return a new {@link IOException} subclass, never {@code null}
     */
    public static IOException buildException(NetworkFaultType type, URI uri) {
        String host = (uri != null && uri.getHost() != null) ? uri.getHost() : "unknown";
        return switch (type) {
            case CONNECTION_REFUSED ->
                    new ConnectException("Connection refused (injected fault): " + host);
            case CONNECTION_TIMEOUT ->
                    new SocketTimeoutException("Connect timed out (injected fault): " + host);
            case READ_TIMEOUT ->
                    new SocketTimeoutException("Read timed out (injected fault): " + host);
            case CONNECTION_RESET ->
                    new SocketException("Connection reset (injected fault): " + host);
            case DNS_FAILURE ->
                    new UnknownHostException(host + ": Name or service not known (injected fault)");
        };
    }
}

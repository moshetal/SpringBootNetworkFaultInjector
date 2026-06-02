package com.mta.faultinjection.core;

/**
 * The specific kind of network-level failure to simulate when a rule's
 * {@link FaultType} is set to {@link FaultType#NETWORK}.
 *
 * <p>Each value maps to a well-known {@link java.io.IOException} subclass so
 * that callers see the same exception type they would see from a real failure:
 *
 * <ul>
 *   <li>{@link #CONNECTION_REFUSED}  → {@link java.net.ConnectException}</li>
 *   <li>{@link #CONNECTION_TIMEOUT}  → {@link java.net.SocketTimeoutException} (after delayMs)</li>
 *   <li>{@link #READ_TIMEOUT}        → {@link java.net.SocketTimeoutException} (after delayMs)</li>
 *   <li>{@link #CONNECTION_RESET}    → {@link java.net.SocketException}</li>
 *   <li>{@link #DNS_FAILURE}         → {@link java.net.UnknownHostException}</li>
 * </ul>
 *
 * <p>The two timeout variants ({@code CONNECTION_TIMEOUT}, {@code READ_TIMEOUT})
 * will sleep for the rule's {@code delayMs} before throwing, giving callers a
 * realistic wait before the exception arrives.  All other types throw
 * immediately.
 */
public enum NetworkFaultType {

    /** TCP connect attempt rejected immediately — server not listening on that port. */
    CONNECTION_REFUSED,

    /**
     * TCP handshake never completes.  The interceptor sleeps for {@code delayMs}
     * (simulating the OS connect-timeout interval) then throws
     * {@link java.net.SocketTimeoutException}.
     */
    CONNECTION_TIMEOUT,

    /**
     * Connection was established and the request was sent, but the server never
     * sent a response.  The interceptor sleeps for {@code delayMs} then throws
     * {@link java.net.SocketTimeoutException}.
     */
    READ_TIMEOUT,

    /** TCP RST received mid-conversation — server crashed or forcibly closed the socket. */
    CONNECTION_RESET,

    /** Hostname could not be resolved — simulates a DNS outage or misconfiguration. */
    DNS_FAILURE
}

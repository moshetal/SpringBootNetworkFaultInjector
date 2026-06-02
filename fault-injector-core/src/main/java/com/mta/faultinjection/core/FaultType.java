package com.mta.faultinjection.core;

/**
 * Kind of fault a rule injects when it fires.
 */
public enum FaultType {
    /** Inject artificial latency, then let the real request proceed. */
    DELAY,
    /** Short-circuit with a synthetic error response. */
    ERROR,
    /** Delay first, then short-circuit with a synthetic error response. */
    BOTH,
    /**
     * Throw a network-level {@link java.io.IOException} instead of forwarding the
     * request.  The specific exception type is controlled by the rule's
     * {@code networkFaultType} field (see {@link NetworkFaultType}).
     * <p>
     * Timeout variants ({@link NetworkFaultType#CONNECTION_TIMEOUT},
     * {@link NetworkFaultType#READ_TIMEOUT}) sleep for the rule's {@code delayMs}
     * before throwing, realistically simulating a hanging connection that eventually
     * times out.  All other variants throw immediately.
     */
    NETWORK
}

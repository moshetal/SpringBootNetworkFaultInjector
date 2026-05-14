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
    BOTH
}

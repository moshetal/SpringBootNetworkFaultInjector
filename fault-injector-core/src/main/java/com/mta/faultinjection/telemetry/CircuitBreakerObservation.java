package com.mta.faultinjection.telemetry;

/**
 * Finalized circuit-breaker observation: opens when N consecutive ERROR triggers
 * are recorded for (host, method); counts outbound calls during the window.
 * A low {@code postWindowCallCount} suggests the caller's CB opened.
 */
public record CircuitBreakerObservation(
        String host,
        String method,
        int threshold,
        long thresholdReachedAtMs,
        long windowMs,
        int postWindowCallCount) {}

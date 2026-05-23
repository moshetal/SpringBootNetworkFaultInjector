package com.mta.faultinjection.telemetry;

/**
 * Finalized retry-depth observation: starts when an ERROR-bearing fault fires,
 * counts subsequent outbound calls to the same target during the window.
 */
public record RetryObservation(
        String ruleName,
        String host,
        String method,
        String urlPath,
        long faultEpochMs,
        long windowMs,
        int observedRetries) {}

package com.mta.faultinjection.telemetry;

/**
 * Snapshot of a single delay-bearing fault.
 * <p>
 * {@code observedWaitMs < injectedDelayMs} ⇒ caller's timeout bailed early
 * (reactive path with {@code Mono.timeout(...)} or sync-side interrupt).
 */
public record DelayObservation(
        long timestampMs,
        String ruleName,
        String host,
        String method,
        long injectedDelayMs,
        long observedWaitMs,
        boolean completedSuccessfully) {}

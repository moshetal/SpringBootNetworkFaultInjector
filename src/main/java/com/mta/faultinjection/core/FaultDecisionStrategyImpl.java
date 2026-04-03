package com.mta.faultinjection.core;

/**
 * Central entry point for deciding whether to inject a delay or error for a request.
 * This module is intentionally minimal and can be extended with real decision logic.
 */
public class FaultDecisionStrategyImpl implements FaultDecisionStrategy {

    /**
     * Instruction returned from a decision strategy indicating how an outbound
     * call should be handled.
     */
    public enum Instruction {
        /** Instruct the client to inject an artificial latency before proceeding. */
        INJECT_DELAY,
        /** Instruct the client to fail fast and surface an error to the caller. */
        INJECT_ERROR,
        /** Proceed without modification. */
        PASS
    }

    /**
     * Decide whether to inject a delay, error, or pass the request through as-is.
     * <p>
     * Library users may override this behavior by supplying their own bean that implements
     * {@link FaultDecisionStrategy}. Interceptors/filters in this library will consult the
     * strategy prior to executing the request.
     *
     * @param url  full request URL when available (may be null)
     * @param host request host when available (may be null)
     * @return a non-null {@link Instruction} describing the desired behavior
     */
    @Override
    public Instruction decide(String url, String host) {
        // TODO: Implement real decision logic based on configuration, URL/host, etc.
        return Instruction.PASS; // No-op default for structure-only project
    }
}

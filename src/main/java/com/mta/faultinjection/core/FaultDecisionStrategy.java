package com.mta.faultinjection.core;

/**
 * Strategy interface that determines how to handle an outbound HTTP request
 * for fault injection purposes.
 *
 * Library users can provide their own implementation as a Spring bean to
 * customize behavior globally.
 */
public interface FaultDecisionStrategy {

    /**
     * Decide whether to inject a delay, fail the request, or pass through.
     *
     * This method is called by HTTP client interceptors/filters before the
     * actual request is executed.
     *
     * @param url  the full request URL when available (may be null)
     * @param host the request host when available (may be null)
     * @return an {@link FaultDecisionStrategyImpl.Instruction} indicating the desired action
     */
    FaultDecisionStrategyImpl.Instruction decide(String url, String host);
}

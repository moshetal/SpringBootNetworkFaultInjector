package com.mta.faultinjection.core;

import org.springframework.http.HttpMethod;

import java.net.URI;

/**
 * Strategy that decides how a given outbound HTTP request should be handled
 * for fault-injection purposes.
 * <p>
 * Library users can provide their own implementation as a Spring bean to
 * replace the default, config-driven behavior.
 */
public interface FaultDecisionStrategy {

    /**
     * Decide whether to inject a delay, short-circuit with an error, or pass
     * the request through unchanged.
     *
     * @param method the HTTP method (never {@code null})
     * @param uri    the full request URI (never {@code null})
     * @return a non-null {@link FaultDecision}
     */
    FaultDecision decide(HttpMethod method, URI uri);
}

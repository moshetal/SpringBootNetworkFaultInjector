package com.mta.faultinjection.protocol;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Opaque JSON snapshots from {@code FaultInjectorControlFacade} — includes
 * injection metrics, resilience block, time-series, and recent events.
 */
public record TelemetryBatch(
        String instanceId,
        String serviceName,
        long capturedAtMs,
        JsonNode config,
        JsonNode metrics,
        JsonNode timeseries,
        JsonNode events) {}

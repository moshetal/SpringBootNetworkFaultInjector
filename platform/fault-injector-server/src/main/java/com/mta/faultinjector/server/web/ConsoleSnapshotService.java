package com.mta.faultinjector.server.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.mta.faultinjection.protocol.TelemetryBatch;
import com.mta.faultinjector.server.registry.InstanceRegistry;
import com.mta.faultinjector.server.registry.InstanceRegistry.AgentInstance;
import com.mta.faultinjector.server.telemetry.TelemetryAggregator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Builds the read-model snapshots the console renders: the cluster overview
 * rows and the per-service {@code {config, metrics, timeseries, events}} bundle.
 * <p>
 * Shared by {@link ConsoleController} (REST, fallback path) and
 * {@link ConsoleBroadcaster} (WebSocket push) so both surface identical data.
 */
@Service
public class ConsoleSnapshotService {

    /** Number of recent decisions included in a pushed/served service snapshot. */
    public static final int DEFAULT_EVENT_LIMIT = 200;

    private final InstanceRegistry registry;
    private final TelemetryAggregator aggregator;

    public ConsoleSnapshotService(InstanceRegistry registry, TelemetryAggregator aggregator) {
        this.registry = registry;
        this.aggregator = aggregator;
    }

    /** One row per registered service: instance count and aggregate counters. */
    public List<Map<String, Object>> overview() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String service : registry.serviceNames()) {
            List<AgentInstance> insts = registry.findByService(service);
            List<TelemetryBatch> batches = batches(service, "all", insts);
            JsonNode metrics = aggregator.aggregateMetrics(batches);
            rows.add(Map.of(
                    "serviceName", service,
                    "instanceCount", insts.size(),
                    "matchCount", metrics.path("totals").path("matchCount").asLong(0),
                    "triggerCount", metrics.path("totals").path("triggerCount").asLong(0),
                    "resilienceSignals", resilienceCount(metrics)));
        }
        return rows;
    }

    /**
     * Full render bundle for a single service, aggregated across all instances
     * (scope {@code "all"}). Shape mirrors what the console's poll fetched from
     * the four per-service REST endpoints, so the same renderers apply.
     */
    public Map<String, Object> serviceSnapshot(String serviceName) {
        List<TelemetryBatch> batches = batches(serviceName, "all");
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("serviceName", serviceName);
        snapshot.put("config", aggregator.aggregateConfig(batches));
        snapshot.put("metrics", aggregator.aggregateMetrics(batches));
        snapshot.put("timeseries", aggregator.aggregateTimeSeries(batches));
        snapshot.put("events", aggregator.aggregateEvents(batches, DEFAULT_EVENT_LIMIT));
        return snapshot;
    }

    private List<TelemetryBatch> batches(String serviceName, String scope) {
        return batches(serviceName, scope, registry.findByService(serviceName));
    }

    private List<TelemetryBatch> batches(String serviceName, String scope, List<AgentInstance> insts) {
        List<String> ids = insts.stream().map(AgentInstance::instanceId).toList();
        return aggregator.batchesForService(serviceName, scope, ids);
    }

    static int resilienceCount(JsonNode metrics) {
        JsonNode r = metrics.path("resilience");
        return r.path("retryObservations").size()
                + r.path("circuitBreakerObservations").size()
                + r.path("delayObservations").size();
    }
}

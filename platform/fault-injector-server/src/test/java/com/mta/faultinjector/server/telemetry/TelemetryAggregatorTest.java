package com.mta.faultinjector.server.telemetry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mta.faultinjection.protocol.TelemetryBatch;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TelemetryAggregatorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final TelemetryAggregator aggregator = new TelemetryAggregator(mapper);

    @Test
    void mergesResilienceAcrossInstances() throws Exception {
        JsonNode m1 = mapper.readTree("""
            {"totals":{"matchCount":2,"triggerCount":1,"activeRules":1},
             "rules":[{"name":"r1","enabled":true,"matchCount":2,"triggerCount":1}],
             "resilience":{"config":{"retryWindowMs":30000},
               "retryObservations":[{"ruleName":"r1","observedRetries":2,"faultEpochMs":100}],
               "circuitBreakerObservations":[],"delayObservations":[]}}
            """);
        JsonNode m2 = mapper.readTree("""
            {"totals":{"matchCount":3,"triggerCount":2,"activeRules":1},
             "rules":[{"name":"r1","enabled":true,"matchCount":3,"triggerCount":2}],
             "resilience":{"config":{"retryWindowMs":30000},
               "retryObservations":[{"ruleName":"r1","observedRetries":0,"faultEpochMs":200}],
               "circuitBreakerObservations":[],"delayObservations":[]}}
            """);
        aggregator.ingest(new TelemetryBatch("a", "svc", 1, null, m1, null, null));
        aggregator.ingest(new TelemetryBatch("b", "svc", 1, null, m2, null, null));
        JsonNode merged = aggregator.aggregateMetrics(aggregator.batchesForService("svc", "all", List.of("a", "b")));
        assertThat(merged.path("totals").path("matchCount").asInt()).isEqualTo(5);
        assertThat(merged.path("resilience").path("retryObservations").size()).isEqualTo(2);
    }

    @Test
    void aggregateConfig_treatsMatchingRulesWithDifferentCountersAsConsistent() throws Exception {
        JsonNode c1 = mapper.readTree("""
            {"enabled":true,"defaults":{"delayMs":0},
             "rules":[{"name":"slow","enabled":true,"fault":"DELAY","matchCount":6,"triggerCount":4}]}
            """);
        JsonNode c2 = mapper.readTree("""
            {"enabled":true,"defaults":{"delayMs":0},
             "rules":[{"name":"slow","enabled":true,"fault":"DELAY","matchCount":2,"triggerCount":1}]}
            """);
        aggregator.ingest(new TelemetryBatch("a", "svc", 1, c1, null, null, null));
        aggregator.ingest(new TelemetryBatch("b", "svc", 1, c2, null, null, null));

        JsonNode merged = aggregator.aggregateConfig(aggregator.batchesForService("svc", "all", List.of("a", "b")));

        assertThat(merged.has("consistent")).isFalse();
        assertThat(merged.path("rules").get(0).path("matchCount").asInt()).isEqualTo(8);
        assertThat(merged.path("rules").get(0).path("triggerCount").asInt()).isEqualTo(5);
    }

    @Test
    void aggregateConfig_detectsRealConfigDrift() throws Exception {
        JsonNode c1 = mapper.readTree("""
            {"enabled":true,"rules":[{"name":"slow","enabled":true,"matchCount":1,"triggerCount":0}]}
            """);
        JsonNode c2 = mapper.readTree("""
            {"enabled":true,"rules":[{"name":"slow","enabled":false,"matchCount":1,"triggerCount":0}]}
            """);
        aggregator.ingest(new TelemetryBatch("a", "svc", 1, c1, null, null, null));
        aggregator.ingest(new TelemetryBatch("b", "svc", 1, c2, null, null, null));

        JsonNode merged = aggregator.aggregateConfig(aggregator.batchesForService("svc", "all", List.of("a", "b")));

        assertThat(merged.path("consistent").asBoolean()).isFalse();
        assertThat(merged.path("instances").size()).isEqualTo(2);
    }

    @Test
    void aggregateConfig_singleBatchReturnsConfigUnchanged() throws Exception {
        JsonNode c1 = mapper.readTree("""
            {"enabled":true,"rules":[{"name":"slow","enabled":true,"matchCount":3,"triggerCount":1}]}
            """);
        aggregator.ingest(new TelemetryBatch("a", "svc", 1, c1, null, null, null));

        JsonNode merged = aggregator.aggregateConfig(aggregator.batchesForService("svc", "all", List.of("a")));

        assertThat(merged).isEqualTo(c1);
    }
}

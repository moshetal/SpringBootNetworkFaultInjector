package com.mta.faultinjector.server.telemetry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mta.faultinjection.protocol.TelemetryBatch;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class TelemetryAggregator {

    private static final String RULES = "rules";
    private static final String NAME = "name";
    private static final String MATCH_COUNT = "matchCount";
    private static final String TRIGGER_COUNT = "triggerCount";

    private final ObjectMapper mapper;
    private final Map<String, TelemetryBatch> latestByInstance = new ConcurrentHashMap<>();

    public TelemetryAggregator(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public void ingest(TelemetryBatch batch) {
        latestByInstance.put(batch.instanceId(), batch);
    }

    public void removeInstance(String instanceId) {
        latestByInstance.remove(instanceId);
    }

    public List<TelemetryBatch> batchesForService(String serviceName, String scope, List<String> instanceIds) {
        List<TelemetryBatch> batches = new ArrayList<>();
        for (String id : instanceIds) {
            if (!"all".equals(scope) && !scope.equals(id)) {
                continue;
            }
            TelemetryBatch b = latestByInstance.get(id);
            if (b != null && serviceName.equals(b.serviceName())) {
                batches.add(b);
            }
        }
        return batches;
    }

    public JsonNode aggregateConfig(List<TelemetryBatch> batches) {
        if (batches.isEmpty()) {
            return mapper.createObjectNode();
        }
        if (batches.size() == 1) {
            return batches.get(0).config();
        }
        boolean consistent = true;
        JsonNode firstNorm = normalizeConfigForComparison(batches.get(0).config());
        for (int i = 1; i < batches.size(); i++) {
            if (!firstNorm.equals(normalizeConfigForComparison(batches.get(i).config()))) {
                consistent = false;
                break;
            }
        }
        if (consistent) {
            ObjectNode merged = batches.get(0).config().deepCopy();
            mergeConfigRuleCounters(batches, merged);
            return merged;
        }
        ObjectNode out = mapper.createObjectNode();
        out.put("consistent", false);
        ArrayNode instances = mapper.createArrayNode();
        for (TelemetryBatch b : batches) {
            ObjectNode row = mapper.createObjectNode();
            row.put("instanceId", b.instanceId());
            row.set("config", b.config());
            instances.add(row);
        }
        out.set("instances", instances);
        return out;
    }

    public JsonNode aggregateMetrics(List<TelemetryBatch> batches) {
        if (batches.isEmpty()) {
            return emptyMetrics();
        }
        if (batches.size() == 1) {
            return batches.get(0).metrics();
        }

        Map<String, ObjectNode> ruleRows = new LinkedHashMap<>();
        long totalMatch = 0;
        long totalTrigger = 0;
        long activeRules = 0;

        List<JsonNode> retryObs = new ArrayList<>();
        List<JsonNode> cbObs = new ArrayList<>();
        List<JsonNode> delayObs = new ArrayList<>();
        Map<String, JsonNode> resilienceConfigs = new HashMap<>();

        for (TelemetryBatch b : batches) {
            JsonNode metrics = b.metrics();
            if (metrics == null) continue;
            JsonNode totals = metrics.get("totals");
            if (totals != null) {
                totalMatch += totals.path("matchCount").asLong(0);
                totalTrigger += totals.path("triggerCount").asLong(0);
                activeRules += totals.path("activeRules").asLong(0);
            }
            JsonNode rules = metrics.get("rules");
            if (rules != null && rules.isArray()) {
                for (JsonNode rule : rules) {
                    String name = rule.path("name").asText("(unnamed)");
                    ObjectNode row = ruleRows.computeIfAbsent(name, n -> {
                        ObjectNode r = mapper.createObjectNode();
                        r.put("name", n);
                        r.put("enabled", rule.path("enabled").asBoolean(true));
                        r.put("matchCount", 0L);
                        r.put("triggerCount", 0L);
                        return r;
                    });
                    row.put("matchCount", row.get("matchCount").asLong() + rule.path("matchCount").asLong(0));
                    row.put("triggerCount", row.get("triggerCount").asLong() + rule.path("triggerCount").asLong(0));
                }
            }
            JsonNode resilience = metrics.get("resilience");
            if (resilience != null) {
                resilienceConfigs.put(b.instanceId(), resilience.path("config"));
                appendTagged(retryObs, b.instanceId(), resilience.get("retryObservations"), "faultEpochMs");
                appendTagged(cbObs, b.instanceId(), resilience.get("circuitBreakerObservations"), "thresholdReachedAtMs");
                appendTagged(delayObs, b.instanceId(), resilience.get("delayObservations"), "timestampMs");
            }
        }

        ObjectNode out = mapper.createObjectNode();
        ArrayNode rulesArr = mapper.createArrayNode();
        ruleRows.values().forEach(rulesArr::add);
        out.set("rules", rulesArr);
        ObjectNode totals = mapper.createObjectNode();
        totals.put("matchCount", totalMatch);
        totals.put("triggerCount", totalTrigger);
        totals.put("activeRules", activeRules);
        out.set("totals", totals);

        ObjectNode resilience = mapper.createObjectNode();
        resilience.set("config", mergeResilienceConfig(resilienceConfigs));
        resilience.set("retryObservations", sortNewest(retryObs, "faultEpochMs"));
        resilience.set("circuitBreakerObservations", sortNewest(cbObs, "thresholdReachedAtMs"));
        resilience.set("delayObservations", sortNewest(delayObs, "timestampMs"));
        out.set("resilience", resilience);
        return out;
    }

    public JsonNode aggregateTimeSeries(List<TelemetryBatch> batches) {
        if (batches.isEmpty()) {
            return mapper.createObjectNode().set("buckets", mapper.createArrayNode());
        }
        Map<Long, ObjectNode> merged = new HashMap<>();
        for (TelemetryBatch b : batches) {
            JsonNode ts = b.timeseries();
            if (ts == null) continue;
            JsonNode buckets = ts.get("buckets");
            if (buckets == null || !buckets.isArray()) continue;
            for (JsonNode bucket : buckets) {
                long start = bucket.path("startEpochMs").asLong();
                ObjectNode target = merged.computeIfAbsent(start, k -> {
                    ObjectNode n = mapper.createObjectNode();
                    n.put("startEpochMs", start);
                    n.put("widthMs", bucket.path("widthMs").asLong());
                    n.put("matches", 0L);
                    n.put("triggers", 0L);
                    n.set("perRule", mapper.createObjectNode());
                    return n;
                });
                target.put("matches", target.get("matches").asLong() + bucket.path("matches").asLong(0));
                target.put("triggers", target.get("triggers").asLong() + bucket.path("triggers").asLong(0));
            }
        }
        ArrayNode arr = mapper.createArrayNode();
        merged.values().stream()
                .sorted(Comparator.comparing(n -> n.get("startEpochMs").asLong()))
                .forEach(arr::add);
        ObjectNode out = mapper.createObjectNode();
        out.set("buckets", arr);
        out.set("ruleNames", mapper.createArrayNode());
        return out;
    }

    public JsonNode aggregateEvents(List<TelemetryBatch> batches, int limit) {
        List<JsonNode> all = new ArrayList<>();
        for (TelemetryBatch b : batches) {
            JsonNode eventsWrapper = b.events();
            if (eventsWrapper == null) continue;
            JsonNode events = eventsWrapper.get("events");
            if (events == null || !events.isArray()) continue;
            for (JsonNode e : events) {
                ObjectNode tagged = e.deepCopy();
                tagged.put("instanceId", b.instanceId());
                tagged.put("serviceName", b.serviceName());
                all.add(tagged);
            }
        }
        all.sort(Comparator.comparing(n -> -n.path("timestampMs").asLong()));
        ArrayNode arr = mapper.createArrayNode();
        int cap = limit > 0 ? Math.min(limit, all.size()) : all.size();
        for (int i = 0; i < cap; i++) {
            arr.add(all.get(i));
        }
        ObjectNode out = mapper.createObjectNode();
        out.put("count", arr.size());
        out.set("events", arr);
        return out;
    }

    private JsonNode normalizeConfigForComparison(JsonNode config) {
        if (config == null || !config.isObject()) {
            return config;
        }
        ObjectNode copy = config.deepCopy();
        JsonNode rules = copy.get(RULES);
        if (rules != null && rules.isArray()) {
            List<JsonNode> normalized = new ArrayList<>();
            for (JsonNode rule : rules) {
                ObjectNode ruleCopy = rule.deepCopy();
                ruleCopy.remove(MATCH_COUNT);
                ruleCopy.remove(TRIGGER_COUNT);
                normalized.add(ruleCopy);
            }
            normalized.sort(Comparator.comparing(n -> n.path(NAME).asText("")));
            ArrayNode sorted = mapper.createArrayNode();
            normalized.forEach(sorted::add);
            copy.set(RULES, sorted);
        }
        return copy;
    }

    private void mergeConfigRuleCounters(List<TelemetryBatch> batches, ObjectNode config) {
        JsonNode rules = config.get(RULES);
        if (rules == null || !rules.isArray()) {
            return;
        }
        Map<String, long[]> sums = new HashMap<>();
        for (TelemetryBatch batch : batches) {
            JsonNode batchRules = batch.config().path(RULES);
            if (!batchRules.isArray()) {
                continue;
            }
            for (JsonNode rule : batchRules) {
                String name = rule.path(NAME).asText("(unnamed)");
                long[] totals = sums.computeIfAbsent(name, ignored -> new long[2]);
                totals[0] += rule.path(MATCH_COUNT).asLong(0);
                totals[1] += rule.path(TRIGGER_COUNT).asLong(0);
            }
        }
        ArrayNode mergedRules = mapper.createArrayNode();
        for (JsonNode rule : rules) {
            ObjectNode ruleCopy = rule.deepCopy();
            String name = rule.path(NAME).asText("(unnamed)");
            long[] totals = sums.get(name);
            if (totals != null) {
                ruleCopy.put(MATCH_COUNT, totals[0]);
                ruleCopy.put(TRIGGER_COUNT, totals[1]);
            }
            mergedRules.add(ruleCopy);
        }
        config.set(RULES, mergedRules);
    }

    private JsonNode emptyMetrics() {
        ObjectNode out = mapper.createObjectNode();
        out.set("rules", mapper.createArrayNode());
        ObjectNode totals = mapper.createObjectNode();
        totals.put("matchCount", 0);
        totals.put("triggerCount", 0);
        totals.put("activeRules", 0);
        out.set("totals", totals);
        ObjectNode resilience = mapper.createObjectNode();
        resilience.set("config", mapper.createObjectNode());
        resilience.set("retryObservations", mapper.createArrayNode());
        resilience.set("circuitBreakerObservations", mapper.createArrayNode());
        resilience.set("delayObservations", mapper.createArrayNode());
        out.set("resilience", resilience);
        return out;
    }

    private void appendTagged(List<JsonNode> target, String instanceId, JsonNode arr, String sortField) {
        if (arr == null || !arr.isArray()) return;
        for (JsonNode n : arr) {
            ObjectNode copy = n.deepCopy();
            copy.put("instanceId", instanceId);
            target.add(copy);
        }
    }

    private ArrayNode sortNewest(List<JsonNode> nodes, String field) {
        nodes.sort(Comparator.comparing(n -> -n.path(field).asLong()));
        ArrayNode arr = mapper.createArrayNode();
        nodes.forEach(arr::add);
        return arr;
    }

    private JsonNode mergeResilienceConfig(Map<String, JsonNode> perInstance) {
        if (perInstance.isEmpty()) {
            return mapper.createObjectNode();
        }
        Iterator<JsonNode> it = perInstance.values().iterator();
        JsonNode first = it.next();
        boolean mixed = false;
        while (it.hasNext()) {
            if (!first.equals(it.next())) {
                mixed = true;
                break;
            }
        }
        if (!mixed) {
            return first;
        }
        ObjectNode out = mapper.createObjectNode();
        out.put("mixed", true);
        ObjectNode per = mapper.createObjectNode();
        perInstance.forEach(per::set);
        out.set("perInstance", per);
        return out;
    }
}

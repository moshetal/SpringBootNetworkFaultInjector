package com.mta.faultinjector.server.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mta.faultinjection.protocol.CommandResult;
import com.mta.faultinjection.protocol.CommandType;
import com.mta.faultinjection.protocol.TelemetryBatch;
import com.mta.faultinjector.server.command.CommandRouter;
import com.mta.faultinjector.server.registry.InstanceRegistry;
import com.mta.faultinjector.server.registry.InstanceRegistry.AgentInstance;
import com.mta.faultinjector.server.telemetry.TelemetryAggregator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("${fault.injection.server.console.path:/console}/api")
public class ConsoleController {

    private final InstanceRegistry registry;
    private final TelemetryAggregator aggregator;
    private final CommandRouter commandRouter;
    private final ObjectMapper mapper;
    private final JdbcTemplate jdbc;
    private final ConsoleSnapshotService snapshots;

    public ConsoleController(
            InstanceRegistry registry,
            TelemetryAggregator aggregator,
            CommandRouter commandRouter,
            ObjectMapper mapper,
            JdbcTemplate jdbc,
            ConsoleSnapshotService snapshots) {
        this.registry = registry;
        this.aggregator = aggregator;
        this.commandRouter = commandRouter;
        this.mapper = mapper;
        this.jdbc = jdbc;
        this.snapshots = snapshots;
    }

    @GetMapping("/overview")
    public List<Map<String, Object>> overview() {
        return snapshots.overview();
    }

    @GetMapping("/services")
    public List<String> services() {
        return registry.serviceNames();
    }

    @GetMapping("/services/{serviceName}/instances")
    public List<Map<String, Object>> instances(@PathVariable String serviceName) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (AgentInstance inst : registry.findByService(serviceName)) {
            out.add(Map.of(
                    "instanceId", inst.instanceId(),
                    "serviceName", inst.serviceName(),
                    "lastSeen", inst.lastSeen().toString(),
                    "localUiPath", inst.localUiPath() == null ? "" : inst.localUiPath()));
        }
        return out;
    }

    @GetMapping("/services/{serviceName}/config")
    public JsonNode config(@PathVariable String serviceName, @RequestParam(defaultValue = "all") String scope) {
        return aggregator.aggregateConfig(batches(serviceName, scope));
    }

    @PostMapping("/services/{serviceName}/enabled")
    public CommandResult setEnabled(
            @PathVariable String serviceName,
            @RequestParam(defaultValue = "all") String scope,
            @RequestBody JsonNode body) {
        return commandRouter.dispatch(serviceName, scope, CommandType.SET_ENABLED, body);
    }

    @PutMapping("/services/{serviceName}/defaults")
    public CommandResult updateDefaults(
            @PathVariable String serviceName,
            @RequestParam(defaultValue = "all") String scope,
            @RequestBody JsonNode body) {
        return commandRouter.dispatch(serviceName, scope, CommandType.UPDATE_DEFAULTS, body);
    }

    @PostMapping("/services/{serviceName}/rules")
    public CommandResult addRule(
            @PathVariable String serviceName,
            @RequestParam(defaultValue = "all") String scope,
            @RequestBody JsonNode body) {
        return commandRouter.dispatch(serviceName, scope, CommandType.ADD_RULE, body);
    }

    @PutMapping("/services/{serviceName}/rules/{name}")
    public CommandResult updateRule(
            @PathVariable String serviceName,
            @PathVariable String name,
            @RequestParam(defaultValue = "all") String scope,
            @RequestBody JsonNode body) {
        ObjectNode payload = body.isObject() ? ((ObjectNode) body).deepCopy() : mapper.createObjectNode();
        payload.put("name", name);
        return commandRouter.dispatch(serviceName, scope, CommandType.UPDATE_RULE, payload);
    }

    @DeleteMapping("/services/{serviceName}/rules/{name}")
    public CommandResult deleteRule(
            @PathVariable String serviceName,
            @PathVariable String name,
            @RequestParam(defaultValue = "all") String scope) {
        return commandRouter.dispatch(serviceName, scope, CommandType.DELETE_RULE, mapper.createObjectNode().put("name", name));
    }

    @PostMapping("/services/{serviceName}/rules/{name}/enabled")
    public CommandResult setRuleEnabled(
            @PathVariable String serviceName,
            @PathVariable String name,
            @RequestParam(defaultValue = "all") String scope,
            @RequestBody JsonNode body) {
        ObjectNode payload = body.isObject() ? ((ObjectNode) body).deepCopy() : mapper.createObjectNode();
        payload.put("name", name);
        return commandRouter.dispatch(serviceName, scope, CommandType.SET_RULE_ENABLED, payload);
    }

    @GetMapping("/services/{serviceName}/metrics")
    public JsonNode metrics(@PathVariable String serviceName, @RequestParam(defaultValue = "all") String scope) {
        return aggregator.aggregateMetrics(batches(serviceName, scope));
    }

    @GetMapping("/services/{serviceName}/metrics/timeseries")
    public JsonNode timeSeries(@PathVariable String serviceName, @RequestParam(defaultValue = "all") String scope) {
        return aggregator.aggregateTimeSeries(batches(serviceName, scope));
    }

    @GetMapping("/services/{serviceName}/events")
    public JsonNode events(
            @PathVariable String serviceName,
            @RequestParam(defaultValue = "all") String scope,
            @RequestParam(defaultValue = "200") int limit) {
        return aggregator.aggregateEvents(batches(serviceName, scope), limit);
    }

    @PostMapping("/services/{serviceName}/metrics/reset")
    public CommandResult resetMetrics(
            @PathVariable String serviceName,
            @RequestParam(defaultValue = "all") String scope,
            @RequestBody(required = false) JsonNode body) {
        return commandRouter.dispatch(serviceName, scope, CommandType.RESET_METRICS, body);
    }

    @GetMapping("/services/{serviceName}/export")
    public ResponseEntity<?> export(
            @PathVariable String serviceName,
            @RequestParam(defaultValue = "all") String scope,
            @RequestParam(defaultValue = "json") String format) {
        if ("csv".equalsIgnoreCase(format)) {
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("text/csv"))
                    .body(eventsCsv(batches(serviceName, scope)));
        }
        if ("json".equalsIgnoreCase(format)) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("config", aggregator.aggregateConfig(batches(serviceName, scope)));
            body.put("metrics", aggregator.aggregateMetrics(batches(serviceName, scope)));
            body.put("timeseries", aggregator.aggregateTimeSeries(batches(serviceName, scope)));
            body.put("events", aggregator.aggregateEvents(batches(serviceName, scope), 0).get("events"));
            return ResponseEntity.ok(body);
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported format: " + format);
    }

    private List<TelemetryBatch> batches(String serviceName, String scope) {
        List<String> ids = registry.findByService(serviceName).stream()
                .map(AgentInstance::instanceId)
                .toList();
        return aggregator.batchesForService(serviceName, scope, ids);
    }

    private String eventsCsv(List<TelemetryBatch> batches) {
        StringBuilder sb = new StringBuilder();
        sb.append("timestampMs,instanceId,ruleName,outcome,method,host,url,faultType,delayMs,errorStatus\n");
        JsonNode events = aggregator.aggregateEvents(batches, 0).get("events");
        if (events != null && events.isArray()) {
            for (JsonNode e : events) {
                sb.append(e.path("timestampMs").asLong()).append(',');
                sb.append(csv(e.path("instanceId").asText(""))).append(',');
                sb.append(csv(e.path("ruleName").asText(""))).append(',');
                sb.append(csv(e.path("outcome").asText(""))).append(',');
                sb.append(csv(e.path("method").asText(""))).append(',');
                sb.append(csv(e.path("host").asText(""))).append(',');
                sb.append(csv(e.path("url").asText(""))).append(',');
                sb.append(csv(e.path("faultType").asText(""))).append(',');
                sb.append(e.path("delayMs").asLong(0)).append(',');
                sb.append(e.path("errorStatus").asInt(0)).append('\n');
            }
        }
        return sb.toString();
    }

    private static String csv(String v) {
        if (v == null) return "";
        if (v.contains(",") || v.contains("\"")) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }
}

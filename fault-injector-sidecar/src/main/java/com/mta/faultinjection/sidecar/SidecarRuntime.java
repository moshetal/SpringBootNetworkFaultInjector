package com.mta.faultinjection.sidecar;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mta.faultinjection.config.FaultInjectionProperties;
import com.mta.faultinjection.core.FaultDecision;
import com.mta.faultinjection.core.FaultDecisionStrategyImpl;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpMethod;

public final class SidecarRuntime {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SidecarRuntime() {}

    public static void run(InputStream in, OutputStream out, FaultInjectionProperties props) throws Exception {
        FaultDecisionStrategyImpl strategy = new FaultDecisionStrategyImpl(props);
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));

        write(writer, MAPPER.createObjectNode().put("op", "ready"));
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }

            JsonNode command = null;
            try {
                command = MAPPER.readTree(line);
                if (command == null || !command.isObject()) {
                    throw new IllegalArgumentException("command must be a JSON object");
                }
                if (handle(command, writer, strategy, props)) {
                    return;
                }
            } catch (Exception e) {
                ObjectNode error = MAPPER.createObjectNode();
                if (command != null && command.has("id")) {
                    error.set("id", command.get("id"));
                }
                error.put("error", errorMessage(e));
                write(writer, error);
            }
        }
    }

    private static boolean handle(
            JsonNode command,
            BufferedWriter writer,
            FaultDecisionStrategyImpl strategy,
            FaultInjectionProperties props)
            throws Exception {
        String id = requiredText(command, "id");
        String op = requiredText(command, "op");
        ObjectNode response = MAPPER.createObjectNode().put("id", id);

        switch (op) {
            case "decide" -> {
                HttpMethod method;
                URI uri;
                try {
                    method = HttpMethod.valueOf(requiredText(command, "method"));
                } catch (Exception e) {
                    throw new IllegalArgumentException("invalid method", e);
                }
                try {
                    uri = URI.create(requiredText(command, "url"));
                } catch (Exception e) {
                    throw new IllegalArgumentException("invalid url", e);
                }
                addDecision(response, strategy.decide(method, uri));
            }
            case "metrics" -> {
                ObjectNode rules = response.putObject("rules");
                strategy.metricsSnapshot().forEach((name, metrics) -> {
                    ObjectNode rule = rules.putObject(name);
                    rule.put("matchCount", metrics.matchCount());
                    rule.put("triggerCount", metrics.triggerCount());
                });
            }
            case "setEnabled" -> {
                JsonNode enabled = command.get("enabled");
                if (enabled == null || !enabled.isBoolean()) {
                    throw new IllegalArgumentException("enabled must be a boolean");
                }
                props.setEnabled(enabled.booleanValue());
                response.put("ok", true);
            }
            case "shutdown" -> {
                response.put("ok", true);
                write(writer, response);
                return true;
            }
            default -> throw new IllegalArgumentException("unknown op: " + op);
        }

        write(writer, response);
        return false;
    }

    private static void addDecision(ObjectNode response, FaultDecision decision) {
        response.put("instruction", decision.instruction().name());
        if (decision.hasDelay()) {
            response.put("delayMs", decision.delay().toMillis());
        }
        if (decision.hasError()) {
            response.put("errorStatus", decision.errorStatus());
            response.put("errorMessage", decision.errorMessage());
        }
        if (decision.ruleName() != null) {
            response.put("ruleName", decision.ruleName());
        }
    }

    private static String requiredText(JsonNode command, String field) {
        JsonNode value = command.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException("missing or invalid " + field);
        }
        return value.textValue();
    }

    private static String errorMessage(Exception e) {
        return e.getMessage() == null || e.getMessage().isBlank() ? e.getClass().getSimpleName() : e.getMessage();
    }

    private static void write(BufferedWriter writer, JsonNode value) throws Exception {
        writer.write(MAPPER.writeValueAsString(value));
        writer.newLine();
        writer.flush();
    }
}

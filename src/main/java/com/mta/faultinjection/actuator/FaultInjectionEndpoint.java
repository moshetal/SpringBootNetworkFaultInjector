package com.mta.faultinjection.actuator;

import com.mta.faultinjection.config.FaultInjectionProperties;
import com.mta.faultinjection.config.FaultInjectionProperties.Rule;
import com.mta.faultinjection.core.FaultDecisionStrategy;
import com.mta.faultinjection.core.FaultDecisionStrategyImpl;
import com.mta.faultinjection.core.FaultDecisionStrategyImpl.RuleMetrics;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.lang.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Actuator endpoint (id = "faultinjector") that exposes the live state of the
 * fault-injection subsystem and a small set of safe, targeted mutations for
 * runtime control.
 * <p>
 * Supported write actions:
 * <ul>
 *     <li>{@code {"action":"enable"}} / {@code {"action":"disable"}} — global kill-switch</li>
 *     <li>{@code {"action":"setRuleEnabled","name":"X","enabled":true}}</li>
 *     <li>{@code {"action":"setProbability","name":"X","probability":0.25}}</li>
 * </ul>
 */
@Endpoint(id = "faultinjector")
public class FaultInjectionEndpoint {

    private final FaultInjectionProperties properties;
    private final FaultDecisionStrategy strategy;

    public FaultInjectionEndpoint(FaultInjectionProperties properties, FaultDecisionStrategy strategy) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.strategy = Objects.requireNonNull(strategy, "strategy");
    }

    /**
     * @return the current enabled flag, defaults, and per-rule configuration
     *         with match/trigger counters where available.
     */
    @ReadOperation
    public Map<String, Object> describe() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", properties.isEnabled());
        out.put("defaults", properties.getDefaults());

        Map<String, RuleMetrics> metrics = strategy instanceof FaultDecisionStrategyImpl
                ? ((FaultDecisionStrategyImpl) strategy).metricsSnapshot()
                : Collections.emptyMap();

        List<Map<String, Object>> rules = new ArrayList<>();
        for (Rule rule : properties.getRules()) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("name", rule.getName());
            view.put("enabled", rule.isEnabled());
            view.put("fault", rule.getFault());
            view.put("mode", rule.getMode());
            view.put("hostPattern", rule.getHostPattern());
            view.put("urlPattern", rule.getUrlPattern());
            view.put("methods", rule.safeMethods());
            view.put("probability", rule.getProbability());
            view.put("everyN", rule.getEveryN());
            view.put("delayMs", rule.getDelayMs());
            view.put("errorStatus", rule.getErrorStatus());
            RuleMetrics rm = rule.getName() != null ? metrics.get(rule.getName()) : null;
            view.put("matchCount", rm != null ? rm.matchCount() : 0L);
            view.put("triggerCount", rm != null ? rm.triggerCount() : 0L);
            rules.add(view);
        }
        out.put("rules", rules);
        return out;
    }

    /**
     * Apply a targeted update. Spring Boot Actuator binds each top-level JSON
     * body key to a parameter of this method, so the request shape is
     * {@code {"action":"...", "name":"...", "enabled":true, "probability":0.2}}.
     * Fields not relevant to the chosen action may be omitted.
     */
    @WriteOperation
    public Map<String, Object> update(String action,
                                      @Nullable String name,
                                      @Nullable Boolean enabled,
                                      @Nullable Double probability) {
        if (action == null) {
            throw new IllegalArgumentException("Missing 'action' field");
        }
        switch (action) {
            case "enable":
                properties.setEnabled(true);
                break;
            case "disable":
                properties.setEnabled(false);
                break;
            case "setRuleEnabled":
                requireRule(name).setEnabled(Boolean.TRUE.equals(enabled));
                break;
            case "setProbability":
                if (probability == null) {
                    throw new IllegalArgumentException("'probability' is required for setProbability");
                }
                if (probability < 0.0d || probability > 1.0d) {
                    throw new IllegalArgumentException("'probability' must be in [0.0, 1.0]");
                }
                requireRule(name).setProbability(probability);
                break;
            default:
                throw new IllegalArgumentException("Unknown action: " + action);
        }
        return Collections.singletonMap("status", "applied");
    }

    private Rule requireRule(String name) {
        if (name == null) {
            throw new IllegalArgumentException("Missing 'name' field");
        }
        return properties.getRules().stream()
                .filter(r -> name.equals(r.getName()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No rule named: " + name));
    }
}
